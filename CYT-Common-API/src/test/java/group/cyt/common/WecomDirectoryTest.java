package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.wecom.WecomAdapter;
import group.cyt.common.module.adapter.support.TokenCache;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WecomDirectoryTest {
  WecomDirectoryTest() throws Exception {}
  final OperationRegistry registry = new OperationRegistry(mock(GatewayStore.class));
  final CredentialService credentials = mock(CredentialService.class);
  final UpstreamHttp http = mock(UpstreamHttp.class);

  WecomAdapter adapter(boolean contact) {
    when(credentials.resolve(anyString(), eq("oa"))).thenReturn("");
    when(credentials.required("WEWORK_SECRET", "oa")).thenReturn("fixture-secret");
    when(credentials.required("WEWORK_CORP_ID", "oa")).thenReturn("fixture-corp");
    if (contact) when(credentials.resolve("WEWORK_CONTACT_SECRET", "oa")).thenReturn("fixture-contact");
    when(http.object(eq("GET"), anyString(), anyMap(), isNull()))
        .thenReturn(JsonUtils.obj("errcode", 0, "access_token", "fixture-token", "expires_in", 7200));
    return new WecomAdapter(credentials, http, new TokenCache(), registry);
  }
  Object departments() {
    return JsonUtils.obj("errcode", 0, "department", List.of(JsonUtils.obj("id", 1, "parentid", 0, "name", "Company")));
  }
  Object members(int count) {
    return JsonUtils.obj("errcode", 0, "userlist", IntStream.range(0, count)
        .mapToObj(i -> JsonUtils.obj("userid", "u" + i, "name", "Member " + i)).toList());
  }
  Map<String, Object> member(String url) {
    String id = Arrays.stream(URI.create(url).getQuery().split("&"))
        .filter(s -> s.startsWith("userid=")).findFirst().orElseThrow().substring(7);
    return JsonUtils.obj("errcode", 0, "userid", id, "name", id, "department", List.of(1), "status", 1);
  }

  @Test void fallbackDetailsAreConcurrentBoundedDeduplicatedAndStable() throws Exception {
    WecomAdapter adapter = adapter(false);
    CountDownLatch firstWave = new CountDownLatch(20), release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), reads = new AtomicInteger();
    when(http.json(eq("GET"), anyString(), anyMap(), isNull(), anyDouble())).thenAnswer(call -> {
      String url = call.getArgument(1);
      if (url.contains("/department/list")) return departments();
      if (url.contains("/user/simplelist")) return members(45);
      if (url.contains("/user/get")) {
        reads.incrementAndGet();
        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        firstWave.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); return member(url); }
        finally { active.decrementAndGet(); }
      }
      throw new AssertionError("Unexpected path");
    });
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      var run = caller.submit(() -> adapter.invoke(registry.get("wecom.directory.users"),
          JsonUtils.obj("department_ids", List.of(1, 2)), "oa"));
      assertTrue(firstWave.await(5, TimeUnit.SECONDS), "详情读取应并发推进");
      assertEquals(20, peak.get()); assertEquals(20, reads.get());
      release.countDown();
      var result = JsonUtils.map(run.get(5, TimeUnit.SECONDS));
      assertEquals(45, JsonUtils.list(result.get("users")).size());
      assertEquals(45, reads.get(), "跨部门重复成员只补一次详情");
      assertEquals("u0", JsonUtils.map(JsonUtils.list(result.get("users")).getFirst()).get("userid"));
      verify(http, times(1)).object(eq("GET"), contains("/gettoken"), anyMap(), isNull());
    } finally { release.countDown(); caller.shutdownNow(); adapter.close(); }
  }

  @Test void contactSecretUsesBulkFastPathWithoutMemberRequests() {
    WecomAdapter adapter = adapter(true);
    when(http.json(eq("GET"), anyString(), anyMap(), isNull(), anyDouble())).thenAnswer(call -> {
      String url = call.getArgument(1);
      if (url.contains("/department/list")) return departments();
      if (url.contains("/user/list")) return members(45);
      throw new AssertionError("Bulk path must not fetch members one by one");
    });
    try {
      var result = JsonUtils.map(adapter.invoke(registry.get("wecom.directory.list"), Map.of(), "oa"));
      assertEquals(45, JsonUtils.list(result.get("users")).size());
      assertEquals("contact_secret", result.get("mode"));
    } finally { adapter.close(); }
  }

  @Test void anyDetailFailureRejectsTheSnapshotAndPreservesRateLimitError() {
    WecomAdapter adapter = adapter(false);
    when(http.json(eq("GET"), anyString(), anyMap(), isNull(), anyDouble())).thenAnswer(call -> {
      String url = call.getArgument(1);
      if (url.contains("/department/list")) return departments();
      if (url.contains("/user/simplelist")) return members(45);
      if (url.contains("userid=u0&")) return JsonUtils.obj("errcode", 45009);
      if (url.contains("/user/get")) return member(url);
      throw new AssertionError("Unexpected path");
    });
    try {
      var error = assertThrows(GatewayException.class,
          () -> adapter.invoke(registry.get("wecom.directory.list"), Map.of(), "oa"));
      assertEquals(429, error.status); assertEquals("WECOM_45009", error.code);
    } finally { adapter.close(); }
  }

  @Test void explicitUserLookupKeepsMissingIdsAndDoesNotRepeatDuplicateRequests() {
    WecomAdapter adapter = adapter(false);
    when(http.json(eq("GET"), anyString(), anyMap(), isNull(), anyDouble())).thenAnswer(call -> {
      String url = call.getArgument(1);
      if (url.contains("/department/list")) return departments();
      if (url.contains("userid=gone&")) return JsonUtils.obj("errcode", 60111);
      if (url.contains("/user/get")) return member(url);
      throw new AssertionError("Unexpected path");
    });
    try {
      var result = JsonUtils.map(adapter.invoke(registry.get("wecom.directory.users"),
          JsonUtils.obj("userids", List.of("u0", "gone", "u0")), "oa"));
      assertEquals(1, JsonUtils.list(result.get("users")).size());
      assertEquals(List.of("gone"), result.get("missing"));
      verify(http, times(2)).json(eq("GET"), contains("/user/get"), anyMap(), isNull(), anyDouble());
    } finally { adapter.close(); }
  }
}

package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.*;
import group.cyt.common.module.adapter.support.*;
import group.cyt.common.module.grant.service.QuotaService;
import group.cyt.common.module.registry.model.OperationDefinition;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

class BusinessWorkflowsTest {
  static Stream<String> workflows() {
    return BusinessWorkflows.SPECS.keySet().stream();
  }

  private final PlatformAdapter nativeAdapter = mock(PlatformAdapter.class);
  private final GatewaySettings settings =
      new GatewaySettings(
          new MockEnvironment().withProperty("ADMIN_JWT_SECRET", "fixture-business-cursor-key"));
  private OperationRegistry registry;
  private BusinessWorkflows service;

  @BeforeEach
  void setup() throws Exception {
    registry = new OperationRegistry(mock(GatewayStore.class));
    when(nativeAdapter.supports(anyString())).thenReturn(true);
    service =
        new BusinessWorkflows(
            List.of(nativeAdapter),
            new MockAdapter(),
            registry,
            new CursorCodec(settings),
            mock(QuotaService.class),
            settings);
  }

  private Map<String, Object> call(String key, Map<String, Object> params, String system) {
    var op = registry.get(key);
    return JsonUtils.map(service.invoke(op, registry.validate(op, params), system, false));
  }

  private Map<String, Object> response(
      BusinessWorkflows.Spec spec, List<Object> rows, boolean more) {
    var result = JsonUtils.obj(spec.items(), rows);
    switch (spec.kind()) {
      case "google", "gmail" -> {
        if (more) result.put("nextPageToken", "next");
      }
      case "graphql" ->
          result.put("page_info", JsonUtils.obj("hasNextPage", more, "endCursor", "next"));
      case "s3" -> {
        result.put("truncated", more);
        result.put("next_token", more ? "next" : null);
      }
      case "mabang" -> {
        result.put("has_next", more);
        result.put("next_cursor", more ? "next" : null);
      }
      case "offset" -> {
        result.put("has_more", more);
        result.put("next_start_position", more ? 2 : null);
      }
      case "page" -> {
        result.put("page", more ? 1 : 2);
        result.put("total_page", 2);
      }
      case "total_offset" -> result.put("total", 2);
      default -> {
        result.put("has_more", more);
        result.put("cursor", more ? Map.of("page", 2) : Map.of());
      }
    }
    return result;
  }

  @ParameterizedTest
  @MethodSource("workflows")
  void everyCollectorFollowsItsNativeCursorAndReturnsCommonPage(String key) {
    var spec = BusinessWorkflows.SPECS.get(key);
    when(nativeAdapter.invoke(any(), anyMap(), eq("caller")))
        .thenAnswer(
            a -> {
              OperationDefinition op = a.getArgument(0);
              var p = JsonUtils.map(a.getArgument(1));
              if (op.key().equals("gmail.messages.get"))
                return JsonUtils.obj("id", p.get("message_id"), "payload", Map.of());
              Object cursor = p.get(spec.cursor());
              boolean first =
                  cursor == null
                      || cursor.toString().isBlank()
                      || cursor instanceof Map<?, ?> m && m.isEmpty()
                      || cursor instanceof Number n
                          && n.intValue() == (spec.kind().equals("page") ? 1 : 0);
              return response(spec, List.of(Map.of("id", first ? "record-A" : "record-B")), first);
            });
    var result =
        call(
            key,
            JsonUtils.map(
                JsonUtils.parse(registry.get(key).schema().path("examples").get(0).toString())),
            "caller");
    assertEquals(2, JsonUtils.list(result.get("items")).size());
    assertEquals(false, JsonUtils.map(result.get("page")).get("has_more"));
    assertEquals(2, JsonUtils.map(result.get("page")).get("pages"));
  }

  @Test
  void itemLimitIsResumableAndQueryOrCallerCannotChangeUnderCursor() {
    var spec = BusinessWorkflows.SPECS.get("gdrive.files.search");
    when(nativeAdapter.invoke(any(), anyMap(), anyString()))
        .thenReturn(response(spec, List.of(Map.of("id", "A")), true));
    var result = call("gdrive.files.search", Map.of("max_items", 1), "caller");
    String cursor = JsonUtils.str(JsonUtils.map(result.get("page")), "next_cursor");
    assertFalse(cursor.isBlank());
    assertThrows(
        GatewayException.class,
        () ->
            call(
                "gdrive.files.search",
                JsonUtils.obj(
                    "max_items", 1, "resume_cursor", cursor, "name_contains", "different"),
                "caller"));
    assertThrows(
        GatewayException.class,
        () -> call("gdrive.files.search", Map.of("resume_cursor", cursor), "other"));
  }

  @Test
  void emptyPageWithContinuationStillFetchesTheNextPage() {
    var spec = BusinessWorkflows.SPECS.get("gdrive.files.search");
    when(nativeAdapter.invoke(any(), anyMap(), anyString()))
        .thenReturn(
            response(spec, List.of(), true), response(spec, List.of(Map.of("id", "B")), false));
    var result = call("gdrive.files.search", Map.of(), "caller");
    assertEquals(1, JsonUtils.list(result.get("items")).size());
    assertEquals(2, JsonUtils.map(result.get("page")).get("pages"));
  }

  @Test
  void oversizedNativePageIsRejectedInsteadOfSilentlyDroppingRecords() {
    var spec = BusinessWorkflows.SPECS.get("gdrive.files.search");
    when(nativeAdapter.invoke(any(), anyMap(), anyString()))
        .thenReturn(response(spec, List.of(Map.of("id", "A"), Map.of("id", "B")), true));
    assertThrows(
        GatewayException.class,
        () -> call("gdrive.files.search", Map.of("page_size", 1), "caller"));
  }

  @Test
  void numberedPagesKeepFixedSizeAndResumeInsideAPageWithoutSkipping() {
    var spec = BusinessWorkflows.SPECS.get("judgeme.reviews.collect");
    when(nativeAdapter.invoke(any(), anyMap(), anyString()))
        .thenAnswer(
            a -> {
              var p = JsonUtils.map(a.getArgument(1));
              assertEquals(3, p.get("limit"));
              int page = JsonUtils.integer(JsonUtils.map(p.get("cursor")).get("page"), 1);
              return response(
                  spec,
                  java.util.stream.IntStream.rangeClosed((page - 1) * 3 + 1, page * 3)
                      .mapToObj(i -> (Object) Map.of("id", "row-" + i))
                      .toList(),
                  page == 1);
            });
    var first = call("judgeme.reviews.collect", Map.of("page_size", 3, "max_items", 4), "caller");
    String cursor = JsonUtils.str(JsonUtils.map(first.get("page")), "next_cursor");
    var next =
        call(
            "judgeme.reviews.collect",
            Map.of("page_size", 3, "max_items", 4, "resume_cursor", cursor),
            "caller");
    assertEquals(
        List.of("row-1", "row-2", "row-3", "row-4"),
        JsonUtils.list(first.get("items")).stream()
            .map(r -> JsonUtils.str(JsonUtils.map(r), "id"))
            .toList());
    assertEquals(
        List.of("row-5", "row-6"),
        JsonUtils.list(next.get("items")).stream()
            .map(r -> JsonUtils.str(JsonUtils.map(r), "id"))
            .toList());
    assertEquals(false, JsonUtils.map(next.get("page")).get("has_more"));
    assertThrows(
        GatewayException.class,
        () ->
            call(
                "judgeme.reviews.collect",
                Map.of("page_size", 2, "resume_cursor", cursor),
                "caller"));
    doReturn(response(spec, List.of(Map.of("id", "changed")), false))
        .when(nativeAdapter)
        .invoke(any(), anyMap(), anyString());
    assertEquals(
        409,
        assertThrows(
                GatewayException.class,
                () ->
                    call(
                        "judgeme.reviews.collect",
                        Map.of("page_size", 3, "resume_cursor", cursor),
                        "caller"))
            .status);
  }

  @Test
  void folderEntriesShareBudgetAndPartialProviderErrorsArePreserved() {
    var spec = BusinessWorkflows.SPECS.get("nas.objects.collect");
    var page = response(spec, List.of(Map.of("id", "A")), false);
    page.put("prefixes", List.of("folder/"));
    page.put("errors", List.of(Map.of("id", "bad", "code", "detail_failed")));
    when(nativeAdapter.invoke(any(), anyMap(), anyString())).thenReturn(page);
    var first = call("nas.objects.collect", Map.of("max_items", 1), "caller");
    assertEquals(1, JsonUtils.list(first.get("errors")).size());
    var next =
        call(
            "nas.objects.collect",
            Map.of("resume_cursor", JsonUtils.map(first.get("page")).get("next_cursor")),
            "caller");
    assertEquals(List.of("folder/"), next.get("folders"));
    assertTrue(JsonUtils.list(next.get("items")).isEmpty());
  }

  @Test
  void providerPageSizeIsClampedAndRepeatedCursorStops() {
    var spec = BusinessWorkflows.SPECS.get("shopify.orders.collect");
    when(nativeAdapter.invoke(any(), anyMap(), anyString()))
        .thenAnswer(
            a -> {
              assertEquals(250, JsonUtils.map(a.getArgument(1)).get("limit"));
              return response(spec, List.of(Map.of("id", "A")), true);
            });
    assertThrows(
        GatewayException.class,
        () ->
            call("shopify.orders.collect", Map.of("page_size", 1000, "max_items", 1000), "caller"));
  }

  @Test
  void structuredFiltersEscapeDriveLiteralsAndUseEpochForGmailDates() {
    String drive =
        BusinessWorkflows.driveQuery(
            JsonUtils.obj("name_contains", "John's \\ notes", "parent_id", "parent"));
    assertTrue(drive.contains("John\\'s \\\\ notes"));
    assertTrue(drive.contains("'parent' in parents"));
    String gmail =
        BusinessWorkflows.gmailQuery(
            JsonUtils.obj("sender", "a@example.invalid", "since", "2026-11-05", "unread", true));
    assertTrue(gmail.contains("after:1793808000"));
    assertTrue(gmail.contains("is:unread"));
  }

  @Test
  void gmailHydrationDecodesNestedMimeButPreservesFailedIds() {
    var spec = BusinessWorkflows.SPECS.get("gmail.messages.search");
    when(nativeAdapter.invoke(any(), anyMap(), anyString()))
        .thenAnswer(
            a -> {
              OperationDefinition op = a.getArgument(0);
              var params = JsonUtils.map(a.getArgument(1));
              if (op.key().endsWith("list"))
                return response(
                    spec, List.of(Map.of("id", "mail-a"), Map.of("id", "mail-bad")), false);
              if (params.get("message_id").equals("mail-bad"))
                throw GatewayException.missing("missing");
              return JsonUtils.obj(
                  "id",
                  "A",
                  "payload",
                  JsonUtils.obj(
                      "headers",
                      List.of(JsonUtils.obj("name", "Subject", "value", "Test")),
                      "parts",
                      List.of(
                          JsonUtils.obj(
                              "mimeType",
                              "text/plain",
                              "headers",
                              List.of(
                                  JsonUtils.obj(
                                      "name", "Content-Type", "value", "text/plain; charset=GBK")),
                              "body",
                              JsonUtils.obj(
                                  "data",
                                  Base64.getUrlEncoder()
                                      .encodeToString(
                                          "你好"
                                              .getBytes(
                                                  java.nio.charset.Charset.forName("GBK"))))))));
            });
    var result = call("gmail.messages.search", Map.of("include_body", true), "caller");
    var items = JsonUtils.list(result.get("items"));
    assertEquals(2, items.size());
    assertEquals("你好", JsonUtils.map(items.getFirst()).get("body_text"));
    assertEquals("Test", JsonUtils.map(items.getFirst()).get("subject"));
    assertEquals(1, JsonUtils.list(result.get("errors")).size());
    assertEquals(true, result.get("partial"));
  }
}

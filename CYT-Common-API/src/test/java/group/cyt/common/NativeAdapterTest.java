package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.AdapterDispatcher;
import group.cyt.common.module.adapter.MockAdapter;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.adapter.docusign.DocusignAdapter;
import group.cyt.common.module.adapter.google.*;
import group.cyt.common.module.adapter.lingxing.LingxingAdapter;
import group.cyt.common.module.adapter.mabang.MabangAdapter;
import group.cyt.common.module.adapter.nas.NasAdapter;
import group.cyt.common.module.adapter.quickcep.QuickcepAdapter;
import group.cyt.common.module.adapter.shopify.ShopifyAdapter;
import group.cyt.common.module.adapter.source.CustomerSourcesAdapter;
import group.cyt.common.module.adapter.support.*;
import group.cyt.common.module.adapter.wecom.WecomAdapter;
import group.cyt.common.module.adapter.wecom.approval.*;
import group.cyt.common.module.adapter.winit.WinitAdapter;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.grant.service.QuotaService;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

class NativeAdapterTest {
  @Test
  void docusignPagingAndVoidPreflightStayOnTheConfiguredEndpoint() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
    var c =
        credentials(
            Map.of(
                "DOCUSIGN_INTEGRATION_KEY",
                "fixture-app",
                "DOCUSIGN_USER_ID",
                "fixture-user",
                "DOCUSIGN_PRIVATE_KEY",
                pem,
                "DOCUSIGN_ACCOUNT_ID",
                "fixture-account"));
    var http = mock(UpstreamHttp.class);
    when(http.form(anyString(), anyMap(), anyMap()))
        .thenReturn(JsonUtils.obj("access_token", "fixture-token", "expires_in", 3600));
    when(http.object(eq("GET"), contains("/envelopes?"), anyMap(), isNull()))
        .thenReturn(
            JsonUtils.obj(
                "resultSetSize",
                "10",
                "totalSetSize",
                "30",
                "envelopes",
                List.of(),
                "nextUri",
                "https://unexpected.invalid/path?start_position=20"));
    var adapter = new DocusignAdapter(c, http, new TokenCache());
    var registry = registry();
    var list = registry.get("docusign.envelopes.list");
    var page =
        JsonUtils.map(
            adapter.invoke(
                list,
                registry.validate(list, Map.of("start_position", 10, "page_size", 10)),
                "fixture"));
    assertEquals(20, page.get("next_start_position"));
    assertEquals(true, page.get("has_more"));
    verify(http)
        .object(
            eq("GET"),
            argThat(
                u ->
                    u.startsWith("https://demo.docusign.net/restapi/v2.1/accounts/")
                        && u.contains("start_position=10")),
            anyMap(),
            isNull());
    when(http.object(eq("GET"), endsWith("/envelopes/completed-id"), anyMap(), isNull()))
        .thenReturn(Map.of("status", "completed"));
    var op = registry.get("docusign.envelopes.void");
    assertEquals(
        409,
        assertThrows(
                GatewayException.class,
                () ->
                    adapter.invoke(
                        op,
                        registry.validate(
                            op, Map.of("envelope_id", "completed-id", "reason", "测试")),
                        "fixture"))
            .status);
    verify(http, never()).object(eq("PUT"), anyString(), anyMap(), any());
  }

  @Test
  void lingxingWorkorderKeepsRequiredTopLevelBusinessFields() throws Exception {
    var c =
        credentials(
            Map.of(
                "LINGXING_ACCOUNT",
                "fixture",
                "LINGXING_FIXTURE_APP_ID",
                "0123456789abcdef",
                "LINGXING_FIXTURE_APP_SECRET",
                "fixture-secret"));
    var http = mock(UpstreamHttp.class);
    when(http.raw(anyString(), anyString(), anyMap(), any(), anyDouble(), isNull()))
        .thenAnswer(
            a -> {
              String url = a.getArgument(1);
              if (url.endsWith("/api/auth-server/oauth/access-token"))
                return JsonUtils.write(
                        JsonUtils.obj(
                            "code",
                            "200",
                            "data",
                            JsonUtils.obj("access_token", "fixture-token", "expires_in", 3600)))
                    .getBytes(StandardCharsets.UTF_8);
              var body =
                  JsonUtils.read(new String((byte[]) a.getArgument(3), StandardCharsets.UTF_8));
              assertTrue(url.contains("/customerService/workOrder/add?"));
              assertEquals(123, JsonUtils.integer(body.get("globalOrderNo"), 0));
              assertEquals("reason-1", body.get("reasonUuid"));
              assertEquals(7, JsonUtils.integer(body.get("returnWid"), 0));
              return JsonUtils.write(JsonUtils.obj("code", 0, "data", "business-1"))
                  .getBytes(StandardCharsets.UTF_8);
            });
    var adapter =
        new LingxingAdapter(c, http, new ErpProxy(), mock(TestWritePolicy.class), new TokenCache());
    var registry = registry();
    var op = registry.get("lingxing.workorder.add");
    adapter.invoke(
        op,
        registry.validate(
            op,
            JsonUtils.obj(
                "platformOrderNos",
                List.of("order-1"),
                "globalOrderNo",
                123,
                "reasonUuid",
                "reason-1",
                "returnWid",
                7)),
        "fixture");
  }

  static Stream<Map<String, Object>> winitCases() throws Exception {
    return OperationContractTest.cases()
        .filter(c -> JsonUtils.str(c, "operation").startsWith("winit."));
  }

  static Stream<Map<String, Object>> quickcepCases() throws Exception {
    return OperationContractTest.cases()
        .filter(
            c ->
                JsonUtils.str(c, "operation").startsWith("quickcep.")
                    && !BusinessWorkflows.SPECS.containsKey(JsonUtils.str(c, "operation")));
  }

  private CredentialService credentials(Map<String, String> values) {
    var c = mock(CredentialService.class);
    when(c.resolve(anyString(), anyString()))
        .thenAnswer(a -> values.getOrDefault(a.getArgument(0), ""));
    when(c.required(anyString(), anyString()))
        .thenAnswer(
            a -> {
              String v = values.get(a.getArgument(0));
              if (v == null) throw GatewayException.config("Missing fixture credential");
              return v;
            });
    return c;
  }

  private OperationRegistry registry() throws Exception {
    return new OperationRegistry(mock(GatewayStore.class));
  }

  @ParameterizedTest
  @MethodSource("winitCases")
  void winitRoutesAndDoubleSignaturesMatchOriginal(Map<String, Object> fixture) throws Exception {
    var credentials =
        credentials(
            Map.of(
                "WINIT_APP_KEY",
                "fixture-key",
                "WINIT_TOKEN",
                "fixture-token",
                "WINIT_PLATFORM",
                "CaseSensitive",
                "WINIT_CLIENT_ID",
                "fixture-client",
                "WINIT_CLIENT_SECRET",
                "fixture-client-secret",
                "WINIT_ENV",
                "sandbox"));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get(JsonUtils.str(fixture, "operation"));
    var params = registry.validate(op, JsonUtils.map(fixture.get("params")));
    Map<String, String> actions =
        Map.ofEntries(
            Map.entry("warehouses.list", "winit.pms.getWarehouseList"),
            Map.entry("returns.list", "rma.returnGoodsOrder.queryReturnOderList"),
            Map.entry("returns.items", "winit.mms.item.return.list"),
            Map.entry("returns.psc", "rma.returnGoodsOrder.queryPSCList"),
            Map.entry("returns.label", "rma.returnGoodsOrder.queryReturnLabel"),
            Map.entry("returns.create", "rma.returnGoodsOrder.createReturnGoodsOrder"),
            Map.entry("returns.void", "rma.returnGoodsOrder.voidOrder"),
            Map.entry("inbound.list", "winit.wh.inbound.getOrderList"),
            Map.entry("outbound.list", "queryOutboundOrderList"),
            Map.entry("inbound.tracking", "wh.tracking.queryOrderTracking"),
            Map.entry("outbound.tracking", "tracking.getOrderVerdorTracking"),
            Map.entry("inventory.list", "queryWarehouseStorage"));
    Object[] sent = new Object[1];
    when(http.json(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenAnswer(
            a -> {
              Map<String, Object> body = a.getArgument(3);
              sent[0] = body;
              assertEquals(actions.get(op.key().substring(6)), body.get("action"));
              assertTrue(body.get("data") instanceof Map<?, ?>);
              assertEquals(SignatureSupport.winit(body, "fixture-token"), body.get("sign"));
              assertEquals(
                  SignatureSupport.winit(body, "fixture-client-secret"), body.get("client_sign"));
              if (op.key().equals("winit.inbound.list"))
                return JsonUtils.obj(
                    "code",
                    "0",
                    "data",
                    JsonUtils.obj("orderList", List.of(Map.of("orderNo", "fixture-order"))));
              return JsonUtils.obj("code", "0", "data", Map.of());
            });
    var writePolicy = mock(TestWritePolicy.class);
    Object result = new WinitAdapter(credentials, http, writePolicy).invoke(op, params, "fixture");
    if (op.key().equals("winit.inbound.list"))
      assertEquals(1, JsonUtils.list(JsonUtils.map(result).get("list")).size());
    if (op.key().equals("winit.returns.create") || op.key().equals("winit.returns.void")) {
      // confirm is a gateway-side gesture; on void, warehouseCode is only the allow-list handle.
      // Neither may go up to Winit.
      verify(writePolicy).requireTarget(eq("winit"), anyString());
      Map<String, Object> data = JsonUtils.map(JsonUtils.map(sent[0]).get("data"));
      assertFalse(data.containsKey("confirm"));
      if (op.key().equals("winit.returns.void")) assertFalse(data.containsKey("warehouseCode"));
    }
    verify(http)
        .json(
            eq("POST"),
            eq("https://sandboxopenapi.winit.com.cn/openapi/service"),
            anyMap(),
            any(),
            anyDouble());
  }

  @ParameterizedTest
  @MethodSource("quickcepCases")
  void allQuickcepOperationsUseAccountSpecificRsaHeaders(Map<String, Object> fixture)
      throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(1024);
    var pair = generator.generateKeyPair();
    String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    var c =
        credentials(
            Map.of(
                "QUICKCEP_ACCOUNT",
                "fixture",
                "QUICKCEP_FIXTURE_ACCESS_ID",
                "fixture-access",
                "QUICKCEP_FIXTURE_PUBLIC_KEY",
                publicKey));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get(JsonUtils.str(fixture, "operation"));
    var params = registry.validate(op, JsonUtils.map(fixture.get("params")));
    when(http.json(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenAnswer(
            a -> {
              String url = a.getArgument(1);
              assertTrue(url.startsWith("https://app.quickcep.com/channel/chat/v1/"));
              Map<String, String> headers = a.getArgument(2);
              Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
              cipher.init(Cipher.DECRYPT_MODE, pair.getPrivate());
              String decrypted =
                  new String(
                      cipher.doFinal(Base64.getDecoder().decode(headers.get("quick-token"))),
                      StandardCharsets.UTF_8);
              assertEquals(
                  "{\"timestamp\":"
                      + headers.get("quick-timestamp")
                      + ", \"access_id\": \"fixture-access\"}",
                  decrypted);
              return JsonUtils.obj(
                  "code",
                  0,
                  "success",
                  true,
                  "data",
                  JsonUtils.obj("chatSubSessionIdList", List.of(2593605622431703041L)));
            });
    var result = JsonUtils.map(new QuickcepAdapter(c, http).invoke(op, params, "fixture"));
    if (op.key().equals("quickcep.sessions.list"))
      assertEquals(List.of("2593605622431703041"), result.get("ids"));
  }

  @Test
  void lingxingUsesMultipartTokenExchangeAndPreservesResponseContract() throws Exception {
    var c =
        credentials(
            Map.of(
                "LINGXING_ACCOUNT",
                "fixture",
                "LINGXING_FIXTURE_APP_ID",
                "0123456789abcdef",
                "LINGXING_FIXTURE_APP_SECRET",
                "fixture-secret"));
    var http = mock(UpstreamHttp.class);
    var tokenCalls = new java.util.concurrent.atomic.AtomicInteger();
    when(http.raw(anyString(), anyString(), anyMap(), any(), anyDouble(), isNull()))
        .thenAnswer(
            a -> {
              String url = a.getArgument(1);
              Map<String, String> headers = a.getArgument(2);
              byte[] body = a.getArgument(3);
              if (url.endsWith("/api/auth-server/oauth/access-token")) {
                tokenCalls.incrementAndGet();
                assertTrue(
                    headers.get("Content-Type").startsWith("multipart/form-data; boundary="));
                String raw = new String(body, StandardCharsets.UTF_8);
                assertTrue(raw.contains("name=\"appId\""));
                assertTrue(raw.contains("name=\"appSecret\""));
                return JsonUtils.write(
                        JsonUtils.obj(
                            "code",
                            "200",
                            "data",
                            JsonUtils.obj("access_token", "fixture-token", "expires_in", 3600)))
                    .getBytes(StandardCharsets.UTF_8);
              }
              // Business call: the signed params ride in the query, the body is the very bytes
              // the signature covered — compact and key-sorted, or Lingxing recomputes a
              // different string and answers 2001006 api sign not correct.
              assertTrue(
                  url.startsWith(
                      "https://openapi.lingxing.com/basicOpen/customerService/afterSalesReason/list?"));
              assertTrue(url.contains("sign="));
              assertTrue(url.contains("app_key=0123456789abcdef"));
              // access_token signs the request but is not sent in the query — it rides in the
              // Authorization header. Logging a URL that carries it would leak a live token.
              assertFalse(url.contains("access_token"));
              assertEquals("Bearer fixture-token", headers.get("Authorization"));
              assertEquals(
                  "{\"length\":20,\"offset\":0}", new String(body, StandardCharsets.UTF_8));
              return JsonUtils.write(
                      JsonUtils.obj(
                          "code",
                          0,
                          "data",
                          JsonUtils.obj(
                              "total",
                              "1",
                              "list",
                              List.of(JsonUtils.obj("reasonId", 7, "cause", "质量问题"))),
                          "request_id",
                          "fixture-request"))
                  .getBytes(StandardCharsets.UTF_8);
            });
    var registry = registry();
    var op = registry.get("lingxing.aftersales.reasons");
    var adapter =
        new LingxingAdapter(c, http, new ErpProxy(), mock(TestWritePolicy.class), new TokenCache());
    var params = registry.validate(op, Map.of());
    var result = JsonUtils.map(adapter.invoke(op, params, "fixture"));
    assertEquals("fixture-request", result.get("request_id"));
    var rows = JsonUtils.list(result.get("items"));
    assertEquals(1, rows.size());
    assertEquals("质量问题", JsonUtils.map(rows.get(0)).get("cause"));
    assertEquals(7, JsonUtils.integer(JsonUtils.map(rows.get(0)).get("reason_id"), 0));
    assertEquals(1, result.get("total"));
    // expires_in was 3600s: the second call must reuse the cached token, not re-exchange it
    adapter.invoke(op, params, "fixture");
    assertEquals(1, tokenCalls.get());
  }

  @Test
  void lingxingMailKeepsRequestedDateRangeInsteadOfExpandingItToAYear() throws Exception {
    var c =
        credentials(
            Map.of(
                "LINGXING_ACCOUNT",
                "fixture",
                "LINGXING_FIXTURE_APP_ID",
                "0123456789abcdef",
                "LINGXING_FIXTURE_APP_SECRET",
                "fixture-secret"));
    var http = mock(UpstreamHttp.class);
    when(http.raw(anyString(), anyString(), anyMap(), any(), anyDouble(), isNull()))
        .thenAnswer(
            a -> {
              String url = a.getArgument(1);
              if (url.endsWith("/api/auth-server/oauth/access-token"))
                return JsonUtils.write(
                        JsonUtils.obj(
                            "code",
                            200,
                            "data",
                            JsonUtils.obj("access_token", "fixture-token", "expires_in", 3600)))
                    .getBytes(StandardCharsets.UTF_8);
              var body =
                  JsonUtils.read(new String((byte[]) a.getArgument(3), StandardCharsets.UTF_8));
              assertEquals("2026-11-01", body.get("start_date"));
              assertEquals("2026-11-05", body.get("end_date"));
              return JsonUtils.write(JsonUtils.obj("code", 0, "data", List.of(), "total", 0))
                  .getBytes(StandardCharsets.UTF_8);
            });
    var registry = registry();
    var op = registry.get("lingxing.messages.list");
    var adapter =
        new LingxingAdapter(
            c, http, mock(ErpProxy.class), mock(TestWritePolicy.class), new TokenCache());
    var params =
        registry.validate(
            op,
            JsonUtils.obj(
                "email",
                "fixture@example.invalid",
                "start_date",
                "2026-11-01",
                "end_date",
                "2026-11-05"));
    var result = JsonUtils.map(adapter.invoke(op, params, "fixture"));
    assertEquals("2026-11-01", JsonUtils.map(result.get("cursor")).get("start_date"));
    params.put("start_date", "invalid");
    assertThrows(GatewayException.class, () -> adapter.invoke(op, params, "fixture"));
  }

  @Test
  void signaturesAndFernetAgreeWithPythonGoldenVectors() throws Exception {
    Map<String, Object> vectors;
    try (var input = getClass().getResourceAsStream("/signing-vectors.json")) {
      vectors =
          JsonUtils.MAPPER.readValue(
              input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
    var w = JsonUtils.map(vectors.get("winit"));
    assertEquals(
        w.get("signature"),
        SignatureSupport.winit(JsonUtils.map(w.get("params")), JsonUtils.str(w, "secret")));
    var l = JsonUtils.map(vectors.get("lingxing"));
    assertEquals(
        l.get("signature"),
        SignatureSupport.lingxing(JsonUtils.str(l, "app_id"), JsonUtils.map(l.get("params"))));
    var s = JsonUtils.map(vectors.get("s3"));
    assertEquals(
        s.get("signing_key"),
        HexFormat.of()
            .formatHex(
                NasAdapter.signingKey(
                    JsonUtils.str(s, "secret"),
                    JsonUtils.str(s, "date"),
                    JsonUtils.str(s, "region"))));
    var f = JsonUtils.map(vectors.get("fernet"));
    var service =
        new CredentialService(
            mock(GatewayStore.class),
            new GatewaySettings(
                new MockEnvironment()
                    .withProperty("CREDENTIAL_MASTER_KEY", JsonUtils.str(f, "master"))),
            mock(OperationRegistry.class));
    assertEquals(f.get("plaintext"), service.decrypt(JsonUtils.str(f, "ciphertext")));
  }

  @Test
  void gmailRejectsUnknownMailboxBeforeReadingMessages() throws Exception {
    var token = mock(GoogleTokenService.class);
    when(token.user("fixture")).thenReturn("fixture-token");
    var http = mock(UpstreamHttp.class);
    when(http.object(eq("GET"), contains("/profile"), anyMap(), isNull()))
        .thenReturn(JsonUtils.obj("emailAddress", "actual@example.invalid"));
    var settings = new GatewaySettings(new MockEnvironment());
    var adapter = new GoogleAdapter(token, http, mock(CredentialService.class), settings);
    var registry = registry();
    var op = registry.get("gmail.messages.list");
    assertEquals(
        403,
        assertThrows(
                GatewayException.class,
                () ->
                    adapter.invoke(
                        op,
                        registry.validate(op, Map.of("account", "unknown@example.invalid")),
                        "fixture"))
            .status);
    verify(http, times(1)).object(anyString(), anyString(), anyMap(), isNull());
  }

  @Test
  void nasPresignedUrlRetainsProxyPrefixAndEncodesUnicodeOnce() throws Exception {
    var c =
        credentials(
            Map.of(
                "NAS_MINIO_ENDPOINT",
                "https://nas.example.invalid/minio",
                "NAS_MINIO_ACCESS_KEY",
                "fixture-access",
                "NAS_MINIO_SECRET_KEY",
                "fixture-secret",
                "NAS_MINIO_BUCKET",
                "fixture-bucket"));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get("nas.objects.presign_get");
    var result =
        JsonUtils.map(
            new NasAdapter(c, http)
                .invoke(
                    op, registry.validate(op, Map.of("keys", List.of("中文 文件/a.txt"))), "fixture"));
    String url =
        JsonUtils.str(JsonUtils.map(JsonUtils.list(result.get("items")).getFirst()), "url");
    assertTrue(
        url.startsWith(
            "https://nas.example.invalid/minio/fixture-bucket/%E4%B8%AD%E6%96%87%20%E6%96%87%E4%BB%B6/a.txt?"));
    assertFalse(url.contains("/minio/minio"));
    assertTrue(url.contains("X-Amz-Signature="));
    verifyNoInteractions(http);
  }

  // ── mabang ──────────────────────────────────────────────────────────────────

  static Stream<Map<String, Object>> mabangCases() throws Exception {
    return OperationContractTest.cases()
        .filter(
            c ->
                Set.of("mabang.orders.list", "mabang.orders.track")
                    .contains(JsonUtils.str(c, "operation")));
  }

  private static final Map<String, String> MABANG =
      Map.of(
          "MABANG_APP_KEY", "fixture-app",
          "MABANG_SECRET", "fixture-secret",
          "MABANG_PROXY_BASE", "https://relay.example.invalid",
          "MABANG_PROXY_TOKEN", "fixture-proxy-token");

  @ParameterizedTest
  @MethodSource("mabangCases")
  void mabangSignsTheExactBytesItSendsThroughTheRelay(Map<String, Object> fixture)
      throws Exception {
    var credentials = credentials(MABANG);
    var proxy = mock(ErpProxy.class);
    var registry = registry();
    var op = registry.get(JsonUtils.str(fixture, "operation"));
    var params = registry.validate(op, JsonUtils.map(fixture.get("params")));

    when(proxy.request(any(), any(), any(), any(), any(), any(), any(), any(), anyDouble()))
        .thenAnswer(
            a -> {
              assertEquals("mabang", (String) a.getArgument(0));
              assertEquals("POST", (String) a.getArgument(1));
              assertEquals("/api/v2", (String) a.getArgument(2));
              assertEquals("https://relay.example.invalid", (String) a.getArgument(3));
              assertEquals("fixture-proxy-token", (String) a.getArgument(4));
              assertNull(a.getArgument(5)); // nothing in the query; the whole request is the body

              String body = new String((byte[]) a.getArgument(6), StandardCharsets.UTF_8);
              Map<String, String> headers = a.getArgument(7);
              // Authorization must cover the exact bytes sent — re-serialising the body for
              // signing would produce a different string and read as a wrong secret upstream
              assertEquals(
                  JsonUtils.hmac("fixture-secret", body),
                  headers.get("Authorization"),
                  "signature must cover the exact sent bytes");

              Map<String, Object> envelope = JsonUtils.map(JsonUtils.parse(body));
              assertEquals(
                  Set.of("api", "appkey", "data", "timestamp", "version"), envelope.keySet());
              assertEquals("fixture-app", envelope.get("appkey"));
              assertEquals("1", envelope.get("version"));
              assertTrue(
                  String.valueOf(envelope.get("timestamp")).matches("\\d+"),
                  "timestamp must be a string of digits (seconds)");
              // compact JSON: no spaces around colons
              assertFalse(body.contains(": "), "body must be compact JSON (no spaces)");
              // nulls must not appear in data (Python: exclude_none=True)
              assertFalse(
                  String.valueOf(envelope.get("data")).contains("null"),
                  "null params must be dropped before sending");

              // orders.list: code as string "200"; orders.track: code as integer 200
              // both are accepted (String.valueOf(code).equals("200"))
              if (op.key().equals("mabang.orders.list"))
                return JsonUtils.obj(
                    "code",
                    "200",
                    "data",
                    JsonUtils.obj(
                        "data",
                        List.of(Map.of("platformOrderId", "mb-1")),
                        "total",
                        1,
                        "hasNext",
                        true,
                        "nextCursor",
                        "cursor-2",
                        "code",
                        "000"));
              return JsonUtils.obj(
                  "code",
                  200,
                  "data",
                  JsonUtils.obj(
                      "trackInfos",
                      List.of(
                          JsonUtils.obj(
                              "status",
                              "3",
                              "trackInfo",
                              List.of(
                                  JsonUtils.obj("time", "2026-11-02 10:00", "content", "arrived"),
                                  JsonUtils.obj(
                                      "time", "2026-11-01 08:00", "content", "shipped"))))));
            });

    var adapter = new MabangAdapter(credentials, proxy);
    Object result = adapter.invoke(op, params, "fixture");

    if (op.key().equals("mabang.orders.list")) {
      Map<String, Object> r = JsonUtils.map(result);
      assertEquals(1, JsonUtils.list(r.get("orders")).size());
      assertEquals(1, r.get("total"));
      assertTrue(JsonUtils.bool(r.get("has_next")));
      assertEquals("cursor-2", r.get("next_cursor"));
      assertEquals("000", r.get("data_code"));
    } else {
      Map<String, Object> r = JsonUtils.map(result);
      assertEquals(1, r.get("count"));
      var items = JsonUtils.list(r.get("track_infos"));
      Map<String, Object> row = JsonUtils.map(items.get(0));
      assertEquals("妥投", row.get("status_text"));
      assertNull(row.get("status_bad"));
      var events = JsonUtils.list(row.get("trackInfo"));
      assertEquals(2, events.size());
      // ascending time order
      assertEquals("2026-11-01 08:00", JsonUtils.map(events.get(0)).get("time"));
      assertEquals("2026-11-02 10:00", JsonUtils.map(events.get(1)).get("time"));
    }
  }

  @Test
  void mabangFailsHalfConfiguredWithoutReachingTheRelay() throws Exception {
    var proxy = mock(ErpProxy.class);
    var registry = registry();
    var op = registry.get("mabang.orders.list");
    var params = registry.validate(op, Map.of());
    // only APP_KEY set; SECRET, PROXY_BASE, PROXY_TOKEN all absent
    var error =
        assertThrows(
            GatewayException.class,
            () ->
                new MabangAdapter(credentials(Map.of("MABANG_APP_KEY", "fixture-app")), proxy)
                    .invoke(op, params, "fixture"));
    assertEquals(503, error.status);
    assertEquals("not_configured", error.type);
    // every missing name at once: fixing one per deploy wastes three cycles
    assertTrue(error.getMessage().contains("MABANG_SECRET"));
    assertTrue(error.getMessage().contains("MABANG_PROXY_BASE"));
    assertTrue(error.getMessage().contains("MABANG_PROXY_TOKEN"));
    verifyNoInteractions(proxy);
  }

  @Test
  void mabangTreatsNoDataAsEmptyAndRateLimitAsRetryable() throws Exception {
    var credentials = credentials(MABANG);
    var proxy = mock(ErpProxy.class);
    var registry = registry();
    var trackOp = registry.get("mabang.orders.track");
    var trackParams = registry.validate(trackOp, Map.of("platformOrderId", "mb-99"));
    var adapter = new MabangAdapter(credentials, proxy);

    // 暂无数据 — a parcel that has not been scanned yet is a normal answer, not an error
    when(proxy.request(any(), any(), any(), any(), any(), any(), any(), any(), anyDouble()))
        .thenReturn(JsonUtils.obj("code", "500", "message", "暂无数据"));
    Map<String, Object> empty = JsonUtils.map(adapter.invoke(trackOp, trackParams, "fixture"));
    assertEquals(List.of(), empty.get("track_infos"));
    assertEquals(0, empty.get("count"));

    // 限频 → 429 retryable
    when(proxy.request(any(), any(), any(), any(), any(), any(), any(), any(), anyDouble()))
        .thenReturn(JsonUtils.obj("code", "429", "message", "请求频繁"));
    var limited =
        assertThrows(GatewayException.class, () -> adapter.invoke(trackOp, trackParams, "fixture"));
    assertEquals(429, limited.status);
    assertTrue(limited.retryable);

    // other business code → 502, non-retryable, carrying the vendor code
    when(proxy.request(any(), any(), any(), any(), any(), any(), any(), any(), anyDouble()))
        .thenReturn(JsonUtils.obj("code", "1001", "message", "appkey 无效"));
    var bad =
        assertThrows(GatewayException.class, () -> adapter.invoke(trackOp, trackParams, "fixture"));
    assertEquals(502, bad.status);
    assertFalse(bad.retryable);
    assertEquals("1001", bad.code);
  }

  @Test
  void mabangAcceptsDataHandedBackAsAJsonString() throws Exception {
    var credentials = credentials(MABANG);
    var proxy = mock(ErpProxy.class);
    var registry = registry();
    var op = registry.get("mabang.orders.list");
    var params = registry.validate(op, Map.of());

    // order-get-order-list-new sometimes returns data as a JSON string rather than an object
    String innerData =
        JsonUtils.write(
            JsonUtils.obj(
                "data",
                List.of(Map.of("platformOrderId", "mb-str")),
                "total",
                42,
                "hasNext",
                false,
                "code",
                "000"));
    when(proxy.request(any(), any(), any(), any(), any(), any(), any(), any(), anyDouble()))
        .thenReturn(JsonUtils.obj("code", "200", "data", innerData));

    Map<String, Object> result =
        JsonUtils.map(new MabangAdapter(credentials, proxy).invoke(op, params, "fixture"));
    assertEquals(1, JsonUtils.list(result.get("orders")).size());
    assertEquals(42, result.get("total"));
    assertFalse(JsonUtils.bool(result.get("has_next")));
    assertNull(result.get("next_cursor"));
  }

  // ── read-only CS sources ────────────────────────────────────────────────────

  static Stream<Map<String, Object>> sourceCases() throws Exception {
    return OperationContractTest.cases()
        .filter(
            c ->
                Set.of("judgeme", "trustpilot", "paypal", "stripe", "rd")
                        .contains(JsonUtils.str(c, "operation").split("\\.")[0])
                    && JsonUtils.str(c, "operation").endsWith(".list"));
  }

  /** Missing authorization must surface as an error, never as fake catalog rows. */
  @ParameterizedTest
  @MethodSource("sourceCases")
  void readOnlySourcesFailLoudlyWithoutAccountCredentials(Map<String, Object> fixture)
      throws Exception {
    var registry = registry();
    var op = registry.get(JsonUtils.str(fixture, "operation"));
    var params = registry.validate(op, JsonUtils.map(fixture.get("params")));
    // credentials resolve returns "" for every key; http mock is never wired
    var proxy = mock(ErpProxy.class);
    var http = mock(UpstreamHttp.class);
    var error =
        assertThrows(
            GatewayException.class,
            () ->
                new CustomerSourcesAdapter(credentials(Map.of()), http)
                    .invoke(op, params, "fixture"));
    assertEquals(503, error.status);
    assertEquals("not_configured", error.type);
    verifyNoInteractions(http);
  }

  @Test
  void judgemeRoutesPerAccountAndPagesByFullness() throws Exception {
    var credentials =
        credentials(
            Map.of(
                "JUDGEME_FIXTURE_API_TOKEN", "fixture-token",
                "JUDGEME_FIXTURE_SHOP_DOMAIN", "fixture.myshop.example"));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get("judgeme.reviews.list");
    // account="fixture" routes to JUDGEME_FIXTURE_*; limit=1 makes the single-row response full
    var params =
        registry.validate(
            op,
            JsonUtils.map(
                JsonUtils.obj(
                    "account", "fixture", "limit", 1, "cursor", JsonUtils.obj("page", 2))));
    when(http.json(anyString(), anyString(), anyMap(), isNull(), anyDouble()))
        .thenReturn(
            JsonUtils.obj(
                "reviews",
                List.of(
                    JsonUtils.obj(
                        "id",
                        99,
                        "rating",
                        5,
                        "body",
                        "great",
                        "reviewer",
                        JsonUtils.obj("email", "buyer@example.invalid", "name", "Alice"),
                        "product_external_id",
                        "SKU-1",
                        "product_external_id_type",
                        "sku",
                        "created_at",
                        "2026-10-01T12:00:00Z",
                        "deleted",
                        false))));
    Map<String, Object> result =
        JsonUtils.map(new CustomerSourcesAdapter(credentials, http).invoke(op, params, "fixture"));
    // URL carries the account-specific credentials and the cursor page
    var urlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(http).json(eq("GET"), urlCaptor.capture(), anyMap(), isNull(), anyDouble());
    assertTrue(urlCaptor.getValue().contains("shop_domain=fixture.myshop.example"));
    assertTrue(urlCaptor.getValue().contains("api_token=fixture-token"));
    assertTrue(urlCaptor.getValue().contains("page=2"));
    // items normalise to the gateway shape
    var items = JsonUtils.list(result.get("items"));
    assertEquals(1, items.size());
    Map<String, Object> row = JsonUtils.map(items.get(0));
    assertEquals("99", row.get("id")); // stringified
    assertEquals("buyer@example.invalid", row.get("customer_email"));
    assertEquals("SKU-1", row.get("sku"));
    assertEquals("2026-10-01", row.get("review_date"));
    // a full page (1 of 1) means there may be more; cursor advances to page 3
    assertTrue(JsonUtils.bool(result.get("has_more")));
    assertEquals(3, JsonUtils.integer(JsonUtils.map(result.get("cursor")).get("page"), 0));
  }

  /**
   * The real Judge.me list payload never says which kind of external id it sent (measured over all
   * 9329 epomaker reviews): product_external_id is the Shopify numeric product id, so the gateway
   * hands the product identity over instead of laundering it into sku, which would drag order
   * attribution along with it. '0' is the fake product the shop-level reviews hang off.
   */
  @Test
  void judgemeCarriesProductIdentityBecauseTheUpstreamHasNoSku() throws Exception {
    var credentials =
        credentials(
            Map.of(
                "JUDGEME_FIXTURE_API_TOKEN", "fixture-token",
                "JUDGEME_FIXTURE_SHOP_DOMAIN", "fixture.myshop.example"));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get("judgeme.reviews.list");
    var params =
        registry.validate(
            op, JsonUtils.map(JsonUtils.obj("account", "fixture", "limit", 3)));
    when(http.json(anyString(), anyString(), anyMap(), isNull(), anyDouble()))
        .thenReturn(
            JsonUtils.obj(
                "reviews",
                List.of(
                    JsonUtils.obj(
                        "id", 1,
                        "product_external_id", 8123456789012L,
                        "product_handle", "kp80-keyboard",
                        "product_title", "EPOMAKER KP80"),
                    JsonUtils.obj("id", 2, "product_external_id", "0"),
                    JsonUtils.obj(
                        "id",
                        3,
                        "product_external_id",
                        "SKU-9",
                        "product_external_id_type",
                        "SKU"))));
    var result =
        JsonUtils.map(
            new CustomerSourcesAdapter(credentials, http).invoke(op, params, "fixture"));
    var items = JsonUtils.list(result.get("items")).stream().map(JsonUtils::map).toList();
    assertEquals("8123456789012", items.get(0).get("product_id"));
    assertNull(items.get(0).get("sku"));
    assertEquals("kp80-keyboard", items.get(0).get("product_handle"));
    assertEquals("EPOMAKER KP80", items.get(0).get("product_title"));
    assertNull(items.get(1).get("product_id")); // 店铺级评价不是商品
    assertEquals("SKU-9", items.get(2).get("sku"));
    assertNull(items.get(2).get("product_id"));
  }

  /**
   * CS mirrors this page into shopify_products and reads variant codes straight off the node, and
   * Shopify charges nested `first` args multiplicatively — so the page stays capped at 50 products
   * even when the caller asks for the schema maximum.
   */
  @Test
  void shopifyProductsListCarriesVariantCodesAndCapsThePage() throws Exception {
    var credentials =
        credentials(
            Map.of(
                "SHOPIFY_STORE", "fixture.myshopify.com",
                "SHOPIFY_ACCESS_TOKEN", "fixture-token"));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get("shopify.products.list");
    var params = registry.validate(op, JsonUtils.map(JsonUtils.obj("limit", 250)));
    when(http.object(anyString(), anyString(), anyMap(), any()))
        .thenReturn(
            JsonUtils.obj(
                "data",
                JsonUtils.obj(
                    "products",
                    JsonUtils.obj(
                        "edges",
                        List.of(
                            JsonUtils.obj(
                                "node",
                                JsonUtils.obj(
                                    "id", "gid://shopify/Product/1",
                                    "title", "KP80",
                                    "variants",
                                    JsonUtils.obj(
                                        "count",
                                        12,
                                        "edges",
                                        List.of(
                                            JsonUtils.obj(
                                                "node",
                                                JsonUtils.obj("sku", "TH80-BLK"))))))),
                        "pageInfo", JsonUtils.obj("hasNextPage", false, "endCursor", null)))));
    var result =
        JsonUtils.map(
            new ShopifyAdapter(credentials, http, mock(TestWritePolicy.class))
                .invoke(op, params, "fixture"));
    var body = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(http).object(eq("POST"), anyString(), anyMap(), body.capture());
    assertEquals(50, JsonUtils.map(JsonUtils.map(body.getValue()).get("variables")).get("first"));
    var node =
        JsonUtils.map(JsonUtils.map(JsonUtils.list(result.get("items")).get(0)).get("variants"));
    assertEquals(12, node.get("count"));
    var firstVariant =
        JsonUtils.map(JsonUtils.map(JsonUtils.list(node.get("edges")).get(0)).get("node"));
    assertEquals("TH80-BLK", firstVariant.get("sku"));
    assertEquals("fixture.myshopify.com", result.get("store"));
  }

  @Test
  void stripeScalesAmountsAndCarriesTheIdCursor() throws Exception {
    var credentials = credentials(Map.of("STRIPE_FIXTURE_API_KEY", "sk_fixture"));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get("stripe.disputes.list");
    var params =
        registry.validate(op, JsonUtils.map(JsonUtils.obj("account", "fixture", "limit", 2)));
    when(http.json(anyString(), anyString(), anyMap(), isNull(), anyDouble()))
        .thenReturn(
            JsonUtils.obj(
                "data",
                List.of(
                    // 1050 JPY → "1050" (zero-decimal currency, not 10.50)
                    JsonUtils.obj(
                        "id", "dp_1",
                        "amount", 1050,
                        "currency", "jpy",
                        "status", "needs_response",
                        "evidence_details", JsonUtils.obj("due_by", 1_762_020_000L)),
                    // 1050 USD → "10.50"
                    JsonUtils.obj(
                        "id", "dp_2",
                        "amount", 1050,
                        "currency", "usd",
                        "status", "won")),
                "has_more",
                true));
    Map<String, Object> result =
        JsonUtils.map(new CustomerSourcesAdapter(credentials, http).invoke(op, params, "fixture"));
    var items = JsonUtils.list(result.get("items"));
    assertEquals("1050", JsonUtils.map(items.get(0)).get("amount"));
    assertEquals("10.5", JsonUtils.map(items.get(1)).get("amount"));
    // due_by epoch → ISO-8601 UTC with explicit +00:00 suffix (matches Python isoformat)
    String deadline = JsonUtils.str(JsonUtils.map(items.get(0)), "deadline");
    assertTrue(deadline.endsWith("+00:00"), "deadline must carry explicit UTC offset");
    // Stripe uses a last-id cursor, not a page counter
    assertEquals("dp_2", JsonUtils.map(result.get("cursor")).get("starting_after"));
    assertTrue(JsonUtils.bool(result.get("has_more")));
  }

  @Test
  void stripeRejectsHasMoreWithoutAUsableCursor() throws Exception {
    var credentials = credentials(Map.of("STRIPE_API_KEY", "sk_global"));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get("stripe.disputes.list");
    var params = registry.validate(op, Map.of());
    when(http.json(anyString(), anyString(), anyMap(), isNull(), anyDouble()))
        .thenReturn(JsonUtils.obj("data", List.of(), "has_more", true));
    var error =
        assertThrows(
            GatewayException.class,
            () -> new CustomerSourcesAdapter(credentials, http).invoke(op, params, "fixture"));
    assertEquals(502, error.status);
    assertEquals("upstream_error", error.type);
  }

  @Test
  void paypalReadsDetailPerDisputeAndUsesPayPalCursor() throws Exception {
    var credentials =
        credentials(
            Map.of(
                "PAYPAL_FIXTURE_CLIENT_ID", "fixture-client",
                "PAYPAL_FIXTURE_CLIENT_SECRET", "fixture-secret"));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get("paypal.disputes.list");
    // limit=80 must be clipped to PayPal's max page_size of 50
    var params =
        registry.validate(op, JsonUtils.map(JsonUtils.obj("account", "fixture", "limit", 80)));
    // auth token exchange
    when(http.form(anyString(), anyMap(), anyMap()))
        .thenReturn(JsonUtils.obj("access_token", "paypal-fixture-token"));
    // list call returns one summary row
    when(http.json(eq("GET"), contains("/v1/customer/disputes?"), anyMap(), isNull(), anyDouble()))
        .thenReturn(
            JsonUtils.obj(
                "items", List.of(JsonUtils.obj("dispute_id", "ppd-1")),
                "links", List.of(JsonUtils.obj("rel", "next"))));
    // detail call fills in the buyer context and evidence deadline the summary omits
    when(http.json(
            eq("GET"), contains("/v1/customer/disputes/ppd-1"), anyMap(), isNull(), anyDouble()))
        .thenReturn(
            JsonUtils.obj(
                "dispute_id", "ppd-1",
                "status", "NEEDS_RESPONSE",
                "dispute_amount", JsonUtils.obj("value", "25.00", "currency_code", "USD"),
                "disputed_transactions",
                    List.of(
                        JsonUtils.obj(
                            "buyer",
                            JsonUtils.obj("email", "buyer@example.invalid", "name", "Bob"),
                            "invoice_number",
                            "ORD-99")),
                "seller_response_due_date", "2026-11-15T00:00:00Z"));
    Map<String, Object> result =
        JsonUtils.map(new CustomerSourcesAdapter(credentials, http).invoke(op, params, "fixture"));
    var items = JsonUtils.list(result.get("items"));
    assertEquals(1, items.size());
    Map<String, Object> row = JsonUtils.map(items.get(0));
    assertEquals("buyer@example.invalid", row.get("customer_email"));
    assertEquals("ORD-99", row.get("order_no"));
    assertEquals("25.00", row.get("amount"));
    // page_size clipped to 50
    // both legs: the list page (clipped to PayPal's max of 50) and one detail fetch per dispute
    var urls = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(http, times(2)).json(eq("GET"), urls.capture(), anyMap(), isNull(), anyDouble());
    assertTrue(
        urls.getAllValues().stream()
            .anyMatch(
                u ->
                    u.startsWith("https://api-m.sandbox.paypal.com/v1/customer/disputes?")
                        && u.contains("page_size=50")),
        "ENV unset must default to sandbox and page_size must be clipped to 50");
    assertTrue(
        urls.getAllValues().contains("https://api-m.sandbox.paypal.com/v1/customer/disputes/ppd-1"),
        "the summary omits buyer context, so each dispute needs its own detail call");
    // PayPal signals next page via a "next" link, not item count
    assertTrue(JsonUtils.bool(result.get("has_more")));
  }

  @Test
  void paypalRejectsUnknownEnvironment() throws Exception {
    var credentials =
        credentials(
            Map.of(
                "PAYPAL_CLIENT_ID", "fixture-client",
                "PAYPAL_CLIENT_SECRET", "fixture-secret",
                "PAYPAL_ENV", "staging"));
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get("paypal.disputes.list");
    var params = registry.validate(op, Map.of());
    var error =
        assertThrows(
            GatewayException.class,
            () -> new CustomerSourcesAdapter(credentials, http).invoke(op, params, "fixture"));
    assertEquals(422, error.status);
    verifyNoInteractions(http);
  }

  @Test
  void rdRejectsNonLoopbackHttpAndStampsCursorFromLatestUpdate() throws Exception {
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var op = registry.get("rd.products.list");
    var params = registry.validate(op, Map.of());
    // non-loopback HTTP is forbidden: pricing data must not cross the network unencrypted
    var insecure = credentials(Map.of("RD_BASE_URL", "http://rd.example.invalid"));
    var error =
        assertThrows(
            GatewayException.class,
            () -> new CustomerSourcesAdapter(insecure, http).invoke(op, params, "fixture"));
    assertEquals(422, error.status);
    verifyNoInteractions(http);

    // loopback HTTP is allowed for local testing
    var local = credentials(Map.of("RD_BASE_URL", "http://127.0.0.1:9000"));
    when(http.json(anyString(), anyString(), anyMap(), isNull(), anyDouble()))
        .thenReturn(
            JsonUtils.obj(
                "success",
                true,
                "data",
                List.of(
                    JsonUtils.obj(
                        "product_id", 1,
                        "mabang_sku", "MB-001",
                        "sku", "SKU-001",
                        "name", "Widget",
                        "status", "active",
                        "updated_at", "2026-11-01T08:00:00Z"))));
    Map<String, Object> result =
        JsonUtils.map(new CustomerSourcesAdapter(local, http).invoke(op, params, "fixture"));
    var items = JsonUtils.list(result.get("items"));
    Map<String, Object> row = JsonUtils.map(items.get(0));
    // mabang_sku takes precedence over sku; pricing fields are stripped
    assertEquals("MB-001", row.get("sku"));
    assertFalse(row.containsKey("cost_price"));
    assertFalse(row.containsKey("price"));
    assertFalse(JsonUtils.bool(result.get("has_more")));
    // cursor carries the latest updated_at stamp for incremental rescans
    assertEquals("2026-11-01T08:00:00Z", JsonUtils.map(result.get("cursor")).get("updated_at"));
  }

  /**
   * Trustpilot only returns consumer identity on the authenticated private endpoint. Publishing a
   * null there would be a lie in one direction and a PII leak if the gate were ever removed.
   */
  @Test
  void trustpilotRevealsConsumerIdentityOnlyOnThePrivateEndpoint() throws Exception {
    var registry = registry();
    var op = registry.get("trustpilot.reviews.list");
    var params = registry.validate(op, Map.of("account", "fixture"));
    var review =
        JsonUtils.obj(
            "id", "r-1",
            "stars", 4,
            "text", "ok",
            "consumer", JsonUtils.obj("email", "consumer@example.invalid", "displayName", "Cara"),
            "referenceId", "ORDER-5",
            "createdAt", "2026-10-20T07:00:00Z",
            "isDeleted", false);

    Object[] publicCall = new Object[2];
    var publicHttp = mock(UpstreamHttp.class);
    when(publicHttp.json(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenAnswer(
            a -> {
              publicCall[0] = a.getArgument(1);
              publicCall[1] = a.getArgument(2);
              return JsonUtils.obj("reviews", List.of(review));
            });
    var publicRow =
        JsonUtils.map(
            JsonUtils.list(
                    JsonUtils.map(
                            new CustomerSourcesAdapter(
                                    credentials(
                                        Map.of(
                                            "TRUSTPILOT_FIXTURE_BUSINESS_UNIT_ID", "unit-1",
                                            "TRUSTPILOT_FIXTURE_API_KEY", "fixture-api-key")),
                                    publicHttp)
                                .invoke(op, params, "fixture"))
                        .get("items"))
                .get(0));
    assertTrue(
        String.valueOf(publicCall[0]).startsWith("https://api.trustpilot.com/v1/business-units/"));
    assertFalse(String.valueOf(publicCall[0]).contains("/private/"));
    assertEquals("fixture-api-key", JsonUtils.map(publicCall[1]).get("apikey"));
    assertNull(publicRow.get("customer_email"));
    assertNull(publicRow.get("order_no"));

    Object[] privateCall = new Object[2];
    var privateHttp = mock(UpstreamHttp.class);
    when(privateHttp.json(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenAnswer(
            a -> {
              privateCall[0] = a.getArgument(1);
              privateCall[1] = a.getArgument(2);
              return JsonUtils.obj("reviews", List.of(review));
            });
    var privateRow =
        JsonUtils.map(
            JsonUtils.list(
                    JsonUtils.map(
                            new CustomerSourcesAdapter(
                                    credentials(
                                        Map.of(
                                            "TRUSTPILOT_FIXTURE_BUSINESS_UNIT_ID", "unit-1",
                                            "TRUSTPILOT_FIXTURE_ACCESS_TOKEN", "fixture-oauth")),
                                    privateHttp)
                                .invoke(op, params, "fixture"))
                        .get("items"))
                .get(0));
    assertTrue(
        String.valueOf(privateCall[0])
            .startsWith("https://api.trustpilot.com/v1/private/business-units/"));
    assertEquals("Bearer fixture-oauth", JsonUtils.map(privateCall[1]).get("Authorization"));
    assertEquals("consumer@example.invalid", privateRow.get("customer_email"));
    assertEquals("ORDER-5", privateRow.get("order_no"));
    assertEquals(4, privateRow.get("rating"));
    assertEquals("2026-10-20", privateRow.get("review_date"));
  }

  /**
   * contract.json declares an operation is routable; this asserts a production adapter actually
   * claims every platform, so a declared operation never dies at 平台适配器未加载.
   */
  @Test
  void everyContractedPlatformHasAProductionAdapter() throws Exception {
    var credentials = credentials(Map.of());
    var http = mock(UpstreamHttp.class);
    var registry = registry();
    var unclaimed = new TreeSet<String>();
    var adapters = productionAdapters(credentials, http, registry);
    for (var platform : registry.contract().path("platforms")) {
      String name = platform.path("platform").asText();
      if (adapters.stream().noneMatch(a -> a.supports(name))) unclaimed.add(name);
    }
    assertEquals(Set.of(), unclaimed, "契约已登记但无适配器实现: " + unclaimed);
  }

  private List<PlatformAdapter> productionAdapters(
      CredentialService credentials, UpstreamHttp http, OperationRegistry registry)
      throws Exception {
    var proxy = mock(ErpProxy.class);
    when(proxy.request(any(), any(), any(), any(), any(), any(), any(), any(), anyDouble()))
        .thenReturn(JsonUtils.obj());
    var writePolicy = mock(TestWritePolicy.class);
    return List.of(
        new DocusignAdapter(credentials, http, new TokenCache()),
        new GoogleAdapter(
            mock(GoogleTokenService.class),
            http,
            credentials,
            new GatewaySettings(new MockEnvironment())),
        new ShopifyAdapter(credentials, http, writePolicy),
        new WecomAdapter(credentials, http, new TokenCache(), registry),
        new NasAdapter(credentials, http),
        new QuickcepAdapter(credentials, http),
        new LingxingAdapter(credentials, http, proxy, writePolicy, new TokenCache()),
        new WinitAdapter(credentials, http, writePolicy),
        new MabangAdapter(credentials, proxy),
        new CustomerSourcesAdapter(credentials, http));
  }

  /**
   * Every operation the contract declares must reach real handler code. Credentials resolve to a
   * placeholder on purpose: the assertion is about routing, not about the upstream answer, so an
   * 未知操作 / 未实现 branch fails the build while a rejected fixture credential does not.
   */
  @Test
  void everyDeclaredOperationReachesJavaHandlerCode() throws Exception {
    var registry = registry();
    var credentials = mock(CredentialService.class);
    when(credentials.resolve(anyString(), anyString())).thenReturn("fixture-value");
    when(credentials.required(anyString(), anyString())).thenReturn("fixture-value");
    var http = mock(UpstreamHttp.class);
    when(http.json(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenReturn(
            JsonUtils.obj(
                "access_token",
                "fixture-access",
                "expires_in",
                7200,
                "errcode",
                0,
                "code",
                "0",
                "data",
                JsonUtils.obj("access_token", "fixture-access", "expires_in", 7200)));
    when(http.object(anyString(), anyString(), anyMap(), any()))
        .thenReturn(
            JsonUtils.obj("access_token", "fixture-access", "expires_in", 7200, "errcode", 0));
    when(http.form(anyString(), anyMap(), anyMap()))
        .thenReturn(JsonUtils.obj("access_token", "fixture-access", "expires_in", 7200));
    when(http.raw(anyString(), anyString(), anyMap(), any(), anyDouble(), any()))
        .thenReturn("{}".getBytes(StandardCharsets.UTF_8));
    var proxy = mock(ErpProxy.class);
    when(proxy.request(any(), any(), any(), any(), any(), any(), any(), any(), anyDouble()))
        .thenReturn(JsonUtils.obj());
    var dispatcher =
        new AdapterDispatcher(
            productionAdapters(credentials, http, registry),
            new MockAdapter(),
            new GatewaySettings(new MockEnvironment().withProperty("MOCK_ALL", "false")),
            registry,
            new WecomApprovalService(
                new WecomApprovalClient(
                    credentials, http, new TokenCache(), mock(QuotaService.class)),
                mock(GatewayStore.class),
                new GatewaySettings(new MockEnvironment()),
                new CursorCodec(new GatewaySettings(new MockEnvironment())),
                credentials),
            new BusinessWorkflows(
                productionAdapters(credentials, http, registry),
                new MockAdapter(),
                registry,
                new CursorCodec(new GatewaySettings(new MockEnvironment())),
                mock(QuotaService.class),
                new GatewaySettings(new MockEnvironment())));

    var unreachable = new ArrayList<String>();
    int completed = 0;
    for (Map<String, Object> fixture : OperationContractTest.cases().toList()) {
      var op = registry.get(JsonUtils.str(fixture, "operation"));
      try {
        dispatcher.invoke(
            op, registry.validate(op, JsonUtils.map(fixture.get("params"))), "fixture");
        completed++;
      } catch (GatewayException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("未知") || message.contains("未实现") || message.contains("适配器未加载"))
          unreachable.add(op.key() + ": " + message);
      } catch (RuntimeException e) {
        String message = e + " " + e.getMessage();
        if (message.contains("未知") || message.contains("未实现"))
          unreachable.add(op.key() + ": " + message);
      }
    }
    assertEquals(List.of(), unreachable, "契约已登记但 Java 侧未实现: " + unreachable);
    // This sweep must actually select native adapters. Placeholder RSA keys and proxy addresses
    // intentionally fail configuration checks; the fixture-specific tests above verify wire
    // formats.
    assertFalse(dispatcher.mock(registry.get("wecom.users.get")));
    assertTrue(completed >= 20, "仅 " + completed + " 个原生处理器完成占位响应测试");
  }
}

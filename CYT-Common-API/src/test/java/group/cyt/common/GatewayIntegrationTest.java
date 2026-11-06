package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;

import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.auth.service.AuthenticationService;
import group.cyt.common.module.gateway.service.CallLogService;
import group.cyt.common.module.registry.controller.RegistryController;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "TEST_MYSQL_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayIntegrationTest {
  private static final String DATABASE =
      "cyt_common_check_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  private static final String BASE = System.getenv("TEST_MYSQL_URL");
  private static String jdbcUrl;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) throws Exception {
    if (!BASE.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/?"))
      throw new IllegalStateException(
          "Tests require an explicit loopback MySQL server URL without database");
    try (Connection c =
            DriverManager.getConnection(
                BASE + (BASE.endsWith("/") ? "" : "/") + "mysql",
                System.getenv().getOrDefault("TEST_MYSQL_USERNAME", "root"),
                System.getenv().getOrDefault("TEST_MYSQL_PASSWORD", ""));
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE `" + DATABASE + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    }
    jdbcUrl =
        BASE
            + (BASE.endsWith("/") ? "" : "/")
            + DATABASE
            + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    p.add("spring.datasource.url", () -> jdbcUrl);
    p.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("TEST_MYSQL_USERNAME", "root"));
    p.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("TEST_MYSQL_PASSWORD", ""));
    p.add("ENV", () -> "local");
    p.add("MOCK_ALL", () -> "true");
    p.add("GATEWAY_DEV_MODE", () -> "false");
    p.add("ADMIN_JWT_SECRET", () -> "fixture-admin-jwt-secret-with-at-least-32-characters");
    p.add("ADMIN_AUTH_MODES", () -> "admin_jwt");
    p.add("ADMIN_USERNAME", () -> "fixture-admin");
    p.add("ADMIN_PASSWORD", () -> "fixture-password");
    p.add("CREDENTIAL_MASTER_KEY", () -> "fixture-master-key");
    p.add("CUSTOM_API_ALLOW_LOOPBACK", () -> "true");
    p.add("WECOM_EVENT_INITIAL_DELAY_MS", () -> "3600000");
  }

  @Autowired TestRestTemplate http;
  @Autowired GatewayStore store;
  @Autowired AuthenticationService authentication;
  @Autowired CallLogService logs;
  @Autowired OperationRegistry registry;
  @Autowired RegistryController documents;
  @Autowired group.cyt.common.module.credential.service.CredentialService credentials;
  @Autowired group.cyt.common.core.config.GatewaySettings settings;
  @Autowired group.cyt.common.module.adapter.wecom.approval.WecomApprovalClient approvalClient;
  @LocalServerPort int port;

  private String admin() {
    return JsonUtils.str(authentication.session("fixture-admin"), "token");
  }

  private ResponseEntity<Map> request(HttpMethod method, String path, Object body, String token) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) h.setBearerAuth(token);
    return http.exchange(path, method, new HttpEntity<>(body, h), Map.class);
  }

  @Test
  @Order(20)
  void selectedBatchPreviewsEffectiveConflictsPreservesMetadataAndRejectsStaleChanges() {
    String system = "test_bulk_grants";
    createSystem(system, false);
    String token = admin();
    var metadata =
        JsonUtils.obj(
            "system",
            system,
            "operation_pattern",
            "gmail.profile.get",
            "effect",
            "deny",
            "quota",
            "31/min",
            "expires_at",
            "2099-01-01T00:00:00Z",
            "note",
            "保留配额与有效期");
    assertEquals(
        200,
        request(HttpMethod.POST, "/admin/api/grants", metadata, token).getStatusCode().value());
    assertEquals(
        200,
        request(
                HttpMethod.POST,
                "/admin/api/grants",
                JsonUtils.obj("system", system, "operation_pattern", "gdrive.*", "effect", "deny"),
                token)
            .getStatusCode()
            .value());
    var body =
        JsonUtils.obj(
            "system",
            system,
            "effect",
            "allow",
            "operations",
            List.of("gmail.profile.get", "gdrive.files.list", "wecom.approvals.submit"));
    var response = request(HttpMethod.POST, "/admin/api/grants/batch/preview", body, token);
    assertEquals(200, response.getStatusCode().value());
    var plan = response.getBody();
    assertEquals(2, ((Number) plan.get("changed")).intValue());
    assertEquals(1, ((Number) plan.get("blocked_count")).intValue());
    assertEquals(1, ((Number) plan.get("sensitive_count")).intValue());
    var conflict =
        JsonUtils.list(plan.get("items")).stream()
            .map(JsonUtils::map)
            .filter(i -> JsonUtils.bool(i.get("blocked")))
            .findFirst()
            .orElseThrow();
    assertEquals(1, JsonUtils.list(conflict.get("blocking_rules")).size());
    assertNotNull(
        JsonUtils.map(JsonUtils.list(conflict.get("blocking_rules")).getFirst()).get("id"));
    assertEquals(2, store.count("SELECT COUNT(*) FROM gateway_grant WHERE `system`=?", system));
    body.put("revision", plan.get("revision"));
    assertEquals(
        422,
        request(HttpMethod.POST, "/admin/api/grants/batch", body, token).getStatusCode().value());
    body.put("skip_blocked", true);
    assertEquals(
        422,
        request(HttpMethod.POST, "/admin/api/grants/batch", body, token).getStatusCode().value());
    body.put("confirm_sensitive", true);
    assertEquals(
        200,
        request(HttpMethod.POST, "/admin/api/grants/batch", body, token).getStatusCode().value());
    var saved =
        store.one(
            "SELECT * FROM gateway_grant WHERE `system`=? AND operation_pattern='gmail.profile.get'",
            system);
    assertEquals("allow", saved.get("effect"));
    assertEquals("31/min", saved.get("quota"));
    assertEquals("保留配额与有效期", saved.get("note"));
    assertTrue(saved.get("expires_at").toString().startsWith("2099-01-01"));
    assertEquals(
        0,
        store.count(
            "SELECT COUNT(*) FROM gateway_grant WHERE `system`=? AND operation_pattern='gdrive.files.list'",
            system));
    assertEquals(
        409,
        request(HttpMethod.POST, "/admin/api/grants/batch", body, token).getStatusCode().value());
    var expired =
        JsonUtils.obj(
            "system",
            system,
            "operation_pattern",
            "gmail.messages.get",
            "effect",
            "allow",
            "expires_at",
            "2001-01-01T00:00:00Z");
    request(HttpMethod.POST, "/admin/api/grants", expired, token);
    var blocked =
        request(
                HttpMethod.POST,
                "/admin/api/grants/batch/preview",
                JsonUtils.obj(
                    "system",
                    system,
                    "effect",
                    "allow",
                    "operations",
                    List.of("gmail.messages.get")),
                token)
            .getBody();
    assertEquals(1, ((Number) blocked.get("blocked_count")).intValue());
    var effective =
        request(HttpMethod.GET, "/admin/api/effective/" + system, null, token).getBody();
    assertTrue(
        JsonUtils.list(effective.get("operations")).stream()
            .map(JsonUtils::map)
            .anyMatch(
                r ->
                    "gdrive.files.list".equals(r.get("operation"))
                        && JsonUtils.str(r, "reason").contains("gdrive.*")));
  }

  @Test
  @Order(21)
  void batchRollsBackAllRulesAndAuditsIfALaterWriteFails() {
    String system = "test_bulk_rollback";
    createSystem(system, false);
    store.insert(
        "gateway_grant",
        JsonUtils.obj(
            "system",
            system,
            "operation_pattern",
            "gmail.profile.get",
            "effect",
            "deny",
            "quota",
            "malformed_legacy_quota"));
    String token = admin();
    var body =
        JsonUtils.obj(
            "system",
            system,
            "effect",
            "allow",
            "operations",
            List.of("gdrive.files.list", "gmail.profile.get"));
    var plan = request(HttpMethod.POST, "/admin/api/grants/batch/preview", body, token).getBody();
    body.put("revision", plan.get("revision"));
    assertEquals(
        422,
        request(HttpMethod.POST, "/admin/api/grants/batch", body, token).getStatusCode().value());
    assertEquals(1, store.count("SELECT COUNT(*) FROM gateway_grant WHERE `system`=?", system));
    assertEquals(
        0, store.count("SELECT COUNT(*) FROM gateway_grant_audit WHERE `system`=?", system));
  }

  @Test
  @Order(22)
  void callerDocumentContainsOnlyCurrentlyAllowedOperationsAndReferencedSchemas() throws Exception {
    String system = "test_consumer_docs";
    String callerKey = createSystem(system, true);
    store.insert(
        "gateway_grant",
        JsonUtils.obj("system", system, "operation_pattern", "gmail.*", "effect", "deny"));
    String token = admin();
    var response = request(HttpMethod.GET, "/admin/api/consumer-openapi/" + system, null, token);
    assertEquals(200, response.getStatusCode().value());
    var spec = response.getBody();
    var paths = JsonUtils.map(spec.get("paths"));
    assertTrue(paths.containsKey("/integrations/gdrive/files/list"));
    assertFalse(paths.keySet().stream().anyMatch(p -> p.contains("/gmail/")));
    assertFalse(paths.containsKey("/integrations/wecom/approvals/submit"));
    var schemas = JsonUtils.map(JsonUtils.map(spec.get("components")).get("schemas"));
    assertFalse(schemas.containsKey("gmail_messages_get"));
    assertFalse(schemas.containsKey("wecom_approvals_submit"));
    assertEquals(system, spec.get("x-cyt-consumer"));
    assertEquals(
        "gdrive.files.list",
        JsonUtils.map(JsonUtils.map(paths.get("/integrations/gdrive/files/list")).get("post"))
            .get("x-cyt-operation"));
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    var bundle =
        http.exchange(
            "/admin/api/consumer-package/" + system,
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            byte[].class);
    assertEquals(200, bundle.getStatusCode().value());
    var names = new HashSet<String>();
    try (var zip =
        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bundle.getBody()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        names.add(entry.getName());
        String content = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(content.contains(callerKey));
        if (entry.getName().equals("START_HERE.html")) {
          assertTrue(content.contains("gdrive.files.list"));
          assertFalse(content.contains("/integrations/gmail/"));
          assertFalse(content.contains("wecom.approvals.submit"));
          assertFalse(content.contains("__CYT_OPENAPI__"));
        }
      }
    }
    assertEquals(
        Set.of(
            "START_HERE.html",
            "README.md",
            "openapi.json",
            "sdk/python/cyt_gateway.py",
            "sdk/typescript/index.ts"),
        names);
    request(HttpMethod.POST, "/admin/api/systems/" + system + "/toggle", Map.of(), token);
    assertEquals(
        403,
        request(HttpMethod.GET, "/admin/api/consumer-openapi/" + system, null, token)
            .getStatusCode()
            .value());
  }

  @Test
  @Order(23)
  void ownServicesRetainTheirSourceKindAndRejectUnknownKinds() {
    String token = admin();
    var body =
        JsonUtils.obj(
            "key",
            "internal_fixture",
            "name",
            "内部订单服务",
            "service_kind",
            "internal",
            "base_url",
            "http://127.0.0.1:19099",
            "auth_type",
            "none",
            "enabled",
            false);
    var saved = request(HttpMethod.POST, "/admin/api/modules", body, token);
    assertEquals(200, saved.getStatusCode().value());
    assertEquals(
        "internal",
        store
            .one("SELECT service_kind FROM gateway_module WHERE `key`='internal_fixture'")
            .get("service_kind"));
    var api =
        request(
            HttpMethod.POST,
            "/admin/api/custom-apis",
            JsonUtils.obj(
                "key",
                "internal_fixture.orders.get",
                "module_key",
                "internal_fixture",
                "name",
                "内部订单查询",
                "method",
                "GET",
                "path",
                "/orders",
                "auth_type",
                "inherit",
                "enabled",
                false),
            token);
    assertEquals(200, api.getStatusCode().value());
    var inherited =
        store.one(
            "SELECT base_url,headers_json,pagination_json FROM gateway_custom_api WHERE `key`='internal_fixture.orders.get'");
    assertEquals("", inherited.get("base_url"));
    assertEquals("{}", inherited.get("headers_json"));
    body.put("key", "invalid_kind_fixture");
    body.put("service_kind", "anything");
    assertEquals(
        422, request(HttpMethod.POST, "/admin/api/modules", body, token).getStatusCode().value());
    assertEquals(
        0, store.count("SELECT COUNT(*) FROM gateway_module WHERE `key`='invalid_kind_fixture'"));
  }

  @Test
  @Order(24)
  void documentationDirectionsStaySeparateAndResponseContractsPersist() {
    String token = admin(), system = "doc_direction_fixture";
    createSystem(system, true);
    request(
        HttpMethod.POST,
        "/admin/api/modules",
        JsonUtils.obj(
            "key",
            "doc_oa_fixture",
            "name",
            "OA 契约演练",
            "service_kind",
            "internal",
            "base_url",
            "http://127.0.0.1:19099",
            "auth_type",
            "none",
            "enabled",
            true),
        token);
    var schema =
        JsonUtils.obj(
            "type",
            "object",
            "properties",
            JsonUtils.obj(
                "employee_id", JsonUtils.obj("type", "string", "description", "OA 成员唯一标识")));
    var body =
        JsonUtils.obj(
            "key",
            "doc_oa_fixture.organization.roster",
            "module_key",
            "doc_oa_fixture",
            "name",
            "花名册契约",
            "method",
            "GET",
            "path",
            "/roster",
            "auth_type",
            "inherit",
            "enabled",
            true,
            "response_schema_json",
            JsonUtils.write(schema));
    assertEquals(
        200,
        request(HttpMethod.POST, "/admin/api/custom-apis", body, token).getStatusCode().value());
    var internal =
        request(
                HttpMethod.GET,
                "/admin/api/consumer-openapi/" + system + "?direction=internal",
                null,
                token)
            .getBody();
    var paths = JsonUtils.map(internal.get("paths"));
    assertEquals(Set.of("/integrations/doc-oa-fixture/organization/roster"), paths.keySet());
    assertTrue(JsonUtils.write(internal).contains("OA 成员唯一标识"));
    assertEquals("internal", internal.get("x-cyt-direction"));
    var third =
        request(
                HttpMethod.GET,
                "/admin/api/consumer-openapi/" + system + "?direction=third_party",
                null,
                token)
            .getBody();
    assertFalse(
        JsonUtils.map(third.get("paths"))
            .containsKey("/integrations/doc-oa-fixture/organization/roster"));
    assertTrue(JsonUtils.map(third.get("paths")).containsKey("/integrations/gdrive/files/list"));
    assertEquals(
        422,
        request(
                HttpMethod.GET,
                "/admin/api/consumer-openapi/" + system + "?direction=unknown",
                null,
                token)
            .getStatusCode()
            .value());
    body.put("key", "doc_oa_fixture.organization.invalid");
    body.put("response_schema_json", "{\"$ref\":\"https://untrusted.example/schema\"}");
    assertEquals(
        422,
        request(HttpMethod.POST, "/admin/api/custom-apis", body, token).getStatusCode().value());
  }

  private String createSystem(String system, boolean grantAll) {
    store.insert(
        "gateway_system",
        JsonUtils.obj("key", system, "display_name", system, "description", "test"));
    if (grantAll)
      store.insert(
          "gateway_grant",
          JsonUtils.obj(
              "system",
              system,
              "operation_pattern",
              "*",
              "effect",
              "allow",
              "quota",
              "100000/min"));
    String secret = JsonUtils.random(24), prefix = UUID.randomUUID().toString().substring(0, 8);
    store.insert(
        "gateway_system_apikey",
        JsonUtils.obj("system", system, "prefix", prefix, "secret_hash", JsonUtils.sha(secret)));
    return "cyt_" + prefix + "_" + secret;
  }

  @Test
  @Order(1)
  void flywayCreatesBusinessAndApprovalTablesAndIndexes() {
    assertEquals(
        17,
        store.count(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name LIKE 'gateway_%'",
            DATABASE));
    assertTrue(
        store.count(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=? AND index_name='ix_calls_system_time'",
                DATABASE)
            > 0);
  }

  @Test
  void requiresAdminSessionAndCallerCredentials() {
    assertEquals(
        401, request(HttpMethod.GET, "/admin/api/systems", null, null).getStatusCode().value());
    assertEquals(
        401,
        request(HttpMethod.POST, "/integrations/gmail/profile/get", Map.of(), null)
            .getStatusCode()
            .value());
    assertEquals(
        401, request(HttpMethod.GET, "/admin/api/meta", null, "bad-jwt").getStatusCode().value());
    assertEquals(
        200,
        request(
                HttpMethod.POST,
                "/admin/api/auth/login",
                JsonUtils.obj("username", "fixture-admin", "password", "fixture-password"),
                null)
            .getStatusCode()
            .value());
  }

  @Test
  void apiKeysDefaultDenyAndRevocation() {
    String key = createSystem("test_deny", false);
    assertEquals(
        403,
        request(HttpMethod.POST, "/integrations/gmail/profile/get", Map.of(), key)
            .getStatusCode()
            .value());
    store.insert(
        "gateway_grant",
        JsonUtils.obj("system", "test_deny", "operation_pattern", "gmail.profile.get"));
    assertEquals(
        200,
        request(HttpMethod.POST, "/integrations/gmail/profile/get", Map.of(), key)
            .getStatusCode()
            .value());
    store.update("UPDATE gateway_system_apikey SET revoked=true WHERE `system`='test_deny'");
    assertEquals(
        401,
        request(HttpMethod.POST, "/integrations/gmail/profile/get", Map.of(), key)
            .getStatusCode()
            .value());
  }

  @Test
  void denyOverridesWildcardAndSensitiveRequiresExactGrant() {
    String key = createSystem("test_grants", true);
    assertEquals(
        403,
        request(
                HttpMethod.POST,
                "/integrations/gcs/objects/delete",
                JsonUtils.obj("path", "fixture.txt"),
                key)
            .getStatusCode()
            .value());
    store.insert(
        "gateway_grant",
        JsonUtils.obj(
            "system", "test_grants", "operation_pattern", "gcs.objects.delete", "effect", "allow"));
    assertEquals(
        200,
        request(
                HttpMethod.POST,
                "/integrations/gcs/objects/delete",
                JsonUtils.obj("path", "fixture.txt"),
                key)
            .getStatusCode()
            .value());
    store.insert(
        "gateway_grant",
        JsonUtils.obj("system", "test_grants", "operation_pattern", "gmail.*", "effect", "deny"));
    assertEquals(
        403,
        request(HttpMethod.POST, "/integrations/gmail/profile/get", Map.of(), key)
            .getStatusCode()
            .value());
  }

  @Test
  void expiredGrantsAndDisabledSystemsNeverCallUpstream() {
    String key = createSystem("test_expiry", false);
    store.insert(
        "gateway_grant",
        JsonUtils.obj(
            "system",
            "test_expiry",
            "operation_pattern",
            "gmail.*",
            "expires_at",
            Timestamp.from(java.time.Instant.now().minusSeconds(60))));
    assertEquals(
        403,
        request(HttpMethod.POST, "/integrations/gmail/profile/get", Map.of(), key)
            .getStatusCode()
            .value());
    store.update("UPDATE gateway_system SET enabled=false WHERE `key`='test_expiry'");
    assertEquals(
        403,
        request(HttpMethod.POST, "/integrations/gmail/profile/get", Map.of(), key)
            .getStatusCode()
            .value());
  }

  @Test
  void unknownOperationsAndExtraParametersAreRejected() {
    String key = createSystem("test_params", true);
    assertEquals(
        404,
        request(HttpMethod.POST, "/integrations/arbitrary/path/call", Map.of(), key)
            .getStatusCode()
            .value());
    assertEquals(
        422,
        request(
                HttpMethod.POST,
                "/integrations/gmail/profile/get",
                JsonUtils.obj("unexpected", "value"),
                key)
            .getStatusCode()
            .value());
  }

  @Test
  void idempotencyReplaysAndRejectsChangedPayload() {
    String key = createSystem("test_idem", true);
    Map<String, Object> body =
        JsonUtils.obj(
            "template_id",
            "fixture-template",
            "template_roles",
            List.of(
                JsonUtils.obj(
                    "role_name", "Signer", "name", "Fixture", "email", "fixture@example.invalid")));
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(key);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "fixture-key");
    var first =
        http.exchange(
            "/integrations/docusign/envelopes/create",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class);
    var second =
        http.exchange(
            "/integrations/docusign/envelopes/create",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class);
    assertEquals(200, first.getStatusCode().value());
    assertEquals(first.getBody().get("data"), second.getBody().get("data"));
    assertEquals(true, JsonUtils.map(second.getBody().get("meta")).get("idempotent_replay"));
    body.put("email_subject", "changed");
    assertEquals(
        409,
        http.exchange(
                "/integrations/docusign/envelopes/create",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class)
            .getStatusCode()
            .value());
  }

  @Test
  void concurrentIdempotencyCreatesOnlyOneClaim() throws Exception {
    String key = createSystem("test_concurrency", true);
    var body =
        JsonUtils.obj(
            "template_id",
            "fixture-template",
            "template_roles",
            List.of(
                JsonUtils.obj(
                    "role_name", "Signer", "name", "Fixture", "email", "fixture@example.invalid")));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Integer>> responses = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        responses.add(
            executor.submit(
                () -> {
                  HttpHeaders h = new HttpHeaders();
                  h.setBearerAuth(key);
                  h.setContentType(MediaType.APPLICATION_JSON);
                  h.set("Idempotency-Key", "same-concurrent-key");
                  return http.exchange(
                          "/integrations/docusign/envelopes/create",
                          HttpMethod.POST,
                          new HttpEntity<>(body, h),
                          Map.class)
                      .getStatusCode()
                      .value();
                }));
      for (var r : responses) assertTrue(Set.of(200, 409).contains(r.get()));
    }
    assertEquals(
        1,
        store.count("SELECT COUNT(*) FROM gateway_idempotency WHERE `system`='test_concurrency'"));
  }

  @Test
  void all82PublicRoutesExecuteInExplicitMockMode() throws Exception {
    String system = "test_contract", key = createSystem(system, true);
    for (var op : registry.operations())
      if (op.sensitive())
        store.insert(
            "gateway_grant",
            JsonUtils.obj("system", system, "operation_pattern", op.key(), "quota", "100000/min"));
    List<Map<String, Object>> cases;
    try (var input = getClass().getResourceAsStream("/operation-cases.json")) {
      cases =
          JsonUtils.MAPPER.readValue(
              input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
    String envelope = null;
    for (var item : cases) {
      String operation = JsonUtils.str(item, "operation");
      var p = new LinkedHashMap<>(JsonUtils.map(item.get("params")));
      if (p.containsKey("envelope_id")) p.put("envelope_id", envelope);
      var response = request(HttpMethod.POST, OperationRegistry.path(operation), p, key);
      assertEquals(200, response.getStatusCode().value(), operation + ": " + response.getBody());
      assertEquals(true, JsonUtils.map(response.getBody().get("meta")).get("mock"), operation);
      if (operation.equals("docusign.envelopes.create"))
        envelope = JsonUtils.str(JsonUtils.map(response.getBody().get("data")), "envelope_id");
    }
  }

  @Test
  void secretsAreEncryptedAndNeverReturned() {
    String token = admin();
    var response =
        request(
            HttpMethod.PUT,
            "/admin/api/environments/local/secrets",
            JsonUtils.obj(
                "values", JsonUtils.obj("CUSTOM_FIXTURE_TOKEN", "fixture-private-secret")),
            token);
    assertEquals(200, response.getStatusCode().value());
    String ciphertext =
        JsonUtils.str(
            store.one("SELECT ciphertext FROM gateway_secret WHERE name='CUSTOM_FIXTURE_TOKEN'"),
            "ciphertext");
    assertFalse(ciphertext.contains("fixture-private-secret"));
    assertTrue(ciphertext.startsWith("gAAAA"));
    var listed = request(HttpMethod.GET, "/admin/api/environments/local/secrets", null, token);
    assertFalse(JsonUtils.write(listed.getBody()).contains("fixture-private-secret"));
  }

  @Test
  void simulateUsesTheSameAuthorizationPipeline() {
    createSystem("test_simulate", false);
    assertEquals(
        403,
        request(
                HttpMethod.POST,
                "/admin/api/simulate",
                JsonUtils.obj(
                    "system",
                    "test_simulate",
                    "operation",
                    "gmail.profile.get",
                    "params",
                    Map.of()),
                admin())
            .getStatusCode()
            .value());
  }

  @Test
  void monitorAndRegistryEndpointsReturnFrontendContracts() {
    for (String path :
        List.of(
            "meta",
            "platforms",
            "overview",
            "calls",
            "audit",
            "registry",
            "systems",
            "grants",
            "environments",
            "modules",
            "custom-apis",
            "callbacks",
            "credentials",
            "facets",
            "docs",
            "openapi.json")) {
      var response = request(HttpMethod.GET, "/admin/api/" + path, null, admin());
      assertEquals(200, response.getStatusCode().value(), path + ": " + response.getBody());
    }
  }

  @Test
  void customHttpUsesTypedTemplatesAndLocalOnlyAllowlist() {
    String token = admin();
    var module =
        request(
            HttpMethod.POST,
            "/admin/api/modules",
            JsonUtils.obj(
                "key",
                "fixturehttp",
                "name",
                "Fixture HTTP",
                "base_url",
                "http://127.0.0.1:" + port,
                "auth_type",
                "none"),
            token);
    assertEquals(200, module.getStatusCode().value(), JsonUtils.write(module.getBody()));
    var custom =
        request(
            HttpMethod.POST,
            "/admin/api/custom-apis",
            JsonUtils.obj(
                "key",
                "fixturehttp.echo.create",
                "module_key",
                "fixturehttp",
                "base_url",
                "",
                "method",
                "POST",
                "path",
                "/internal/echo",
                "auth_type",
                "inherit",
                "params_schema_json",
                "[{\"name\":\"count\",\"type\":\"integer\",\"required\":true}]",
                "body_template",
                "{\"count\":\"{{params.count}}\"}"),
            token);
    assertEquals(200, custom.getStatusCode().value(), JsonUtils.write(custom.getBody()));
    createSystem("test_custom_denied", false);
    String testPath = "/admin/api/custom-apis/" + custom.getBody().get("id") + "/test";
    assertEquals(
        403,
        request(
                HttpMethod.POST,
                testPath,
                JsonUtils.obj("system", "test_custom_denied", "params", JsonUtils.obj("count", 7)),
                token)
            .getStatusCode()
            .value());
    String key = createSystem("test_custom", true);
    assertEquals(
        200,
        request(
                HttpMethod.POST,
                testPath,
                JsonUtils.obj("system", "test_custom", "params", JsonUtils.obj("count", 7)),
                token)
            .getStatusCode()
            .value());
    var result =
        request(
            HttpMethod.POST,
            "/integrations/fixturehttp/echo/create",
            JsonUtils.obj("count", 7),
            key);
    assertEquals(200, result.getStatusCode().value(), JsonUtils.write(result.getBody()));
    assertEquals(7, JsonUtils.at(result.getBody(), "data.data.body.count"));
  }

  @Test
  void openApiReferencesResolveIntoComponents() {
    var spec = JsonUtils.map(documents.openapi());
    String encoded = JsonUtils.write(spec);
    assertFalse(encoded.contains("#/$defs/"));
    assertTrue(JsonUtils.map(spec.get("paths")).size() >= 82);
  }

  @Test
  void customPagePaginationUsesExistingPythonConfigurationKeys() throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/items",
        exchange -> {
          Map<String, String> query = new HashMap<>();
          for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(parts[0], parts.length > 1 ? parts[1] : "");
          }
          int page = Integer.parseInt(query.getOrDefault("page", "1")),
              size = Integer.parseInt(query.getOrDefault("page_size", "2"));
          List<Object> items = new ArrayList<>();
          for (int i = (page - 1) * size; i < Math.min(page * size, 5); i++)
            items.add(Map.of("id", i));
          byte[] bytes =
              JsonUtils.write(JsonUtils.obj("data", JsonUtils.obj("items", items)))
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      String token = admin();
      assertEquals(
          200,
          request(
                  HttpMethod.POST,
                  "/admin/api/modules",
                  JsonUtils.obj(
                      "key",
                      "fixturepage",
                      "base_url",
                      "http://127.0.0.1:" + server.getAddress().getPort(),
                      "auth_type",
                      "none"),
                  token)
              .getStatusCode()
              .value());
      assertEquals(
          200,
          request(
                  HttpMethod.POST,
                  "/admin/api/custom-apis",
                  JsonUtils.obj(
                      "key",
                      "fixturepage.items.list",
                      "module_key",
                      "fixturepage",
                      "base_url",
                      "",
                      "method",
                      "GET",
                      "path",
                      "/items",
                      "auth_type",
                      "inherit",
                      "pagination_json",
                      "{\"mode\":\"page\",\"param\":\"page\",\"size_param\":\"page_size\",\"size\":2,\"start\":1,\"max_pages\":5,\"items_path\":\"data.items\"}"),
                  token)
              .getStatusCode()
              .value());
      String key = createSystem("test_pagination", true);
      var result = request(HttpMethod.POST, "/integrations/fixturepage/items/list", Map.of(), key);
      assertEquals(200, result.getStatusCode().value(), JsonUtils.write(result.getBody()));
      assertEquals(5, JsonUtils.at(result.getBody(), "data.data.count"));
      assertEquals(3, JsonUtils.at(result.getBody(), "data.data.pages"));
      assertEquals(false, JsonUtils.at(result.getBody(), "data.data.truncated"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void webhookVerifiesRawBytesAndSignsOneForwardingAttempt() throws Exception {
    var delivered = new java.util.concurrent.atomic.AtomicInteger();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/callback",
        exchange -> {
          byte[] raw = exchange.getRequestBody().readAllBytes();
          String expected =
              HexFormat.of()
                  .formatHex(
                      JsonUtils.hmac(
                          "fixture-forward-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                          raw));
          if (expected.equals(exchange.getRequestHeaders().getFirst("X-Cyt-Signature")))
            delivered.incrementAndGet();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    try {
      String token = admin();
      createSystem("test_webhook", false);
      assertEquals(
          200,
          request(
                  HttpMethod.PUT,
                  "/admin/api/environments/local/secrets",
                  JsonUtils.obj(
                      "values",
                      JsonUtils.obj(
                          "DOCUSIGN_HMAC_KEY",
                          "fixture-webhook-key",
                          "CUSTOM_FORWARD_KEY",
                          "fixture-forward-key")),
                  token)
              .getStatusCode()
              .value());
      assertEquals(
          200,
          request(
                  HttpMethod.POST,
                  "/admin/api/callbacks",
                  JsonUtils.obj(
                      "system",
                      "test_webhook",
                      "platform",
                      "docusign",
                      "url",
                      "http://127.0.0.1:" + server.getAddress().getPort() + "/callback",
                      "secret_ref",
                      "CUSTOM_FORWARD_KEY"),
                  token)
              .getStatusCode()
              .value());
      byte[] raw =
          "{ \"event\":\"envelope-completed\", \"data\":{\"envelopeId\":\"fixture-envelope\",\"status\":\"completed\"} }"
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set("X-DocuSign-Signature-1", "invalid-signature");
      String path = "/integrations/docusign/webhooks/test_webhook/events";
      assertEquals(
          401,
          http.exchange(path, HttpMethod.POST, new HttpEntity<>(raw, headers), Map.class)
              .getStatusCode()
              .value());
      assertEquals(0, delivered.get());
      headers.set(
          "X-DocuSign-Signature-1",
          Base64.getEncoder()
              .encodeToString(
                  JsonUtils.hmac(
                      "fixture-webhook-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      raw)));
      var response =
          http.exchange(path, HttpMethod.POST, new HttpEntity<>(raw, headers), Map.class);
      assertEquals(200, response.getStatusCode().value(), JsonUtils.write(response.getBody()));
      assertEquals(true, JsonUtils.at(response.getBody(), "forward.delivered"));
      assertEquals(1, delivered.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void logTimestampsUseUtcAndHttpErrorsKeepTheirStatus() {
    String key = createSystem("test_utc", true);
    assertEquals(
        200,
        request(HttpMethod.POST, "/integrations/gmail/profile/get", Map.of(), key)
            .getStatusCode()
            .value());
    logs.flush();
    var row =
        store.one(
            "SELECT called_at FROM gateway_call_log WHERE `system`='test_utc' ORDER BY id DESC LIMIT 1");
    assertTrue(
        Math.abs(
                java.time.Duration.between(
                        java.time.Instant.parse(row.get("called_at").toString()),
                        java.time.Instant.now())
                    .getSeconds())
            < 30);
    assertEquals(
        404,
        request(HttpMethod.GET, "/admin/api/not-a-real-endpoint", null, admin())
            .getStatusCode()
            .value());
    assertEquals(
        422,
        request(HttpMethod.GET, "/admin/api/calls?page=invalid", null, admin())
            .getStatusCode()
            .value());
  }

  @Test
  void approvalFacadeUsesAliasesExactAuthorizationAndDurableIdempotency() {
    String system = "test_approval", key = createSystem(system, true);
    var bound =
        request(
            HttpMethod.POST,
            "/integrations/wecom/approval-templates/bind",
            JsonUtils.obj(
                "template_id",
                "demo-expense",
                "alias",
                "expense",
                "field_aliases",
                JsonUtils.obj("reason", "Text-01", "amount", "Money-02")),
            key);
    assertEquals(200, bound.getStatusCode().value(), bound.getBody().toString());
    var invalid =
        request(
            HttpMethod.POST,
            "/integrations/wecom/approvals/prepare",
            JsonUtils.obj(
                "template",
                "expense",
                "applicant",
                "fixture-user",
                "fields",
                Map.of("reason", "采购")),
            key);
    assertEquals(false, JsonUtils.map(invalid.getBody().get("data")).get("valid"));
    assertEquals(
        0, store.count("SELECT COUNT(*) FROM gateway_wecom_submission WHERE `system`=?", system));
    var body =
        JsonUtils.obj(
            "template",
            "expense",
            "applicant",
            "fixture-user",
            "fields",
            JsonUtils.obj("reason", "采购", "amount", "12.30"),
            "request_id",
            "approval-request-001");
    assertEquals(
        403,
        request(HttpMethod.POST, "/integrations/wecom/approvals/submit", body, key)
            .getStatusCode()
            .value());
    store.insert(
        "gateway_grant",
        JsonUtils.obj(
            "system",
            system,
            "operation_pattern",
            "wecom.approvals.submit",
            "effect",
            "allow",
            "quota",
            "100000/min"));
    var first = request(HttpMethod.POST, "/integrations/wecom/approvals/submit", body, key);
    assertEquals(200, first.getStatusCode().value(), first.getBody().toString());
    String no = JsonUtils.str(JsonUtils.map(first.getBody().get("data")), "approval_no");
    assertTrue(no.startsWith("MOCK-"));
    var replay = request(HttpMethod.POST, "/integrations/wecom/approvals/submit", body, key);
    assertEquals(200, replay.getStatusCode().value());
    assertEquals(true, JsonUtils.map(replay.getBody().get("meta")).get("idempotent_replay"));
    assertEquals(
        1, store.count("SELECT COUNT(*) FROM gateway_wecom_submission WHERE `system`=?", system));
    var different = new LinkedHashMap<>(body);
    different.put("fields", JsonUtils.obj("reason", "不同的申请", "amount", "50"));
    assertEquals(
        409,
        request(HttpMethod.POST, "/integrations/wecom/approvals/submit", different, key)
            .getStatusCode()
            .value());
    var detail =
        request(
            HttpMethod.POST, "/integrations/wecom/approvals/get", Map.of("approval_no", no), key);
    assertEquals("pending", JsonUtils.map(detail.getBody().get("data")).get("status"));
    var collected =
        request(
            HttpMethod.POST,
            "/integrations/wecom/approvals/collect",
            Map.of("include_details", false),
            key);
    assertEquals(List.of(no), JsonUtils.map(collected.getBody().get("data")).get("approval_nos"));
    String other = createSystem("test_approval_other", true);
    assertEquals(
        404,
        request(
                HttpMethod.POST,
                "/integrations/wecom/approval-templates/get",
                Map.of("template", "expense"),
                other)
            .getStatusCode()
            .value());
    logs.flush();
    var logged =
        store.one(
            "SELECT request_summary FROM gateway_call_log WHERE `system`=? AND operation='wecom.approvals.submit' AND status='ok' ORDER BY id LIMIT 1",
            system);
    assertFalse(logged.get("request_summary").toString().contains("采购"));
  }

  @Test
  void approvalCallbacksAreVerifiedEncryptedDeduplicatedAndRoutedToTheOwner() throws Exception {
    String source = "test_cb_source", owner = "test_cb_owner", other = "test_cb_unrelated";
    createSystem(source, true);
    createSystem(owner, true);
    createSystem(other, true);
    String aes = Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
    credentials.save(
        "local",
        Map.of(
            "WEWORK_TEST_CB_SOURCE_CORP_ID",
            "fixture-corp",
            "WEWORK_TEST_CB_SOURCE_APPROVAL_SECRET",
            "fixture-approval-secret",
            "WEWORK_TEST_CB_SOURCE_CALLBACK_TOKEN",
            "fixture-callback-token",
            "WEWORK_TEST_CB_SOURCE_CALLBACK_AES_KEY",
            aes),
        "fixture");
    store.insert(
        "gateway_wecom_submission",
        JsonUtils.obj(
            "env",
            "local",
            "runtime",
            "live",
            "system",
            owner,
            "profile",
            approvalClient.profile(source),
            "approval_no",
            "202611050001",
            "template_id",
            "fixture-template",
            "request_id",
            "callback-owned-001"));
    String plain =
        "<xml><CreateTime>1793836800</CreateTime><Event><![CDATA[sys_approval_change]]></Event><ApprovalInfo><SpNoStr>202611050001</SpNoStr><SpNo>1</SpNo><SpStatus>2</SpStatus><TemplateId>fixture-template</TemplateId></ApprovalInfo></xml>";
    byte[] message = plain.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        receiver = "fixture-corp".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var content =
        java.nio.ByteBuffer.allocate(20 + message.length + receiver.length)
            .put(new byte[16])
            .putInt(message.length)
            .put(message)
            .put(receiver)
            .array();
    int pad = 32 - content.length % 32;
    byte[] padded = Arrays.copyOf(content, content.length + pad);
    Arrays.fill(padded, content.length, padded.length, (byte) pad);
    var cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
    cipher.init(
        javax.crypto.Cipher.ENCRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"),
        new javax.crypto.spec.IvParameterSpec(new byte[16]));
    String encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    var parts =
        new ArrayList<>(
            List.of("fixture-callback-token", "1793836800", "fixture-nonce", encrypted));
    Collections.sort(parts);
    String signature =
        HexFormat.of()
            .formatHex(
                JsonUtils.digest(
                    "SHA-1",
                    String.join("", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    String path =
        group.cyt.common.core.http.UpstreamHttp.query(
            "http://127.0.0.1:" + port + "/integrations/wecom/webhooks/" + source + "/events",
            JsonUtils.obj(
                "msg_signature", signature, "timestamp", "1793836800", "nonce", "fixture-nonce"));
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_XML);
    var envelope =
        new HttpEntity<>("<xml><Encrypt><![CDATA[" + encrypted + "]]></Encrypt></xml>", headers);
    assertEquals(
        401,
        http.exchange(
                path.replace(signature, "0".repeat(40)), HttpMethod.POST, envelope, String.class)
            .getStatusCode()
            .value());
    for (int i = 0; i < 2; i++) {
      var response = http.exchange(path, HttpMethod.POST, envelope, String.class);
      assertEquals(200, response.getStatusCode().value(), response.getBody());
      assertEquals("success", response.getBody());
    }
    assertEquals(
        1, store.count("SELECT COUNT(*) FROM gateway_wecom_event WHERE `system`=?", owner));
    assertEquals(
        0,
        store.count(
            "SELECT COUNT(*) FROM gateway_wecom_event WHERE `system` IN (?,?)", source, other));
    var row = store.one("SELECT * FROM gateway_wecom_event WHERE `system`=?", owner);
    assertEquals("202611050001", row.get("approval_no"));
    assertFalse(row.get("payload_ciphertext").toString().contains("202611050001"));
    var decoded = JsonUtils.read(credentials.decrypt(row.get("payload_ciphertext").toString()));
    assertEquals("approved", decoded.get("status"));
    var fakeCallbacks =
        org.mockito.Mockito.mock(group.cyt.common.module.callback.service.CallbackService.class);
    org.mockito.Mockito.when(
            fakeCallbacks.deliver(
                org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.eq("wecom"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(JsonUtils.obj("delivered", false, "reason", "deliver_failed"));
    var events =
        new group.cyt.common.module.adapter.wecom.approval.WecomApprovalEvents(
            store, approvalClient, credentials, settings, fakeCallbacks);
    store.update(
        "UPDATE gateway_wecom_event SET next_attempt_at=UTC_TIMESTAMP(6),delivery_state='pending' WHERE id=?",
        row.get("id"));
    events.deliverPending();
    var failed = store.one("SELECT * FROM gateway_wecom_event WHERE id=?", row.get("id"));
    assertEquals("pending", failed.get("delivery_state"));
    assertTrue(((Number) failed.get("attempts")).intValue() > 0);
    org.mockito.Mockito.when(
            fakeCallbacks.deliver(
                org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.eq("wecom"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(JsonUtils.obj("delivered", true));
    store.update(
        "UPDATE gateway_wecom_event SET next_attempt_at=UTC_TIMESTAMP(6) WHERE id=?",
        row.get("id"));
    events.deliverPending();
    assertEquals(
        "delivered",
        store
            .one("SELECT delivery_state FROM gateway_wecom_event WHERE id=?", row.get("id"))
            .get("delivery_state"));
    var echo = http.getForEntity(path + "&echostr={echo}", String.class, encrypted);
    assertEquals(plain, echo.getBody());
  }

  @AfterAll
  static void cleanup(@Autowired CallLogService logs) throws Exception {
    logs.flush();
    if (jdbcUrl == null) return;
    try (Connection c =
            DriverManager.getConnection(
                BASE + (BASE.endsWith("/") ? "" : "/") + "mysql",
                System.getenv().getOrDefault("TEST_MYSQL_USERNAME", "root"),
                System.getenv().getOrDefault("TEST_MYSQL_PASSWORD", ""));
        Statement s = c.createStatement()) {
      if (!DATABASE.matches("cyt_common_check_[a-f0-9]{12}"))
        throw new IllegalStateException("Unexpected test database");
      s.execute("DROP DATABASE `" + DATABASE + "`");
    }
  }
}

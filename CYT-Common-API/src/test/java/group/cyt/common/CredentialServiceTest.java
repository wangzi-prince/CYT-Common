package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CredentialServiceTest {

  private static final String CONTRACT =
      """
      {"credentials":[
        {"platform":"docusign","label":"DocuSign","docs":"","note":"","scope_policy":"inherit","strict_recommended":true,"system_aware":false,"accounts_var":"","default_account_var":"","account_fallback":"","vars":[
          {"name":"DOCUSIGN_INTEGRATION_KEY","label":"Integration Key","required":true,"secret":true,"hint":"","example":"","per_system":true,"scoped":"DOCUSIGN_{SYSTEM}_INTEGRATION_KEY"},
          {"name":"DOCUSIGN_PRIVATE_KEY","label":"RSA 私钥","required":true,"secret":true,"hint":"","example":"","per_system":false,"scoped":""}
        ]},
        {"platform":"quickcep","label":"QuickCEP","docs":"","note":"","scope_policy":"inherit","strict_recommended":false,"system_aware":false,"accounts_var":"QUICKCEP_ACCOUNTS","default_account_var":"QUICKCEP_ACCOUNT","account_fallback":"default","vars":[
          {"name":"QUICKCEP_{ACCOUNT}_ACCESS_ID","label":"Access ID","required":true,"secret":true,"hint":"","example":"","per_system":false,"scoped":""},
          {"name":"QUICKCEP_{ACCOUNT}_PUBLIC_KEY","label":"RSA 公钥","required":false,"secret":true,"hint":"","example":"","per_system":false,"scoped":""}
        ]}
      ]}
      """;

  private static JsonNode contract() {
    try {
      return JsonUtils.MAPPER.readTree(CONTRACT);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private CredentialService service(MockEnvironment env) {
    var registry = mock(OperationRegistry.class);
    when(registry.contract()).thenReturn(contract());
    return new CredentialService(mock(GatewayStore.class), new GatewaySettings(env), registry);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> item(List<Object> items, String platform) {
    for (var o : items) {
      var m = JsonUtils.map(o);
      if (platform.equals(m.get("platform"))) return m;
    }
    throw new AssertionError("platform not found: " + platform);
  }

  private static Map<String, Object> varOf(Map<String, Object> item, String name) {
    for (var o : JsonUtils.list(item.get("vars"))) {
      var m = JsonUtils.map(o);
      if (name.equals(m.get("name"))) return m;
    }
    throw new AssertionError("var not found: " + name);
  }

  @Test
  void inventoryMarksSharedVsScopedPerSystemState() {
    var env = new MockEnvironment().withProperty("DOCUSIGN_INTEGRATION_KEY", "abcd");
    var items = service(env).inventory(List.of("portal", "marketing"));
    var docusign = item(items, "docusign");

    assertEquals(List.of("DOCUSIGN_PRIVATE_KEY"), docusign.get("missing"));
    assertEquals(false, docusign.get("configured"));
    assertEquals(List.of("DOCUSIGN_INTEGRATION_KEY"), docusign.get("per_system_vars"));
    assertEquals(List.of("DOCUSIGN_INTEGRATION_KEY"), docusign.get("splittable_vars"));
    assertEquals(List.of("portal"), docusign.get("systems_ready"));
    assertEquals("inherit", docusign.get("scope_policy"));
    assertEquals(true, docusign.get("strict_recommended"));

    var key = varOf(docusign, "DOCUSIGN_INTEGRATION_KEY");
    assertEquals(true, key.get("set"));
    assertEquals("env", key.get("source"));
    assertEquals("4 字符", key.get("preview"));
    var scoped = JsonUtils.list(key.get("scoped"));
    assertEquals(2, scoped.size());
    var portal = JsonUtils.map(scoped.get(0));
    assertEquals("DOCUSIGN_INTEGRATION_KEY", portal.get("name"));
    assertEquals(true, portal.get("is_shared"));
    assertEquals(true, portal.get("set"));
    var marketing = JsonUtils.map(scoped.get(1));
    assertEquals("DOCUSIGN_MARKETING_INTEGRATION_KEY", marketing.get("name"));
    assertEquals(false, marketing.get("is_shared"));
    assertEquals(false, marketing.get("set"));
  }

  @Test
  void multiAccountExpandsAndInfersFallbackAccount() {
    var env =
        new MockEnvironment()
            .withProperty("QUICKCEP_ACCOUNTS", "epomaker; linsoul")
            .withProperty("QUICKCEP_EPOMAKER_ACCESS_ID", "id-1")
            .withProperty("QUICKCEP_DEFAULT_ACCESS_ID", "id-default");
    var items = service(env).inventory(List.of());
    var quickcep = item(items, "quickcep");

    assertEquals(true, quickcep.get("multi_account"));
    assertEquals(List.of("epomaker", "linsoul"), quickcep.get("accounts"));
    assertEquals(false, quickcep.get("account_missing"));
    assertEquals(false, quickcep.get("configured")); // linsoul 没配必填 ACCESS_ID
    assertEquals(List.of("QUICKCEP_LINSOUL_ACCESS_ID"), quickcep.get("missing"));
    assertEquals("epomaker", varOf(quickcep, "QUICKCEP_EPOMAKER_ACCESS_ID").get("account"));
    assertEquals(true, varOf(quickcep, "QUICKCEP_EPOMAKER_ACCESS_ID").get("set"));
    assertEquals(false, varOf(quickcep, "QUICKCEP_LINSOUL_ACCESS_ID").get("set"));
  }

  @Test
  void accountFallbackKeepsPlatformCallable() {
    var env = new MockEnvironment().withProperty("QUICKCEP_DEFAULT_ACCESS_ID", "id-default");
    var items = service(env).inventory(List.of());
    var quickcep = item(items, "quickcep");

    assertEquals(List.of("default"), quickcep.get("accounts"));
    assertEquals(false, quickcep.get("account_missing"));
    assertEquals(true, quickcep.get("configured"));
  }

  @Test
  void scopeAndSplitOverridesApply() {
    var env =
        new MockEnvironment()
            .withProperty("CRED_SCOPE_DOCUSIGN", "strict")
            .withProperty("CRED_SPLIT_DOCUSIGN", "none");
    var items = service(env).inventory(List.of("marketing"));
    var docusign = item(items, "docusign");

    assertEquals("strict", docusign.get("scope_policy"));
    assertEquals(List.of(), docusign.get("per_system_vars"));
    assertEquals(List.of(), docusign.get("systems_ready"));
    var summary = service(env).summary(items);
    assertEquals(List.of("docusign"), summary.get("strict_platforms"));
  }

  @Test
  void summaryCountsConfiguredAndMissingVars() {
    var env = new MockEnvironment().withProperty("DOCUSIGN_INTEGRATION_KEY", "abcd");
    var items = service(env).inventory(List.of());
    var summary = service(env).summary(items);

    assertEquals(2, summary.get("platforms"));
    assertEquals(0, summary.get("configured"));
    assertEquals(2, summary.get("missing_vars"));
    assertEquals(List.of("docusign"), summary.get("per_system_platforms"));
  }

  @Test
  void envFallbackAliasesDefaultAccountSegment() {
    var env = new MockEnvironment().withProperty("LINGXING_APP_ID", "app-id");
    var read = service(env).readWithSource("LINGXING_DEFAULT_APP_ID");
    assertEquals("app-id", read[0]);
    assertEquals("env", read[1]);
  }

  @Test
  void inlineGoogleCredentialsAreMaskedEvenWhenVariableAllowsAFilePath() throws Exception {
    var registry = mock(OperationRegistry.class);
    when(registry.contract())
        .thenReturn(
            JsonUtils.MAPPER.readTree(
                """
                {"credentials":[{"platform":"gcs","vars":[
                  {"name":"GOOGLE_APPLICATION_CREDENTIALS","secret":false}
                ]}]}
                """));
    String json = "{\"private_key\":\"fixture-private-key\",\"client_email\":\"fixture@example.com\"}";
    var env = new MockEnvironment().withProperty("GOOGLE_APPLICATION_CREDENTIALS", json);
    var service =
        new CredentialService(mock(GatewayStore.class), new GatewaySettings(env), registry);

    var variable = varOf(item(service.inventory(List.of()), "gcs"), "GOOGLE_APPLICATION_CREDENTIALS");
    assertEquals(json.length() + " 字符", variable.get("preview"));
    assertFalse(JsonUtils.write(variable).contains("fixture-private-key"));
  }
}

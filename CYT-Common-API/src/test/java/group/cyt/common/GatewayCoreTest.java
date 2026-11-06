package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.cyt.common.core.config.GatewayBootstrap;
import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.NetworkPolicy;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.MockAdapter;
import group.cyt.common.module.adapter.support.SignatureSupport;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.custom.adapter.CustomHttpAdapter;
import group.cyt.common.module.grant.service.QuotaService;
import group.cyt.common.module.registry.service.*;
import java.net.InetAddress;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GatewayCoreTest {
  @Test
  void restartDoesNotRegrantDemoPermissions() {
    var store = mock(GatewayStore.class);
    when(store.count("SELECT COUNT(*) FROM gateway_system")).thenReturn(1L);
    var bootstrap =
        new GatewayBootstrap(
            store, new GatewaySettings(new MockEnvironment()), mock(OperationRegistry.class));
    bootstrap.run(null);
    verify(store, never()).atomic(any());
  }

  @Test
  void simulationUsesPublicReadResponseFields() throws Exception {
    var registry = new OperationRegistry(mock(GatewayStore.class));
    var adapter = new MockAdapter();
    var profile =
        JsonUtils.map(
            adapter.invoke(
                registry.get("gmail.profile.get"),
                Map.of("account", "fixture@example.invalid"),
                "portal"));
    assertEquals("fixture@example.invalid", profile.get("emailAddress"));
    var signed =
        JsonUtils.map(
            adapter.invoke(
                registry.get("nas.objects.presign_get"),
                Map.of("keys", List.of("example.txt"), "expires", 3600),
                "drive"));
    assertEquals(3600, signed.get("expires_in"));
  }

  @Test
  void wildcardMatchesOnlyWholePrefixes() {
    assertTrue(OperationRegistry.matches("wecom.*", "wecom.users.get"));
    assertFalse(OperationRegistry.matches("wecom.*", "wecompany.users.get"));
    assertFalse(OperationRegistry.matches("wecom.*.get", "wecom.users.get"));
    assertTrue(OperationRegistry.matches("*", "gmail.profile.get"));
  }

  @Test
  void typedTemplatesPreserveObjectsAndNumbers() {
    var p = JsonUtils.obj("count", 3, "filters", JsonUtils.obj("active", true));
    assertEquals(3, CustomHttpAdapter.template("{{params.count}}", p));
    assertEquals(Map.of("active", true), CustomHttpAdapter.template("{{params.filters}}", p));
    assertEquals("count=3", CustomHttpAdapter.template("count={{params.count}}", p));
    assertEquals("{{evil()}}", CustomHttpAdapter.template("{{evil()}}", p));
  }

  @Test
  void rejectsReservedNetworks() throws Exception {
    for (String ip :
        List.of(
            "127.0.0.1",
            "10.0.0.1",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "::1",
            "fd00::1")) assertTrue(NetworkPolicy.privateAddress(InetAddress.getByName(ip)), ip);
    assertFalse(NetworkPolicy.privateAddress(InetAddress.getByName("8.8.8.8")));
  }

  @Test
  void internalServiceTrustIsAnExactOriginAndNeverIncludesMetadataOrLoopback() {
    var policy =
        new NetworkPolicy(
            new GatewaySettings(
                new MockEnvironment()
                    .withProperty("ENV", "prod")
                    .withProperty(
                        "INTERNAL_API_ALLOWED_ORIGINS",
                        "http://10.20.30.40:9000,http://169.254.169.254,http://127.0.0.1:9000")));
    assertEquals(1, policy.resolve("http://10.20.30.40:9000/orders/123").size());
    assertThrows(GatewayException.class, () -> policy.resolve("http://10.20.30.40:9001/orders"));
    assertThrows(GatewayException.class, () -> policy.resolve("http://10.20.30.41:9000/orders"));
    assertThrows(
        GatewayException.class, () -> policy.resolve("http://169.254.169.254/latest/meta-data"));
    assertThrows(GatewayException.class, () -> policy.resolve("http://127.0.0.1:9000/admin"));
    var untrusted = new NetworkPolicy(new GatewaySettings(new MockEnvironment()));
    assertThrows(GatewayException.class, () -> untrusted.resolve("https://10.20.30.40/orders"));
  }

  @Test
  void fernetRoundTripAuthenticatesCiphertext() throws Exception {
    var settings =
        new GatewaySettings(
            new MockEnvironment().withProperty("CREDENTIAL_MASTER_KEY", "fixture-master-key"));
    var service =
        new CredentialService(mock(GatewayStore.class), settings, mock(OperationRegistry.class));
    String ciphertext = service.encrypt("中文-private-value");
    assertTrue(ciphertext.startsWith("gAAAA"));
    assertFalse(ciphertext.contains("private-value"));
    assertEquals("中文-private-value", service.decrypt(ciphertext));
    byte[] bytes = Base64.getUrlDecoder().decode(ciphertext);
    bytes[30] ^= 1;
    assertThrows(
        GatewayException.class,
        () -> service.decrypt(Base64.getUrlEncoder().encodeToString(bytes)));
  }

  @Test
  void disabledEncryptionNeverFallsBackToPlaintext() {
    var service =
        new CredentialService(
            mock(GatewayStore.class),
            new GatewaySettings(new MockEnvironment()),
            mock(OperationRegistry.class));
    assertThrows(GatewayException.class, () -> service.encrypt("secret"));
  }

  @Test
  void normalizesDateTimesAndRecipients() {
    var p =
        ParameterRules.normalize(
            "wecom.schedules.create",
            JsonUtils.obj(
                "start_at",
                "2026-10-30 10:00:00",
                "end_at",
                "2026-10-30T11:00:00+08:00",
                "attendees",
                List.of("fixture-user")));
    assertEquals(
        3600L,
        ((Number) p.get("end_time")).longValue() - ((Number) p.get("start_time")).longValue());
    assertEquals(List.of(Map.of("userid", "fixture-user")), p.get("attendees"));
    assertEquals(
        List.of("fixture@example.invalid"),
        ParameterRules.normalize("gmail.messages.send", Map.of("to", "fixture@example.invalid"))
            .get("to"));
  }

  @Test
  void crossFieldChecksRejectDangerousAmbiguity() {
    assertThrows(
        GatewayException.class, () -> ParameterRules.validate("gcs.objects.upload", Map.of()));
    assertThrows(
        GatewayException.class,
        () ->
            ParameterRules.validate(
                "shopify.refunds.create", JsonUtils.obj("confirm", false, "amount", "5")));
    assertThrows(
        GatewayException.class,
        () ->
            ParameterRules.validate(
                "wecom.schedules.create", JsonUtils.obj("start_time", 100, "end_time", 90)));
  }

  @Test
  void slidingQuotaRejectsExcessCalls() {
    try (var service = new QuotaService(new GatewaySettings(new MockEnvironment()))) {
      service.consume("fixture", "2/min");
      service.consume("fixture", "2/min");
      assertEquals(
          429,
          assertThrows(GatewayException.class, () -> service.consume("fixture", "2/min")).status);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void winitSignatureUsesSortedCompactData() {
    var params =
        JsonUtils.obj(
            "action",
            "fixture.action",
            "app_key",
            "fixture-key",
            "data",
            JsonUtils.obj("z", 1, "a", ""),
            "format",
            "json",
            "platform",
            "CaseSensitive",
            "sign_method",
            "md5",
            "timestamp",
            "2026-10-29 12:00:00",
            "version",
            "1.0");
    String one = SignatureSupport.winit(params, "fixture-secret");
    params.put("data", JsonUtils.obj("a", "", "z", 1));
    assertEquals(one, SignatureSupport.winit(params, "fixture-secret"));
    params.put("platform", "casesensitive");
    assertNotEquals(one, SignatureSupport.winit(params, "fixture-secret"));
  }
}

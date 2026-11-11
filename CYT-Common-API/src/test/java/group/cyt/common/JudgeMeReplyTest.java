package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.MockAdapter;
import group.cyt.common.module.adapter.source.CustomerSourcesAdapter;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JudgeMeReplyTest {
  OperationRegistry registry;
  CredentialService credentials;
  UpstreamHttp http;
  CustomerSourcesAdapter adapter;

  @BeforeEach
  void setup() throws Exception {
    registry = new OperationRegistry(mock(GatewayStore.class));
    credentials = mock(CredentialService.class);
    when(credentials.resolve(anyString(), anyString())).thenReturn("");
    when(credentials.resolve("JUDGEME_FIXTURE_API_TOKEN", "cs"))
        .thenReturn("fixture-private-token");
    when(credentials.resolve("JUDGEME_FIXTURE_SHOP_DOMAIN", "cs"))
        .thenReturn("fixture.myshopify.com");
    http = mock(UpstreamHttp.class);
    adapter = new CustomerSourcesAdapter(credentials, http);
  }

  Map<String, Object> params() {
    return registry.validate(
        registry.get("judgeme.reviews.reply"),
        Map.of(
            "account",
            "fixture",
            "review_id",
            987654,
            "body",
            "Thanks, we will help.",
            "confirm",
            true));
  }

  @Test
  void publishesWithCorrectShopIdTokenAndNotificationDefault() {
    when(http.exchange(anyString(), anyString(), anyMap(), any(), anyDouble(), isNull()))
        .thenReturn(new UpstreamHttp.ResponseData(200, Map.of(), new byte[0]));
    var op = registry.get("judgeme.reviews.reply");
    var result = JsonUtils.map(adapter.invoke(op, params(), "cs"));
    assertEquals(true, result.get("ok"));
    assertEquals(987654, result.get("review_id"));
    assertEquals(false, result.get("send_reply_email"));
    assertFalse(result.toString().contains("fixture-private-token"));
    verify(http)
        .exchange(
            eq("POST"),
            eq("https://api.judge.me/api/v1/replies?shop_domain=fixture.myshopify.com"),
            eq(Map.of("X-Api-Token", "fixture-private-token", "Content-Type", "application/json")),
            argThat(
                bytes ->
                    JsonUtils.map(JsonUtils.parse(new String(bytes, StandardCharsets.UTF_8)))
                        .equals(
                            JsonUtils.obj(
                                "review_id",
                                987654,
                                "reply",
                                Map.of("content", "Thanks, we will help."),
                                "send_reply_email",
                                false))),
            eq(30.0),
            isNull());
    assertTrue(op.sensitive());
    assertTrue(op.idempotent());
    assertEquals(1, op.retryMax());
    assertFalse(op.readOnly());
  }

  @Test
  void emailNotificationIsOptIn() {
    when(http.exchange(anyString(), anyString(), anyMap(), any(), anyDouble(), isNull()))
        .thenReturn(
            new UpstreamHttp.ResponseData(200, Map.of(), "{}".getBytes(StandardCharsets.UTF_8)));
    var params = params();
    params.put("send_reply_email", true);
    assertEquals(
        true,
        JsonUtils.map(adapter.invoke(registry.get("judgeme.reviews.reply"), params, "cs"))
            .get("send_reply_email"));
  }

  @Test
  void rejectsMissingConfirmationAndNeverFallsBackToAnotherAccount() {
    var p = params();
    p.put("confirm", false);
    assertThrows(
        GatewayException.class,
        () -> adapter.invoke(registry.get("judgeme.reviews.reply"), p, "cs"));
    p.put("confirm", true);
    p.put("account", "unconfigured");
    assertThrows(
        GatewayException.class,
        () -> adapter.invoke(registry.get("judgeme.reviews.reply"), p, "cs"));
    verifyNoInteractions(http);
  }

  @Test
  void rejectsBadIdsWhitespaceAndUnknownFieldsBeforeSending() {
    for (Object id : new Object[] {0, -1, 1.5, true, "fixture:123"})
      assertThrows(
          GatewayException.class,
          () ->
              registry.validate(
                  registry.get("judgeme.reviews.reply"),
                  Map.of("review_id", id, "body", "Reply", "confirm", true)));
    var p = params();
    p.put("body", "  ");
    assertThrows(
        GatewayException.class,
        () -> adapter.invoke(registry.get("judgeme.reviews.reply"), p, "cs"));
    p.put("api_token", "injected");
    assertThrows(
        GatewayException.class, () -> registry.validate(registry.get("judgeme.reviews.reply"), p));
    verifyNoInteractions(http);
  }

  @Test
  void rejectsErrorAndMalformedResponses() {
    for (String response :
        new String[] {
          "{\"error\":\"Rejected\"}",
          "{\"ok\":false}",
          "{\"success\":false}",
          "[]",
          "<html>Error</html>"
        }) {
      when(http.exchange(anyString(), anyString(), anyMap(), any(), anyDouble(), isNull()))
          .thenReturn(
              new UpstreamHttp.ResponseData(
                  200, Map.of(), response.getBytes(StandardCharsets.UTF_8)));
      assertThrows(
          GatewayException.class,
          () -> adapter.invoke(registry.get("judgeme.reviews.reply"), params(), "cs"));
    }
  }

  @Test
  void unexpectedHttpStatusCannotBecomeSuccess() {
    for (int status : new int[] {201, 204, 302, 307}) {
      when(http.exchange(anyString(), anyString(), anyMap(), any(), anyDouble(), isNull()))
          .thenReturn(new UpstreamHttp.ResponseData(status, Map.of(), new byte[0]));
      assertThrows(
          GatewayException.class,
          () -> adapter.invoke(registry.get("judgeme.reviews.reply"), params(), "cs"));
    }
  }

  @Test
  void simulatedReplyDoesNotClaimToHavePublished() {
    var result =
        JsonUtils.map(
            new MockAdapter().invoke(registry.get("judgeme.reviews.reply"), params(), "cs"));
    assertEquals(false, result.get("ok"));
    assertEquals(true, result.get("mock"));
  }
}

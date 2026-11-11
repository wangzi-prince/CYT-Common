package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.source.CustomerSourcesAdapter;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JudgeMePublicRepliesTest {
  OperationRegistry registry;
  UpstreamHttp http;
  CustomerSourcesAdapter adapter;
  static final String UUID = "12345678-1234-4321-8123-123456789abc";

  @BeforeEach
  void setup() throws Exception {
    registry = new OperationRegistry(mock(GatewayStore.class));
    var credentials = mock(CredentialService.class);
    when(credentials.resolve(anyString(), anyString())).thenReturn("");
    when(credentials.resolve("JUDGEME_FIXTURE_API_TOKEN", "cs"))
        .thenReturn("fixture-private-token");
    when(credentials.resolve("JUDGEME_FIXTURE_SHOP_DOMAIN", "cs"))
        .thenReturn("fixture.myshopify.com");
    http = mock(UpstreamHttp.class);
    adapter = new CustomerSourcesAdapter(credentials, http);
  }

  Map<String, Object> row() {
    return JsonUtils.obj(
        "uuid",
        UUID,
        "rating",
        1,
        "body",
        "A broken key.",
        "title",
        "Issue",
        "created_at",
        "2026-11-10T12:00:00.123Z",
        "product_url",
        "/products/fixture-keyboard",
        "reply_content",
        "<p>We will help.</p>");
  }

  void response(Object value) {
    when(http.json(anyString(), anyString(), anyMap(), isNull(), anyDouble())).thenReturn(value);
  }

  Map<String, Object> invoke(Map<String, Object> cursor) {
    var op = registry.get("judgeme.replies.list");
    return JsonUtils.map(
        adapter.invoke(
            op, registry.validate(op, Map.of("account", "fixture", "cursor", cursor)), "cs"));
  }

  Map<String, Object> first(Map<String, Object> result) {
    return JsonUtils.map(((List<?>) result.get("items")).getFirst());
  }

  @Test
  void readsOfficialPublicWidgetWithHeaderCredentialAndExplicitCoverage() {
    response(JsonUtils.obj("reviews", List.of(row()), "current_page", 1, "total_pages", 2));
    var result = invoke(Map.of());
    assertEquals("replied", first(result).get("reply_state"));
    assertEquals(UUID, first(result).get("id"));
    assertEquals("fixture-keyboard", first(result).get("product_handle"));
    assertEquals("published_public_replies", result.get("coverage"));
    assertEquals(false, result.get("reply_timestamp_available"));
    assertEquals(Map.of("page", 2, "review_type", "product-reviews"), result.get("cursor"));
    assertFalse(result.toString().contains("fixture-private-token"));
    verify(http)
        .json(
            eq("GET"),
            argThat(
                url ->
                    url.startsWith("https://api.judge.me/api/v1/widgets/all_reviews_page?")
                        && url.contains("json_request=true")
                        && url.contains("review_type=product-reviews")
                        && url.contains("shop_domain=fixture.myshopify.com")
                        && !url.contains("api_token")),
            eq(Map.of("X-Api-Token", "fixture-private-token")),
            isNull(),
            anyDouble());
    assertTrue(registry.get("judgeme.replies.list").readOnly());
  }

  @Test
  void completesOnlyAfterProductAndShopReviewStreams() {
    response(JsonUtils.obj("reviews", List.of(), "current_page", 1, "total_pages", 0));
    var product = invoke(Map.of());
    assertEquals(true, product.get("has_more"));
    assertEquals(Map.of("page", 1, "review_type", "shop-reviews"), product.get("cursor"));
    var shop = invoke(Map.of("review_type", "shop-reviews"));
    assertEquals(false, shop.get("has_more"));
    assertEquals(Map.of("page", 1, "review_type", "product-reviews"), shop.get("cursor"));
  }

  @Test
  void absenceAndExplicitEmptyReplyAreDifferentObservations() {
    var row = row();
    row.remove("reply_content");
    response(JsonUtils.obj("reviews", List.of(row), "current_page", 1, "total_pages", 1));
    assertEquals("unknown", first(invoke(Map.of())).get("reply_state"));
    row.put("reply_content", null);
    assertEquals("unreplied", first(invoke(Map.of())).get("reply_state"));
    row.put("reply_content", "");
    assertEquals("unreplied", first(invoke(Map.of())).get("reply_state"));
    row.put("reply_content", Map.of("unexpected", true));
    assertEquals("unknown", first(invoke(Map.of())).get("reply_state"));
  }

  @Test
  void marksGroupedReviewsSoTheyCannotBeAttachedToAnotherProduct() {
    var row = row();
    row.put("is_for_product_from_group", true);
    response(JsonUtils.obj("reviews", List.of(row), "current_page", 1, "total_pages", 1));
    assertEquals(true, first(invoke(Map.of())).get("grouped"));
  }

  @Test
  void refusesInvalidOrIncompletePagesWithoutPretendingSuccess() {
    for (Object value :
        List.of(
            Map.of(),
            Map.of("error", "unavailable"),
            JsonUtils.obj("reviews", List.of(), "current_page", 2, "total_pages", 2),
            JsonUtils.obj("reviews", List.of(), "current_page", 1, "total_pages", 2),
            JsonUtils.obj("reviews", List.of(row(), row()), "current_page", 1, "total_pages", 1))) {
      response(value);
      assertThrows(GatewayException.class, () -> invoke(Map.of()));
    }
    var invalid = row();
    invalid.put("uuid", "123");
    response(JsonUtils.obj("reviews", List.of(invalid), "current_page", 1, "total_pages", 1));
    assertThrows(GatewayException.class, () -> invoke(Map.of()));
  }

  @Test
  void rejectsUnsupportedStreamBeforeCallingUpstream() {
    assertThrows(GatewayException.class, () -> invoke(Map.of("review_type", "private-reviews")));
    verifyNoInteractions(http);
  }
}

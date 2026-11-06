package group.cyt.common.module.adapter.shopify;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.adapter.support.TestWritePolicy;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ShopifyAdapter implements PlatformAdapter {
  private record Client(String store, String url, Map<String, String> headers) {}

  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final TestWritePolicy writePolicy;
  private final Map<String, Object> queries;

  public ShopifyAdapter(
      CredentialService credentials, UpstreamHttp http, TestWritePolicy writePolicy)
      throws Exception {
    this.credentials = credentials;
    this.http = http;
    this.writePolicy = writePolicy;
    try (var input = getClass().getResourceAsStream("/shopify-queries.json")) {
      queries =
          JsonUtils.MAPPER.readValue(
              input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
  }

  public boolean supports(String platform) {
    return platform.equals("shopify");
  }

  public Object invoke(OperationDefinition op, Map<String, Object> p, String system) {
    String actual =
        credentials
            .required("SHOPIFY_STORE", system)
            .replaceFirst("^https?://", "")
            .replaceAll("/$", "");
    if (!actual.matches("[A-Za-z0-9][A-Za-z0-9.-]*"))
      throw GatewayException.config("Shopify 店铺域名无效");
    String requested =
        JsonUtils.str(p, "store").replaceFirst("^https?://", "").replaceAll("/$", "");
    if (!requested.isBlank() && !actual.equalsIgnoreCase(requested))
      throw GatewayException.forbidden("请求店铺与实际授权店铺不一致");
    String version = credentials.resolve("SHOPIFY_API_VERSION", system);
    if (version.isBlank()) version = "2026-07";
    if (!version.matches("[0-9]{4}-[0-9]{2}")) throw GatewayException.config("Shopify API 版本格式无效");
    Client c =
        new Client(
            actual,
            "https://" + actual + "/admin/api/" + version + "/graphql.json",
            Map.of("X-Shopify-Access-Token", credentials.required("SHOPIFY_ACCESS_TOKEN", system)));
    String key = op.key().substring(8);
    if (!op.readOnly()) writePolicy.requireTarget("shopify", requested);
    return switch (key) {
      case "orders.get" ->
          JsonUtils.obj(
              "order",
              query(c, "_ORDER_QUERY", JsonUtils.obj("id", p.get("order_id"))).get("order"),
              "mock",
              false);
      case "products.get" ->
          JsonUtils.obj(
              "product",
              query(c, "_PRODUCT_QUERY", JsonUtils.obj("id", p.get("product_id"))).get("product"),
              "mock",
              false);
      case "orders.list", "products.list", "returns.list", "refunds.list" -> list(c, key, p);
      case "orders.update" -> {
        var input = JsonUtils.obj("id", p.get("order_id"));
        for (String field : List.of("note", "tags"))
          if (p.get(field) != null) input.put(field, p.get(field));
        if (p.get("shipping_address") != null)
          input.put("shippingAddress", address(JsonUtils.map(p.get("shipping_address"))));
        var r =
            block(query(c, "_ORDER_UPDATE_MUTATION", JsonUtils.obj("input", input)), "orderUpdate");
        yield JsonUtils.obj("order", r.get("order"), "mock", false);
      }
      case "inventory.set" -> {
        var input =
            JsonUtils.obj(
                "name",
                "available",
                "reason",
                p.get("reason"),
                "quantities",
                List.of(
                    JsonUtils.obj(
                        "inventoryItemId",
                        p.get("inventory_item_id"),
                        "locationId",
                        p.get("location_id"),
                        "quantity",
                        p.get("available"))));
        block(
            query(c, "_INVENTORY_MUTATION", JsonUtils.obj("input", input)),
            "inventorySetQuantities");
        yield JsonUtils.obj("ok", true, "errors", List.of(), "mock", false);
      }
      case "orders.create" -> create(c, p);
      case "refunds.create" -> refund(c, p);
      default -> throw GatewayException.config("未知 Shopify 操作: " + key);
    };
  }

  private Map<String, Object> query(Client c, String name, Map<String, Object> variables) {
    String query = JsonUtils.str(queries, name);
    if (query.isBlank()) throw GatewayException.config("缺少内置 GraphQL 模板");
    return graphql(c, query, variables);
  }

  private Map<String, Object> graphql(Client c, String query, Map<String, Object> variables) {
    var result =
        http.object(
            "POST", c.url(), c.headers(), JsonUtils.obj("query", query, "variables", variables));
    if (!JsonUtils.list(result.get("errors")).isEmpty())
      throw new GatewayException(502, "upstream_error", "Shopify GraphQL 调用失败，请核对应用权限与参数", false);
    return JsonUtils.map(result.get("data"));
  }

  private Object list(Client c, String key, Map<String, Object> p) {
    String template =
        switch (key) {
          case "orders.list" -> "_ORDERS_QUERY";
          case "products.list" -> "_PRODUCTS_QUERY";
          case "returns.list" -> "_RETURNS_QUERY";
          default -> "_REFUNDS_QUERY";
        };
    var variables =
        JsonUtils.obj("first", p.get("limit"), "query", p.get("query"), "after", p.get("after"));
    if (key.equals("orders.list") || key.equals("products.list")) {
      variables.put("sortKey", p.get("sort_key"));
      variables.put("reverse", p.get("reverse"));
    }
    var result = query(c, template, variables);
    String resource = key.split("\\.")[0];
    var connection = JsonUtils.map(result.get(resource.equals("refunds") ? "orders" : resource));
    List<Object> items = new ArrayList<>();
    for (Object edge : JsonUtils.list(connection.get("edges"))) {
      var node = JsonUtils.map(JsonUtils.map(edge).get("node"));
      if (!resource.equals("refunds")) items.add(node);
      else
        for (Object value : JsonUtils.list(node.get("refunds"))) {
          var refund = new LinkedHashMap<>(JsonUtils.map(value));
          refund.put(
              "transactions",
              JsonUtils.list(JsonUtils.map(refund.get("transactions")).get("edges")).stream()
                  .map(x -> JsonUtils.map(x).get("node"))
                  .toList());
          refund.put(
              "order",
              JsonUtils.obj(
                  "id",
                  node.get("id"),
                  "name",
                  node.get("name"),
                  "created_at",
                  node.get("createdAt"),
                  "updated_at",
                  node.get("updatedAt"),
                  "financial_status",
                  node.get("displayFinancialStatus"),
                  "customer",
                  node.get("customer"),
                  "total_refunded_set",
                  node.get("totalRefundedSet")));
          items.add(refund);
        }
    }
    return JsonUtils.obj(
        "items", items, "page_info", connection.get("pageInfo"), "store", c.store(), "mock", false);
  }

  private Object create(Client c, Map<String, Object> p) {
    String tag = "cs-reship-" + JsonUtils.str(p, "reference");
    var existing =
        query(
            c,
            "_ORDERS_QUERY",
            JsonUtils.obj(
                "first",
                2,
                "query",
                "tag:" + tag,
                "after",
                null,
                "sortKey",
                "CREATED_AT",
                "reverse",
                false));
    var edges = JsonUtils.list(JsonUtils.map(existing.get("orders")).get("edges"));
    if (edges.size() > 1) throw new GatewayException(409, "conflict", "存在多个同编号补发订单，需人工核对", false);
    if (!edges.isEmpty()) {
      var old = JsonUtils.map(JsonUtils.map(edges.getFirst()).get("node"));
      return JsonUtils.obj("id", old.get("id"), "order", old, "duplicate", true, "mock", false);
    }
    List<Object> lines = new ArrayList<>();
    for (Object v : JsonUtils.list(p.get("line_items"))) {
      var line = JsonUtils.map(v);
      String sku = JsonUtils.str(line, "sku");
      String escaped = sku.replace("\\", "\\\\").replace("\"", "\\\"");
      var variants =
          query(c, "_VARIANT_SKU_QUERY", JsonUtils.obj("query", "sku:\"" + escaped + "\""));
      var matches =
          JsonUtils.list(JsonUtils.map(variants.get("productVariants")).get("nodes")).stream()
              .map(JsonUtils::map)
              .filter(x -> sku.equals(x.get("sku")))
              .toList();
      if (matches.size() != 1) throw GatewayException.invalid("SKU 未唯一匹配店铺商品，拒绝创建补发单");
      lines.add(
          JsonUtils.obj(
              "variantId",
              matches.getFirst().get("id"),
              "quantity",
              line.get("quantity"),
              "priceSet",
              JsonUtils.obj(
                  "shopMoney",
                  JsonUtils.obj("amount", "0.00", "currencyCode", p.get("currency")))));
    }
    var address = address(JsonUtils.map(p.get("shipping_address")));
    if (JsonUtils.str(address, "address1").isBlank()
        || JsonUtils.str(address, "countryCode").isBlank())
      throw GatewayException.invalid("补发收货地址和国家不能为空");
    var order =
        JsonUtils.obj(
            "email",
            p.get("customer_email"),
            "currency",
            p.get("currency"),
            "financialStatus",
            "PAID",
            "shippingAddress",
            address,
            "lineItems",
            lines,
            "tags",
            List.of(tag, "CS-RESHIP"),
            "sourceName",
            "cyt-cs-reship",
            "sourceIdentifier",
            p.get("reference"),
            "note",
            "Replacement for " + p.get("reference"));
    var result =
        block(
            query(
                c,
                "_ORDER_CREATE_MUTATION",
                JsonUtils.obj(
                    "order",
                    order,
                    "options",
                    JsonUtils.obj("sendReceipt", false, "sendFulfillmentReceipt", false))),
            "orderCreate");
    var created = JsonUtils.map(result.get("order"));
    if (JsonUtils.str(created, "id").isBlank())
      throw new GatewayException(502, "upstream_error", "Shopify 未确认补发创建成功", false);
    return JsonUtils.obj("id", created.get("id"), "order", created, "mock", false);
  }

  private Object refund(Client c, Map<String, Object> p) {
    var pre = query(c, "_REFUND_PREFLIGHT_QUERY", JsonUtils.obj("id", p.get("order_id")));
    var order = JsonUtils.map(pre.get("order"));
    if (order.isEmpty()) throw GatewayException.missing("订单不存在");
    BigDecimal total = money(order, "totalPriceSet"),
        refunded = money(order, "totalRefundedSet"),
        amount = new BigDecimal(JsonUtils.str(p, "amount"));
    if (amount.compareTo(total.subtract(refunded)) > 0)
      throw GatewayException.invalid("退款金额超过订单可退余额");
    var sale =
        JsonUtils.list(order.get("transactions")).stream()
            .map(JsonUtils::map)
            .filter(
                t ->
                    JsonUtils.str(t, "kind").equals("SALE")
                        && JsonUtils.str(t, "status").equals("SUCCESS"))
            .reduce((a, b) -> b)
            .orElseThrow(() -> GatewayException.invalid("该订单没有成功的 SALE 流水，无法退款"));
    var input =
        JsonUtils.obj(
            "orderId",
            p.get("order_id"),
            "notify",
            p.get("notify"),
            "transactions",
            List.of(
                JsonUtils.obj(
                    "orderId",
                    p.get("order_id"),
                    "kind",
                    "REFUND",
                    "gateway",
                    sale.get("gateway"),
                    "parentId",
                    sale.get("id"),
                    "amount",
                    amount.toPlainString())));
    if (!JsonUtils.str(p, "note").isBlank()) input.put("note", p.get("note"));
    if (JsonUtils.bool(p.get("restock"))) {
      List<Object> lines = new ArrayList<>();
      for (Object edge : JsonUtils.list(JsonUtils.map(order.get("lineItems")).get("edges"))) {
        var node = JsonUtils.map(JsonUtils.map(edge).get("node"));
        int quantity =
            JsonUtils.integer(
                node.get("currentQuantity"), JsonUtils.integer(node.get("quantity"), 0));
        if (quantity > 0)
          lines.add(
              JsonUtils.obj(
                  "lineItemId", node.get("id"), "quantity", quantity, "restockType", "RETURN"));
      }
      if (!lines.isEmpty()) input.put("refundLineItems", lines);
    }
    String key = JsonUtils.str(p, "idempotency_key");
    if (key.isBlank()) key = UUID.randomUUID().toString();
    if (!key.matches("[A-Za-z0-9_-]{1,64}")) throw GatewayException.invalid("退款幂等键格式无效");
    String mutation = JsonUtils.str(queries, "_REFUND_CREATE_MUTATION").replace("__KEY__", key);
    var result = block(graphql(c, mutation, JsonUtils.obj("input", input)), "refundCreate");
    return JsonUtils.obj(
        "refund",
        result.get("refund"),
        "order",
        result.get("order"),
        "requested",
        JsonUtils.obj(
            "amount",
            amount.toPlainString(),
            "currency",
            JsonUtils.at(order, "totalPriceSet.shopMoney.currencyCode"),
            "remaining_before",
            total.subtract(refunded).toPlainString()),
        "mock",
        false);
  }

  private static BigDecimal money(Map<String, Object> order, String field) {
    return new BigDecimal(Objects.toString(JsonUtils.at(order, field + ".shopMoney.amount"), "0"));
  }

  private static Map<String, Object> block(Map<String, Object> data, String name) {
    var result = JsonUtils.map(data.get(name));
    if (!JsonUtils.list(result.get("userErrors")).isEmpty())
      throw new GatewayException(502, "upstream_error", "Shopify 拒绝执行操作", false)
          .detail(result.get("userErrors"));
    return result;
  }

  private static Map<String, Object> address(Map<String, Object> source) {
    var out = new LinkedHashMap<>(source);
    out.values().removeIf(Objects::isNull);
    String name = JsonUtils.str(out, "name").trim();
    out.remove("name");
    if (!name.isBlank()) {
      int split = name.lastIndexOf(' ');
      out.put("firstName", split < 0 ? name : name.substring(0, split).trim());
      if (split >= 0) out.put("lastName", name.substring(split + 1).trim());
    }
    return out;
  }
}

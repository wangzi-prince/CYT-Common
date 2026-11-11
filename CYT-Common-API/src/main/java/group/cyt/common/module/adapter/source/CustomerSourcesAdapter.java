package group.cyt.common.module.adapter.source;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.math.BigDecimal;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * CS sources and confirmed Judge.me public replies.
 *
 * <p>Missing credentials fail loudly rather than returning mock data.
 */
@Component
public class CustomerSourcesAdapter implements PlatformAdapter {
  private static final Set<String> PLATFORMS =
      Set.of("judgeme", "trustpilot", "paypal", "stripe", "rd");

  /** Currencies with no minor unit — Stripe reports the amount already in the major unit. */
  private static final Set<String> ZERO_DECIMAL =
      Set.of(
          "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV",
          "XAF", "XOF", "XPF");

  private static final Set<String> THREE_DECIMAL = Set.of("BHD", "JOD", "KWD", "OMR", "TND");

  private final CredentialService credentials;
  private final UpstreamHttp http;

  public CustomerSourcesAdapter(CredentialService credentials, UpstreamHttp http) {
    this.credentials = credentials;
    this.http = http;
  }

  @Override
  public boolean supports(String platform) {
    return PLATFORMS.contains(platform);
  }

  @Override
  public Object invoke(OperationDefinition op, Map<String, Object> params, String system) {
    double timeout = op.timeoutSeconds();
    String account = JsonUtils.str(params, "account");
    Map<String, Object> cursor = JsonUtils.map(params.get("cursor"));
    int limit = JsonUtils.integer(params.get("limit"), 100);
    return switch (op.key()) {
      case "judgeme.reviews.list" -> judgemeReviews(account, cursor, limit, timeout, system);
      case "judgeme.reviews.reply" -> judgemeReply(account, params, timeout, system);
      case "trustpilot.reviews.list" -> trustpilotReviews(account, cursor, limit, timeout, system);
      case "stripe.disputes.list" -> stripeDisputes(account, cursor, limit, timeout, system);
      case "paypal.disputes.list" -> paypalDisputes(account, cursor, limit, timeout, system);
      case "rd.products.list" -> rdProducts(account, cursor, timeout, system);
      default -> throw GatewayException.config("未知客服来源操作: " + op.key());
    };
  }

  // ── credential helpers ──────────────────────────────────────────────────────

  /**
   * SOURCE_{ACCOUNT}_FIELD, or SOURCE_FIELD when no account is named — the same layout the
   * credential page writes, so 「已配」 on the page means resolvable here.
   */
  private String cred(
      String source, String account, String field, boolean required, String system) {
    String key =
        account == null || account.isBlank()
            ? source.toUpperCase(Locale.ROOT) + "_" + field
            : source.toUpperCase(Locale.ROOT)
                + "_"
                + CredentialService.accountKey(account)
                + "_"
                + field;
    String value = credentials.resolve(key, system).strip();
    if (value.isEmpty() && required)
      throw GatewayException.config(source + " 缺少账号配置 " + key + "；不会返回模拟数据");
    return value;
  }

  // ── pagination helpers ──────────────────────────────────────────────────────

  private static int pageNumber(Map<String, Object> cursor) {
    Object raw = cursor.get("page");
    if (raw == null || String.valueOf(raw).isBlank()) return 1;
    int page;
    if (raw instanceof Number n) page = n.intValue();
    else
      try {
        page = Integer.parseInt(String.valueOf(raw).strip());
      } catch (NumberFormatException e) {
        throw GatewayException.invalid("分页游标 page 必须为正整数");
      }
    if (page < 1) throw GatewayException.invalid("分页游标 page 必须为正整数");
    return page;
  }

  /**
   * Full scans revisit updates to old reviews/disputes. Never infer deletions from an incomplete
   * page or silently treat absence as a removed review.
   */
  private static Map<String, Object> pageResult(List<?> items, int page, boolean hasMore) {
    return JsonUtils.obj(
        "items", items,
        "has_more", hasMore,
        "cursor", JsonUtils.obj("page", hasMore ? page + 1 : 1));
  }

  // ── field helpers ───────────────────────────────────────────────────────────

  /** First 10 chars (YYYY-MM-DD) of an ISO date string, or null if absent. */
  private static String date10(Object value) {
    String text = Objects.toString(value, "").strip();
    return text.isEmpty() ? null : text.substring(0, Math.min(10, text.length()));
  }

  /**
   * Stripe reports amounts in the minor unit. Convert to a decimal string in the major unit so
   * callers do not need per-currency scaling logic.
   */
  private static String majorAmount(Object amount, Object currency) {
    String code = Objects.toString(currency, "").toUpperCase(Locale.ROOT);
    int exponent = ZERO_DECIMAL.contains(code) ? 0 : THREE_DECIMAL.contains(code) ? 3 : 2;
    BigDecimal value = new BigDecimal(Objects.toString(amount, "0"));
    return value.movePointLeft(exponent).stripTrailingZeros().toPlainString();
  }

  /** Unix seconds → ISO-8601 string with explicit UTC offset, matching Python's isoformat(). */
  private static String epochDate(Object value) {
    if (value == null) return null;
    long seconds =
        value instanceof Number n ? n.longValue() : Long.parseLong(Objects.toString(value, "0"));
    if (seconds == 0) return null;
    return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"))
        + "+00:00";
  }

  /** Python's `a or b`: an empty string counts as absent, and no value at all stays null. */
  private static String orFirst(Object primary, Object fallback) {
    String text = Objects.toString(primary, "").strip();
    if (!text.isEmpty()) return text;
    String other = Objects.toString(fallback, "").strip();
    return other.isEmpty() ? null : other;
  }

  // ── judge.me ────────────────────────────────────────────────────────────────

  private Map<String, Object> judgemeReply(
      String account, Map<String, Object> params, double timeout, String system) {
    if (!Boolean.TRUE.equals(params.get("confirm")))
      throw GatewayException.invalid("公开回复 Judge.me 评论需要 confirm=true");
    String body = JsonUtils.str(params, "body").strip();
    String id = Objects.toString(params.get("review_id"), "");
    if (body.isEmpty() || body.length() > 10000 || !id.matches("[1-9][0-9]*"))
      throw GatewayException.invalid("需要有效的 Judge.me 评论 ID 和非空回复正文");
    String token = cred("judgeme", account, "API_TOKEN", true, system);
    String shop = cred("judgeme", account, "SHOP_DOMAIN", true, system);
    boolean sendEmail = Boolean.TRUE.equals(params.get("send_reply_email"));
    var response =
        http.exchange(
            "POST",
            UpstreamHttp.query("https://api.judge.me/api/v1/replies", Map.of("shop_domain", shop)),
            Map.of("X-Api-Token", token, "Content-Type", "application/json"),
            JsonUtils.write(
                    JsonUtils.obj(
                        "review_id",
                        params.get("review_id"),
                        "reply",
                        Map.of("content", body),
                        "send_reply_email",
                        sendEmail))
                .getBytes(StandardCharsets.UTF_8),
            timeout,
            null);
    if (response.status() != 200)
      throw new GatewayException(
          502, "upstream_error", "Judge.me 未返回预期 HTTP 200；请核对平台记录，勿自动重发", false);
    // HTTP success is the documented acknowledgement; the response body is unspecified.
    Object data;
    try {
      data =
          response.body().length == 0
              ? Map.of()
              : JsonUtils.parse(new String(response.body(), StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new GatewayException(502, "upstream_error", "Judge.me 未确认回复成功；请核对平台记录，勿自动重发", false);
    }
    if (!(data instanceof Map<?, ?> result)
        || result.get("error") != null
        || result.get("errors") != null
        || Boolean.FALSE.equals(result.get("ok"))
        || Boolean.FALSE.equals(result.get("success")))
      throw new GatewayException(502, "upstream_error", "Judge.me 未确认回复成功；请核对平台记录，勿自动重发", false);
    return JsonUtils.obj(
        "ok",
        true,
        "account",
        account,
        "review_id",
        params.get("review_id"),
        "reply",
        body,
        "send_reply_email",
        sendEmail);
  }

  private Map<String, Object> judgemeReviews(
      String account, Map<String, Object> cursor, int limit, double timeout, String system) {
    String token = cred("judgeme", account, "API_TOKEN", true, system);
    String shop = cred("judgeme", account, "SHOP_DOMAIN", true, system);
    int page = pageNumber(cursor);
    Map<String, Object> data =
        JsonUtils.map(
            http.json(
                "GET",
                UpstreamHttp.query(
                    "https://api.judge.me/api/v1/reviews",
                    JsonUtils.obj(
                        "shop_domain", shop, "api_token", token, "page", page, "per_page", limit)),
                Map.of(),
                null,
                timeout));
    if (!(data.get("reviews") instanceof List))
      throw new GatewayException(502, "upstream_error", "Judge.me 未返回 reviews 列表", false);
    var items =
        JsonUtils.list(data.get("reviews")).stream()
            .map(
                v -> {
                  Map<String, Object> row = JsonUtils.map(v);
                  Map<String, Object> reviewer = JsonUtils.map(row.get("reviewer"));
                  // product_external_id 是 Shopify 商品数字 ID，不是 SKU：Judge.me 列表接口并不返回
                  // product_external_id_type（2026-11-06 实测 epomaker 9329 条），商品身份原样带出去，
                  // 归码由中台对着 Shopify 商品档案做 —— 把商品 ID 塞进 sku 会带歪订单归属。
                  String extId = JsonUtils.str(row, "product_external_id").strip();
                  String extType = JsonUtils.str(row, "product_external_id_type").strip();
                  boolean isSku = "sku".equalsIgnoreCase(extType);
                  // '0' 是店铺级评价（"Judge.me Shop Reviews"）挂的假商品号，不是商品
                  String shopProductId = extId.matches("[1-9][0-9]*") ? extId : null;
                  return JsonUtils.obj(
                      "id", Objects.toString(row.get("id"), ""),
                      "rating", row.get("rating"),
                      "content", Objects.toString(row.get("body"), ""),
                      "customer_email", reviewer.get("email"),
                      "customer_name", reviewer.get("name"),
                      "sku", isSku ? extId : null,
                      "product_id", isSku ? null : shopProductId,
                      "product_handle", row.get("product_handle"),
                      "product_title", row.get("product_title"),
                      "review_date", date10(row.get("created_at")),
                      "deleted", Boolean.TRUE.equals(row.get("deleted")),
                      "raw", row);
                })
            .toList();
    return pageResult(items, page, items.size() == limit);
  }

  // ── trustpilot ──────────────────────────────────────────────────────────────

  private Map<String, Object> trustpilotReviews(
      String account, Map<String, Object> cursor, int limit, double timeout, String system) {
    String unit = cred("trustpilot", account, "BUSINESS_UNIT_ID", true, system);
    String accessToken = cred("trustpilot", account, "ACCESS_TOKEN", false, system);
    String path;
    Map<String, String> authHeaders;
    if (!accessToken.isEmpty()) {
      // private endpoint exposes consumer email and order reference; public endpoint does not
      path = "/v1/private/business-units/" + UpstreamHttp.segment(unit) + "/reviews";
      authHeaders = Map.of("Authorization", "Bearer " + accessToken);
    } else {
      path = "/v1/business-units/" + UpstreamHttp.segment(unit) + "/reviews";
      authHeaders = Map.of("apikey", cred("trustpilot", account, "API_KEY", true, system));
    }
    int page = pageNumber(cursor);
    Map<String, Object> data =
        JsonUtils.map(
            http.json(
                "GET",
                UpstreamHttp.query(
                    "https://api.trustpilot.com" + path,
                    JsonUtils.obj("page", page, "perPage", limit, "orderBy", "createdat.desc")),
                authHeaders,
                null,
                timeout));
    if (!(data.get("reviews") instanceof List))
      throw new GatewayException(502, "upstream_error", "Trustpilot 未返回 reviews 列表", false);
    boolean hasPrivateAccess = !accessToken.isEmpty();
    var items =
        JsonUtils.list(data.get("reviews")).stream()
            .map(
                v -> {
                  Map<String, Object> row = JsonUtils.map(v);
                  Map<String, Object> consumer = JsonUtils.map(row.get("consumer"));
                  return JsonUtils.obj(
                      "id", Objects.toString(row.get("id"), ""),
                      "rating", row.get("stars"),
                      "content", Objects.toString(row.get("text"), ""),
                      "customer_email", hasPrivateAccess ? consumer.get("email") : null,
                      "customer_name", consumer.get("displayName"),
                      "order_no", hasPrivateAccess ? row.get("referenceId") : null,
                      "review_date", date10(row.get("createdAt")),
                      "deleted", Boolean.TRUE.equals(row.get("isDeleted")),
                      "raw", row);
                })
            .toList();
    return pageResult(items, page, items.size() == limit);
  }

  // ── stripe disputes ─────────────────────────────────────────────────────────

  private Map<String, Object> stripeDisputes(
      String account, Map<String, Object> cursor, int limit, double timeout, String system) {
    String key = cred("stripe", account, "API_KEY", true, system);
    var params = new LinkedHashMap<String, Object>();
    params.put("limit", limit);
    String startingAfter = JsonUtils.str(cursor, "starting_after").strip();
    if (!startingAfter.isEmpty()) params.put("starting_after", startingAfter);
    Map<String, Object> data =
        JsonUtils.map(
            http.json(
                "GET",
                UpstreamHttp.query("https://api.stripe.com/v1/disputes", params),
                Map.of("Authorization", "Bearer " + key),
                null,
                timeout));
    if (!(data.get("data") instanceof List))
      throw new GatewayException(502, "upstream_error", "Stripe 未返回 data 列表", false);
    var items =
        JsonUtils.list(data.get("data")).stream()
            .map(
                v -> {
                  Map<String, Object> row = JsonUtils.map(v);
                  Map<String, Object> evidence = JsonUtils.map(row.get("evidence"));
                  Map<String, Object> metadata = JsonUtils.map(row.get("metadata"));
                  Map<String, Object> evidenceDetails = JsonUtils.map(row.get("evidence_details"));
                  return JsonUtils.obj(
                      "id", Objects.toString(row.get("id"), ""),
                      "amount", majorAmount(row.get("amount"), row.get("currency")),
                      "currency",
                          Objects.toString(row.get("currency"), "").toUpperCase(Locale.ROOT),
                      "reason", row.get("reason"),
                      "status", row.get("status"),
                      "customer_email", evidence.get("customer_email_address"),
                      "customer_name", evidence.get("customer_name"),
                      "order_no", metadata.get("order_number"),
                      "deadline", epochDate(evidenceDetails.get("due_by")),
                      "raw", row);
                })
            .toList();
    boolean more = Boolean.TRUE.equals(data.get("has_more"));
    if (more && items.isEmpty())
      throw new GatewayException(502, "upstream_error", "Stripe 声明下一页但未返回可用游标", false);
    String nextCursor = more ? Objects.toString(items.getLast().get("id"), "") : "";
    return JsonUtils.obj(
        "items", items, "has_more", more, "cursor", JsonUtils.obj("starting_after", nextCursor));
  }

  // ── paypal disputes ─────────────────────────────────────────────────────────

  private Map<String, Object> paypalDisputes(
      String account, Map<String, Object> cursor, int limit, double timeout, String system) {
    String clientId = cred("paypal", account, "CLIENT_ID", true, system);
    String secret = cred("paypal", account, "CLIENT_SECRET", true, system);
    String environment = cred("paypal", account, "ENV", false, system);
    if (environment.isEmpty()) environment = "sandbox";
    if (!Set.of("sandbox", "live").contains(environment))
      throw GatewayException.invalid("PayPal ENV 仅支持 sandbox 或 live");
    String base =
        environment.equals("sandbox")
            ? "https://api-m.sandbox.paypal.com"
            : "https://api-m.paypal.com";

    String basic =
        Base64.getEncoder()
            .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    Map<String, Object> auth =
        JsonUtils.map(
            http.form(
                base + "/v1/oauth2/token",
                Map.of("grant_type", "client_credentials"),
                Map.of("Authorization", "Basic " + basic)));
    String accessToken = JsonUtils.str(auth, "access_token").strip();
    if (accessToken.isEmpty())
      throw new GatewayException(502, "upstream_error", "PayPal 未返回访问令牌", false);
    Map<String, String> headers = Map.of("Authorization", "Bearer " + accessToken);

    int page = pageNumber(cursor);
    int size = Math.min(limit, 50);
    Map<String, Object> data =
        JsonUtils.map(
            http.json(
                "GET",
                UpstreamHttp.query(
                    base + "/v1/customer/disputes", JsonUtils.obj("page", page, "page_size", size)),
                headers,
                null,
                timeout));
    if (!(data.get("items") instanceof List))
      throw new GatewayException(502, "upstream_error", "PayPal 未返回 items 列表", false);

    var items = new ArrayList<>();
    for (Object v : JsonUtils.list(data.get("items"))) {
      String disputeId = Objects.toString(JsonUtils.map(v).get("dispute_id"), "").strip();
      if (disputeId.isEmpty())
        throw new GatewayException(502, "upstream_error", "PayPal 争议条目缺少 dispute_id", false);
      // The summary omits buyer context and evidence deadline; fetch detail per item.
      Map<String, Object> row =
          JsonUtils.map(
              http.json(
                  "GET",
                  base + "/v1/customer/disputes/" + UpstreamHttp.segment(disputeId),
                  headers,
                  null,
                  timeout));
      Object transactionRaw =
          JsonUtils.list(row.get("disputed_transactions")).stream().findFirst().orElse(null);
      Map<String, Object> transaction =
          transactionRaw != null ? JsonUtils.map(transactionRaw) : Map.of();
      Map<String, Object> buyer = JsonUtils.map(transaction.get("buyer"));
      Map<String, Object> amount = JsonUtils.map(row.get("dispute_amount"));
      items.add(
          JsonUtils.obj(
              "id", Objects.toString(row.get("dispute_id"), ""),
              "amount", amount.get("value"),
              "currency", amount.get("currency_code"),
              "reason", row.get("reason"),
              "status", row.get("status"),
              "customer_email", buyer.get("email"),
              "customer_name", buyer.get("name"),
              "order_no", transaction.get("invoice_number"),
              "deadline", row.get("seller_response_due_date"),
              "raw", row));
    }
    boolean more =
        JsonUtils.list(data.get("links")).stream()
            .map(JsonUtils::map)
            .anyMatch(link -> "next".equals(link.get("rel")));
    return pageResult(items, page, more);
  }

  // ── RD product catalog ──────────────────────────────────────────────────────

  private Map<String, Object> rdProducts(
      String account, Map<String, Object> cursor, double timeout, String system) {
    String base = cred("rd", account, "BASE_URL", true, system);
    base = base.replaceAll("/+$", ""); // rstrip('/')
    validateRdUrl(base);
    String token = cred("rd", account, "ACCESS_TOKEN", false, system);
    var params = new LinkedHashMap<String, Object>();
    String updatedAt = JsonUtils.str(cursor, "updated_at").strip();
    if (!updatedAt.isEmpty()) params.put("updated_since", updatedAt);
    Map<String, Object> data =
        JsonUtils.map(
            http.json(
                "GET",
                UpstreamHttp.query(base + "/api/products/sync-out/catalog", params),
                token.isEmpty() ? Map.of() : Map.of("Authorization", "Bearer " + token),
                null,
                timeout));
    if (!Boolean.TRUE.equals(data.get("success")) || !(data.get("data") instanceof List))
      throw new GatewayException(502, "upstream_error", "RD 未返回已确认的产品目录 data 列表", false);
    // Commerce consumers do not need pricing/financial fields from this catalog.
    var items =
        JsonUtils.list(data.get("data")).stream()
            .map(
                v -> {
                  Map<String, Object> row = JsonUtils.map(v);
                  return JsonUtils.obj(
                      "id", Objects.toString(row.get("product_id"), ""),
                      "sku", orFirst(row.get("mabang_sku"), row.get("sku")),
                      "brand", row.get("brand"),
                      "name", row.get("name"),
                      "model", row.get("model_number"),
                      "description", row.get("description"),
                      "status", row.get("status"),
                      "image_url", row.get("main_image_url"),
                      "updated_at", row.get("updated_at"));
                })
            .toList();
    var nextCursor = new LinkedHashMap<>(cursor);
    String latest =
        items.stream()
            .map(item -> item.get("updated_at"))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .filter(s -> !s.isEmpty())
            .max(String::compareTo)
            .orElse(null);
    if (latest != null && (updatedAt.isEmpty() || latest.compareTo(updatedAt) > 0))
      nextCursor.put("updated_at", latest);
    return JsonUtils.obj("items", items, "has_more", false, "cursor", nextCursor);
  }

  private void validateRdUrl(String base) {
    URI uri;
    try {
      uri = URI.create(base);
    } catch (IllegalArgumentException e) {
      throw GatewayException.invalid("RD 地址格式无效");
    }
    String scheme = uri.getScheme();
    if (scheme == null) throw GatewayException.invalid("RD 地址须为 HTTPS 或本机测试 HTTP 地址，不能包含认证或查询参数");
    if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
      throw GatewayException.invalid("RD 地址须为 HTTPS 或本机测试 HTTP 地址，不能包含认证或查询参数");
    if (scheme.equalsIgnoreCase("https")) return;
    if (scheme.equalsIgnoreCase("http") && isLoopback(uri.getHost())) return;
    throw GatewayException.invalid("RD 地址须为 HTTPS 或本机测试 HTTP 地址，不能包含认证或查询参数");
  }

  /** Matches Python's ipaddress.ip_address(host).is_loopback — only literal IPs, no DNS. */
  private static boolean isLoopback(String host) {
    if (host == null) return false;
    if (host.equalsIgnoreCase("localhost")) return true;
    boolean literal =
        host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") || host.contains(":");
    if (!literal) return false;
    try {
      return java.net.InetAddress.getByName(host).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }
}

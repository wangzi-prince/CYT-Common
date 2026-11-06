package group.cyt.common.module.adapter.lingxing;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.adapter.support.ErpProxy;
import group.cyt.common.module.adapter.support.SignatureSupport;
import group.cyt.common.module.adapter.support.TestWritePolicy;
import group.cyt.common.module.adapter.support.TokenCache;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Lingxing ERP. Every request is signed with an AES key that is the app id itself, so the body that
 * is signed and the body that is sent must be the same object — see {@link #clean}.
 */
@Component
public class LingxingAdapter implements PlatformAdapter {
  private static final String BASE = "https://openapi.lingxing.com";
  private static final String TOKEN_PATH = "/api/auth-server/oauth/access-token";

  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final ErpProxy proxy;
  private final TestWritePolicy writePolicy;
  private final TokenCache tokenCache;

  public LingxingAdapter(
      CredentialService credentials,
      UpstreamHttp http,
      ErpProxy proxy,
      TestWritePolicy writePolicy,
      TokenCache tokenCache) {
    this.credentials = credentials;
    this.http = http;
    this.proxy = proxy;
    this.writePolicy = writePolicy;
    this.tokenCache = tokenCache;
  }

  public boolean supports(String platform) {
    return platform.equals("lingxing");
  }

  // ── 账号与凭证 ────────────────────────────────────────────────────────

  static String accountKey(String name) {
    return CredentialService.accountKey(name);
  }

  private record Cred(String name, String appId, String appSecret, String mailEmail) {
    boolean ok() {
      return !appId.isEmpty() && !appSecret.isEmpty();
    }
  }

  /**
   * Single-account environments store {@code LINGXING_APP_ID} with no account prefix. The fallback
   * is only allowed for the default account: falling back in a multi-account setup would silently
   * serve account B's data to a caller that asked for account A.
   */
  private String credValue(String keyPart, String field, String system) {
    String scoped = credentials.resolve("LINGXING_" + keyPart + "_" + field, system).strip();
    if (!scoped.isEmpty()) return scoped;
    if (keyPart.isEmpty() || keyPart.equals("DEFAULT"))
      return credentials.resolve("LINGXING_" + field, system).strip();
    return "";
  }

  private String account(String name, String system) {
    if (name != null && !name.isBlank()) return name.strip();
    String configured = credentials.resolve("LINGXING_ACCOUNT", system).strip();
    return configured.isEmpty() ? "default" : configured;
  }

  private Cred cred(String name, String system) {
    String account = account(name, system);
    String key = accountKey(account);
    Cred cred =
        new Cred(
            account,
            credValue(key, "APP_ID", system),
            credValue(key, "APP_SECRET", system),
            credValue(key, "MAIL_EMAIL", system));
    if (!cred.ok())
      throw GatewayException.config(
          "领星账号 "
              + account
              + " 凭证缺失：需要 LINGXING_"
              + key
              + "_APP_ID 和 LINGXING_"
              + key
              + "_APP_SECRET（领星 ERP → 设置 → 业务配置 → 全局 → 开放接口）");
    return cred;
  }

  private String proxyBase(String system) {
    String own = credentials.resolve("LINGXING_PROXY_BASE", system).strip();
    return own.isEmpty() ? credentials.resolve("ERP_PROXY_BASE", system).strip() : own;
  }

  private String proxyToken(String system) {
    return credentials.resolve("LINGXING_PROXY_TOKEN", system).strip();
  }

  // ── 出口 ──────────────────────────────────────────────────────────────

  private Object send(
      String method,
      String path,
      Map<String, Object> params,
      byte[] content,
      Map<String, String> headers,
      String system) {
    String base = proxyBase(system), token = proxyToken(system);
    if (ErpProxy.active(base, token))
      return proxy.request("lingxing", method, path, base, token, params, content, headers, 30);
    String url =
        params == null || params.isEmpty()
            ? BASE + path
            : UpstreamHttp.query(BASE + path, flattenForQuery(params));
    return readBody(http.raw(method, url, headers, content, 30, null));
  }

  private static Object readBody(byte[] body) {
    String text = new String(body, StandardCharsets.UTF_8);
    try {
      return JsonUtils.read(text);
    } catch (RuntimeException e) {
      throw new GatewayException(502, "upstream_error", "领星返回非 JSON 响应: " + head(text), false);
    }
  }

  private static String head(String text) {
    return text.strip().replace("\n", " ");
  }

  private static Map<String, Object> flattenForQuery(Map<String, Object> params) {
    Map<String, Object> out = new LinkedHashMap<>();
    params.forEach(
        (k, v) -> {
          if (v instanceof Map || v instanceof Collection) out.put(k, JsonUtils.write(v));
          else out.put(k, v);
        });
    return out;
  }

  /**
   * appId/appSecret → access_token, cached per account.
   *
   * <p>The two egress routes put the credentials in <em>different</em> places: direct calls must be
   * {@code multipart/form-data} (urlencoded gets a 415), while the relay only forwards path and
   * query. Each is proven in production; neither is a guess.
   */
  private String accessToken(Cred cred, boolean refresh, String system) {
    return tokenCache.get("lingxing:" + cred.name(), refresh, () -> fetchToken(cred, system));
  }

  private Map<String, Object> fetchToken(Cred cred, String system) {
    Map<String, Object> body =
        JsonUtils.map(
            ErpProxy.active(proxyBase(system), proxyToken(system))
                ? send(
                    "POST",
                    TOKEN_PATH,
                    JsonUtils.obj("appId", cred.appId(), "appSecret", cred.appSecret()),
                    null,
                    Map.of(),
                    system)
                : directTokenExchange(cred));
    // The token endpoint reports success as the string "200", unlike business calls' integer 0.
    if (!JsonUtils.str(body, "code").equals("200"))
      throw new GatewayException(
          502,
          "upstream_error",
          "领星取 token 失败: code=" + body.get("code") + " msg=" + body.get("msg"),
          false);
    Map<String, Object> data = new LinkedHashMap<>(JsonUtils.map(body.get("data")));
    String token = JsonUtils.str(data, "access_token").strip();
    if (token.isEmpty())
      throw new GatewayException(
          502, "auth_failed", "领星 token 响应里没有 access_token: " + head(JsonUtils.write(body)), false);
    if (JsonUtils.integer(data.get("expires_in"), 0) <= 0) data.put("expires_in", 7199);
    return data;
  }

  private Object directTokenExchange(Cred cred) {
    String multipart =
        "--cyt-boundary\r\nContent-Disposition: form-data; name=\"appId\"\r\n\r\n"
            + cred.appId()
            + "\r\n--cyt-boundary\r\nContent-Disposition: form-data; name=\"appSecret\"\r\n\r\n"
            + cred.appSecret()
            + "\r\n--cyt-boundary--\r\n";
    try {
      return readBody(
          http.raw(
              "POST",
              BASE + TOKEN_PATH,
              Map.of("Content-Type", "multipart/form-data; boundary=cyt-boundary"),
              multipart.getBytes(StandardCharsets.UTF_8),
              20,
              null));
    } catch (GatewayException e) {
      if (e.type.equals("upstream_error") || e.type.equals("upstream_timeout"))
        throw new GatewayException(
            502,
            "upstream_error",
            "领星取 token 失败：连不上 "
                + BASE
                + "（"
                + e.type
                + "）。两种可能，都不是凭证问题：① 调用方公网 IP 没加进领星后台白名单"
                + "（设置 → 业务配置 → 全局 → 开放接口）；② 本机网络到该域名不通。"
                + "配 LINGXING_PROXY_BASE + LINGXING_PROXY_TOKEN 走 erp-proxy 可绕过。",
            true);
      throw e;
    }
  }

  // ── 签名与调用 ────────────────────────────────────────────────────────

  /**
   * Drops nulls and empty strings at every depth. The vendor treats an empty value as absent when
   * it recomputes the signature, so a body that is only cleaned at the top level produces {@code
   * 2001006 api sign not correct}. Zero, false and empty arrays are meaningful and stay.
   */
  static Map<String, Object> clean(Map<String, Object> in) {
    Map<String, Object> out = new LinkedHashMap<>();
    in.forEach(
        (key, value) -> {
          if (value == null || value.equals("")) return;
          if (value instanceof Map<?, ?> nested) out.put(key, clean(JsonUtils.map(nested)));
          else if (value instanceof List<?> items)
            out.put(
                key,
                items.stream()
                    .map(x -> x instanceof Map<?, ?> m ? clean(JsonUtils.map(m)) : x)
                    .toList());
          else out.put(key, value);
        });
    return out;
  }

  private static Object omitEmpty(Collection<?> value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private Map<String, Object> signQuery(Cred cred, String token, Map<String, Object> params) {
    long ts = Instant.now().getEpochSecond();
    Map<String, Object> input = new LinkedHashMap<>(params);
    input.put("access_token", token);
    input.put("app_key", cred.appId());
    input.put("timestamp", ts);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("app_key", cred.appId());
    out.put("timestamp", ts);
    out.put("sign", SignatureSupport.lingxing(cred.appId(), input));
    out.putAll(params);
    return out;
  }

  private static final class TokenExpired extends RuntimeException {}

  private Map<String, Object> post(
      String accountName, String path, Map<String, Object> body, String system) {
    Cred cred = cred(accountName, system);
    Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
    String raw = payload.isEmpty() ? null : JsonUtils.write(JsonUtils.sorted(payload));
    for (int attempt = 0; ; attempt++) {
      String token = accessToken(cred, attempt > 0, system);
      Map<String, Object> params = signQuery(cred, token, payload);
      Object out =
          send(
              "POST",
              path,
              params,
              raw == null ? null : raw.getBytes(StandardCharsets.UTF_8),
              Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json"),
              system);
      if (!(out instanceof Map))
        throw new GatewayException(
            502, "upstream_error", "领星返回非 JSON 对象: " + String.valueOf(out).substring(0, 20), false);
      try {
        bizError(JsonUtils.map(out));
        return JsonUtils.map(out);
      } catch (TokenExpired e) {
        // A token revoked elsewhere still reads as valid locally until it expires; re-fetch once.
        if (attempt > 0) throw e;
      }
    }
  }

  private Map<String, Object> get(
      String accountName, String path, Map<String, Object> query, String system) {
    Cred cred = cred(accountName, system);
    Map<String, Object> params0 = clean(query == null ? Map.of() : query);
    for (int attempt = 0; ; attempt++) {
      String token = accessToken(cred, attempt > 0, system);
      Map<String, Object> params = signQuery(cred, token, params0);
      Object out =
          send("GET", path, params, null, Map.of("Authorization", "Bearer " + token), system);
      if (!(out instanceof Map))
        throw new GatewayException(502, "upstream_error", "领星返回非 JSON 对象", false);
      try {
        bizError(JsonUtils.map(out));
        return JsonUtils.map(out);
      } catch (TokenExpired e) {
        if (attempt > 0) throw e;
      }
    }
  }

  private void bizError(Map<String, Object> body) {
    Object code = body.get("code");
    if (Objects.equals(code, 0) || "0".equals(String.valueOf(code))) return;
    String msg =
        JsonUtils.str(body, "message").isEmpty()
            ? JsonUtils.str(body, "msg")
            : JsonUtils.str(body, "message");
    Object details = body.get("error_details");
    if (details != null)
      msg +=
          " error_details="
              + String.valueOf(details)
                  .substring(0, Math.min(300, String.valueOf(details).length()));
    int numeric = JsonUtils.integer(code, -1);
    if (numeric == 3001008)
      throw new GatewayException(
          429, "rate_limited", "领星限流（令牌桶空了）：" + msg + "。限流维度是 appId + 接口，稍后重试。", true);
    if (numeric == 2001003) throw new TokenExpired();
    if (numeric == 3001022 || numeric == 3001025)
      throw new GatewayException(
          502, "upstream_error", "领星 token 传递有问题（code=" + code + "）：" + msg, false);
    throw new GatewayException(502, "upstream_error", "领星返回错误 code=" + code + ": " + msg, false)
        .code(String.valueOf(code));
  }

  // ── 分发 ──────────────────────────────────────────────────────────────

  public Object invoke(OperationDefinition op, Map<String, Object> p, String system) {
    String key = op.key().substring("lingxing.".length());
    String account = JsonUtils.str(p, "account");
    return switch (key) {
      case "aftersales.reasons" -> aftersalesReasons(op, account, p, system);
      case "messages.list" -> messagesList(op, account, p, system);
      case "messages.detail" ->
          data(
              JsonUtils.map(
                  post(
                      account,
                      "/erp/sc/data/mail/detail",
                      JsonUtils.map(JsonUtils.obj("webmail_uuid", p.get("webmail_uuid"))),
                      system)));
      case "workorder.add" ->
          rawAndRequestId(
              post(
                  account,
                  "/basicOpen/customerService/workOrder/add",
                  workOrderPayload(p),
                  system));
      case "workorder.audit" ->
          rawAndRequestId(
              post(
                  account,
                  "/basicOpen/customerService/workOrder/auditFlow/summit",
                  JsonUtils.map(
                      JsonUtils.obj(
                          "businessIdList", p.get("businessIdList"), "flowId", p.get("flowId"))),
                  system));
      case "listing.list" ->
          rawAndTotal(
              post(
                  account,
                  "/erp/sc/data/mws/listing",
                  JsonUtils.map(
                      JsonUtils.obj(
                          "sid",
                          p.get("sid"),
                          "search_field",
                          p.get("search_field"),
                          "search_value",
                          p.get("search_value"),
                          "offset",
                          p.get("offset"),
                          "length",
                          p.get("length"))),
                  system));
      case "warehouse.list" ->
          rawAndTotal(
              post(
                  account,
                  "/erp/sc/data/local_inventory/warehouse",
                  JsonUtils.map(
                      JsonUtils.obj(
                          "type",
                          p.get("type"),
                          "sub_type",
                          p.get("sub_type"),
                          "is_delete",
                          p.get("is_delete"),
                          "offset",
                          p.get("offset"),
                          "length",
                          p.get("length"))),
                  system));
      case "seller.list" -> sellerList(account, system);
      case "orders.list" -> ordersList(account, p, system);
      case "orders.detail" -> ordersDetail(account, p, system);
      case "refund.create" -> refundCreate(account, p, system);
      case "inventory.list" ->
          rawAndTotal(
              post(
                  account,
                  "/erp/sc/routing/data/local_inventory/inventoryDetails",
                  clean(
                      JsonUtils.map(
                          JsonUtils.obj(
                              "wid",
                              p.get("wid"),
                              "sku",
                              p.get("sku"),
                              "offset",
                              p.get("offset"),
                              "length",
                              p.get("length")))),
                  system));
      case "inventory.fba" ->
          rawAndTotal(
              post(
                  account,
                  "/basicOpen/openapi/storage/fbaWarehouseDetail",
                  clean(
                      JsonUtils.map(
                          JsonUtils.obj(
                              "offset",
                              p.get("offset"),
                              "length",
                              p.get("length"),
                              "search_field",
                              p.get("search_field"),
                              "search_value",
                              p.get("search_value"),
                              "sid",
                              p.get("sid"),
                              "fulfillment_channel_type",
                              p.get("fulfillment_channel_type"),
                              "is_hide_zero_stock",
                              p.get("is_hide_zero_stock")))),
                  system));
      case "workorder.list" -> workOrderList(account, p, system);
      case "mporders.list" -> mpordersList(account, p, system);
      case "return.qty" -> returnQty(account, p, system);
      case "product.list" -> productList(account, p, system);
      default -> throw GatewayException.config("未知领星操作: " + op.key());
    };
  }

  private static Map<String, Object> workOrderPayload(Map<String, Object> p) {
    var result = new LinkedHashMap<String, Object>();
    for (String field :
        List.of(
            "platformOrderNos",
            "afterInfoList",
            "amountCurrency",
            "createManualWork",
            "cutOff",
            "globalOrderNo",
            "globalPurchaseTime",
            "hasPrediction",
            "logisticsProvider",
            "logisticsType",
            "platformCode",
            "preArrivalTime",
            "reasonUuid",
            "remark",
            "salesType",
            "siteCode",
            "source",
            "storeId",
            "sid",
            "tagList",
            "trackingNo",
            "type",
            "wid",
            "workOrderSupplement",
            "returnWid",
            "returnWidDesc",
            "logisticsTypeCode",
            "logisticsProviderDesc",
            "deliveryTime",
            "globalTrackNo",
            "logisticsTypeId",
            "platform",
            "waybillNo",
            "wpType")) if (p.get(field) != null) result.put(field, p.get(field));
    return result;
  }

  // ── 各操作 ────────────────────────────────────────────────────────────

  /** Object-wrapped page, unlike the other endpoints where data is the array itself. */
  private Object aftersalesReasons(
      OperationDefinition op, String account, Map<String, Object> p, String system) {
    var out =
        post(
            account,
            "/basicOpen/customerService/afterSalesReason/list",
            JsonUtils.map(JsonUtils.obj("offset", p.get("offset"), "length", p.get("length"))),
            system);
    Map<String, Object> data = JsonUtils.map(out.get("data"));
    List<Object> rows = JsonUtils.list(data.get("list"));
    var items =
        rows.stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            .map(
                r ->
                    JsonUtils.obj(
                        "reason_id",
                        r.get("reasonId"),
                        "cause",
                        r.get("cause"),
                        "create_user",
                        r.get("createUser"),
                        "created_at",
                        r.get("gmtCreate")))
            .toList();
    Object total = data.get("total");
    String digits = String.valueOf(total == null ? "" : total);
    return JsonUtils.obj(
        "items",
        items,
        "total",
        digits.matches("\\d+") ? Integer.parseInt(digits) : items.size(),
        "request_id",
        out.get("request_id"));
  }

  private Object messagesList(
      OperationDefinition op, String account, Map<String, Object> p, String system) {
    Object cursor = p.get("cursor");
    int offset = 0;
    String cursorStart = "", cursorEnd = "";
    if (cursor instanceof Map<?, ?> c) {
      Map<String, Object> cm = JsonUtils.map(c);
      try {
        offset = Integer.parseInt(String.valueOf(cm.getOrDefault("offset", "0")));
      } catch (NumberFormatException ignored) {
        offset = 0;
      }
      cursorStart = JsonUtils.str(cm, "start_date");
      cursorEnd = JsonUtils.str(cm, "end_date");
    } else {
      try {
        offset = Integer.parseInt(String.valueOf(cursor == null ? "0" : cursor));
      } catch (NumberFormatException ignored) {
        offset = 0;
      }
    }

    String[] window =
        defaultWindow(
            JsonUtils.str(p, "start_date").isEmpty() ? cursorStart : JsonUtils.str(p, "start_date"),
            JsonUtils.str(p, "end_date").isEmpty() ? cursorEnd : JsonUtils.str(p, "end_date"));

    String email = JsonUtils.str(p, "email").strip();
    if (email.isEmpty()) email = cred(account, system).mailEmail();
    if (email.isEmpty())
      throw GatewayException.config(
          "领星站内信接口强制要求「店铺绑定邮箱」，未提供。请配置 LINGXING_"
              + accountKey(account(account, system))
              + "_MAIL_EMAIL，或在调用时传 email。");

    String flag = JsonUtils.str(p, "flag", "receive");
    boolean receive = !flag.equals("sent");
    var out =
        post(
            account,
            "/erp/sc/data/mail/lists",
            JsonUtils.map(
                JsonUtils.obj(
                    "flag",
                    flag,
                    "email",
                    email,
                    "start_date",
                    window[0],
                    "end_date",
                    window[1],
                    "offset",
                    offset,
                    "length",
                    p.get("limit"))),
            system);
    List<Object> rows = JsonUtils.list(out.get("data"));
    int total = JsonUtils.integer(out.get("total"), 0);
    var items =
        rows.stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            .map(
                m ->
                    JsonUtils.obj(
                        "id",
                        String.valueOf(m.getOrDefault("webmail_uuid", "")),
                        "subject",
                        m.getOrDefault("subject", ""),
                        "customer_email",
                        m.get(receive ? "from_address" : "to_address"),
                        "customer_name",
                        m.get(receive ? "from_name" : "to_name"),
                        "body",
                        "",
                        "flag",
                        flag,
                        "from_address",
                        m.get("from_address"),
                        "to_address",
                        m.get("to_address"),
                        "date",
                        m.get("date"),
                        "mail_sn_code",
                        m.get("mail_sn_code"),
                        "has_attachment",
                        m.get("has_attachment")))
            .toList();
    int next = offset + rows.size();
    return JsonUtils.obj(
        "items",
        items,
        "has_more",
        !rows.isEmpty() && next < total,
        "cursor",
        JsonUtils.obj("offset", next, "start_date", window[0], "end_date", window[1]));
  }

  /** Lingxing requires both dates; the window travels with the cursor so paging cannot drift. */
  private static String[] defaultWindow(String start, String end) {
    LocalDate endDay;
    try {
      endDay =
          end == null || end.isBlank()
              ? LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
              : LocalDate.parse(end.strip().substring(0, 10));
    } catch (RuntimeException e) {
      throw GatewayException.invalid("领星邮件结束日期无效，请使用 YYYY-MM-DD");
    }
    LocalDate startDay;
    if (start == null || start.isBlank()) startDay = endDay.minusDays(30);
    else {
      try {
        startDay = LocalDate.parse(start.strip().substring(0, 10));
      } catch (RuntimeException e) {
        throw GatewayException.invalid("领星邮件开始日期无效，请使用 YYYY-MM-DD");
      }
    }
    if (startDay.isAfter(endDay)) throw GatewayException.invalid("领星邮件开始日期不能晚于结束日期");
    if (startDay.isBefore(endDay.minusDays(365)))
      throw GatewayException.invalid("领星邮件单次查询范围不能超过 365 天，请分批查询");
    return new String[] {startDay.toString(), endDay.toString()};
  }

  private Object data(Map<String, Object> out) {
    Map<String, Object> d = JsonUtils.map(out.get("data"));
    return JsonUtils.obj(
        "webmail_uuid",
        d.get("webmail_uuid"),
        "subject",
        d.get("subject"),
        "from_address",
        d.get("from_address"),
        "to_address_all",
        d.get("to_address_all"),
        "date",
        d.get("date"),
        "text_html",
        d.get("text_html"),
        "text_plain",
        d.get("text_plain"),
        "attachments",
        d.getOrDefault("attachments", List.of()),
        "type",
        d.get("type"),
        "mail_sn_code",
        d.get("mail_sn_code"));
  }

  private static Map<String, Object> rawAndRequestId(Map<String, Object> out) {
    return JsonUtils.map(
        JsonUtils.obj("raw", out.get("data"), "request_id", out.get("request_id")));
  }

  private static Map<String, Object> rawAndTotal(Map<String, Object> out) {
    return JsonUtils.map(JsonUtils.obj("raw", out.get("data"), "total", out.get("total")));
  }

  private Object sellerList(String account, String system) {
    List<Object> rows =
        JsonUtils.list(get(account, "/erp/sc/data/seller/lists", Map.of(), system).get("data"));
    var items =
        rows.stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            .map(
                r ->
                    JsonUtils.obj(
                        "sid",
                        r.get("sid"),
                        "name",
                        r.get("name") == null ? r.get("seller_name") : r.get("name"),
                        "marketplace",
                        r.get("marketplace") == null ? r.get("country") : r.get("marketplace"),
                        "status",
                        r.get("status"),
                        "raw",
                        r))
            .toList();
    return JsonUtils.obj("items", items, "total", items.size());
  }

  private Object ordersList(String account, Map<String, Object> p, String system) {
    var out =
        post(
            account,
            "/erp/sc/data/mws/orders",
            clean(
                JsonUtils.map(
                    JsonUtils.obj(
                        "start_date",
                        p.get("start_date"),
                        "end_date",
                        p.get("end_date"),
                        "date_type",
                        p.get("date_type"),
                        "sid",
                        p.get("sid"),
                        "sid_list",
                        omitEmpty(JsonUtils.list(p.get("sid_list"))),
                        "order_status",
                        omitEmpty(JsonUtils.list(p.get("order_status"))),
                        "sort_desc_by_date_type",
                        p.get("sort_desc_by_date_type"),
                        "fulfillment_channel",
                        p.get("fulfillment_channel"),
                        "offset",
                        p.get("offset"),
                        "length",
                        p.get("length")))),
            system);
    var items =
        JsonUtils.list(out.get("data")).stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            .map(LingxingAdapter::orderRow)
            .toList();
    return JsonUtils.obj(
        "items",
        items,
        "total",
        out.get("total") == null ? items.size() : JsonUtils.integer(out.get("total"), items.size()),
        "request_id",
        out.get("request_id"));
  }

  private static Map<String, Object> orderRow(Map<String, Object> m) {
    var items =
        JsonUtils.list(m.get("item_list")).stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            .map(
                it ->
                    JsonUtils.obj(
                        "asin",
                        it.get("asin"),
                        "msku",
                        it.get("seller_sku"),
                        "sku",
                        it.get("local_sku"),
                        "title",
                        it.get("local_name"),
                        "qty",
                        it.get("quantity_ordered")))
            .toList();
    return JsonUtils.map(
        JsonUtils.obj(
            "order_no",
            m.get("amazon_order_id"),
            "sid",
            m.get("sid"),
            "shop",
            m.get("seller_name"),
            "status",
            m.get("order_status"),
            "amount",
            m.get("order_total_amount"),
            "currency",
            m.get("order_total_currency_code"),
            "channel",
            m.get("fulfillment_channel"),
            "marketplace",
            m.get("sales_channel"),
            "tracking_no",
            m.get("tracking_number"),
            "refund_status",
            m.get("is_return"),
            "refund_amount",
            m.get("refund_amount"),
            "is_return_order",
            m.get("is_return_order"),
            "buyer_postcode",
            m.get("postal_code"),
            "purchase_at",
            m.get("purchase_date_local"),
            "ship_at",
            m.get("shipment_date_local"),
            "updated_at",
            m.get("last_update_date"),
            "items",
            items,
            "raw",
            m));
  }

  private Object ordersDetail(String account, Map<String, Object> p, String system) {
    var out =
        post(
            account,
            "/erp/sc/data/mws/orderDetail",
            JsonUtils.map(JsonUtils.obj("order_id", p.get("order_id"))),
            system);
    Object data = out.get("data");
    List<Object> rows =
        data instanceof List
            ? JsonUtils.list(data)
            : (data instanceof Map ? List.of(data) : List.of());
    return JsonUtils.obj("items", rows, "total", rows.size(), "request_id", out.get("request_id"));
  }

  /** Real money: allowed only for accounts on the test-write whitelist. */
  private Object refundCreate(String account, Map<String, Object> p, String system) {
    writePolicy.requireTarget("lingxing", account(account, system));
    var out =
        post(
            account,
            "/basicOpen/openapi/salesOrder/refundOrder",
            clean(
                JsonUtils.map(
                    JsonUtils.obj(
                        "sid",
                        p.get("sid"),
                        "amazonOrderId",
                        p.get("amazonOrderId"),
                        "purchaseDateLocal",
                        p.get("purchaseDateLocal"),
                        "data",
                        omitEmpty(JsonUtils.list(p.get("data")))))),
            system);
    return rawAndRequestId(out);
  }

  private Object workOrderList(String account, Map<String, Object> p, String system) {
    Map<String, Object> out =
        post(
            account,
            "/pb/mp/returns/workOrder/list",
            JsonUtils.map(
                JsonUtils.obj(
                    "date_type",
                    p.get("date_type"),
                    "start_time",
                    p.get("start_time"),
                    "end_time",
                    p.get("end_time"),
                    "offset",
                    p.get("offset"),
                    "length",
                    p.get("length"))),
            system);
    Map<String, Object> data = JsonUtils.map(out.get("data"));
    return JsonUtils.obj("raw", data, "total", data.get("total"));
  }

  private Object mpordersList(String account, Map<String, Object> p, String system) {
    boolean hasNumbers =
        !JsonUtils.list(p.get("platform_order_nos")).isEmpty()
            || !JsonUtils.list(p.get("platform_order_names")).isEmpty()
            || !JsonUtils.list(p.get("global_order_nos")).isEmpty();
    Map<String, Object> out =
        post(
            account,
            "/pb/mp/order/v2/list",
            clean(
                JsonUtils.map(
                    JsonUtils.obj(
                        "offset",
                        p.get("offset"),
                        "length",
                        p.get("length"),
                        "date_type",
                        hasNumbers ? null : p.get("date_type"),
                        "start_time",
                        hasNumbers ? null : p.get("start_time"),
                        "end_time",
                        hasNumbers ? null : p.get("end_time"),
                        "platform_code",
                        omitEmpty(JsonUtils.list(p.get("platform_code"))),
                        "store_id",
                        omitEmpty(JsonUtils.list(p.get("store_id"))),
                        "platform_order_nos",
                        omitEmpty(JsonUtils.list(p.get("platform_order_nos"))),
                        "platform_order_names",
                        omitEmpty(JsonUtils.list(p.get("platform_order_names"))),
                        "global_order_nos",
                        omitEmpty(JsonUtils.list(p.get("global_order_nos"))),
                        "tracking_nos",
                        omitEmpty(JsonUtils.list(p.get("tracking_nos"))),
                        "order_status",
                        p.get("order_status")))),
            system);
    Map<String, Object> data = JsonUtils.map(out.get("data"));
    return JsonUtils.obj(
        "raw", data, "total", data.get("total"), "request_id", out.get("request_id"));
  }

  private Object returnQty(String account, Map<String, Object> p, String system) {
    Object global = p.get("globalOrderNo");
    List<Object> lines = JsonUtils.list(p.get("orderItemNo"));
    if ((global == null || String.valueOf(global).isBlank()) && lines.isEmpty())
      throw new GatewayException(
          502, "upstream_error", "getReturnQty 需要 globalOrderNo 或 orderItemNo 至少传一个", false);
    Map<String, Object> out =
        post(
            account,
            "/basicOpen/customerService/returnOrder/getReturnQty",
            clean(
                JsonUtils.map(
                    JsonUtils.obj("globalOrderNo", global, "orderItemNo", omitEmpty(lines)))),
            system);
    Map<String, Object> d = JsonUtils.map(out.get("data"));
    return JsonUtils.obj(
        "global_order_no",
        d.get("globalOrderNo"),
        "return_qty",
        d.getOrDefault("returnQty", List.of()),
        "request_id",
        out.get("request_id"));
  }

  private Object productList(String account, Map<String, Object> p, String system) {
    Map<String, Object> out =
        post(
            account,
            "/erp/sc/routing/data/local_inventory/productList",
            clean(
                JsonUtils.map(
                    JsonUtils.obj(
                        "offset",
                        p.get("offset"),
                        "length",
                        p.get("length"),
                        "sku_list",
                        omitEmpty(JsonUtils.list(p.get("sku_list"))),
                        "sku_identifier_list",
                        omitEmpty(JsonUtils.list(p.get("sku_identifier_list"))),
                        "update_time_start",
                        p.get("update_time_start"),
                        "update_time_end",
                        p.get("update_time_end"),
                        "create_time_start",
                        p.get("create_time_start"),
                        "create_time_end",
                        p.get("create_time_end")))),
            system);
    var items =
        JsonUtils.list(out.get("data")).stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            .map(LingxingAdapter::productRow)
            .toList();
    return JsonUtils.obj(
        "items",
        items,
        "total",
        out.get("total") == null
            ? items.size()
            : JsonUtils.integer(out.get("total"), items.size()));
  }

  /** Deliberately without raw: supplier quotes and QC templates make a 1000-row page megabytes. */
  private static Map<String, Object> productRow(Map<String, Object> r) {
    return JsonUtils.map(
        JsonUtils.obj(
            "pid",
            r.get("id"),
            "sku",
            r.get("sku"),
            "sku_identifier",
            r.get("sku_identifier"),
            "name",
            r.get("product_name"),
            "spu",
            r.get("spu"),
            "brand",
            r.get("brand_name"),
            "category",
            r.get("category_name"),
            "status",
            r.get("status"),
            "status_text",
            r.get("status_text"),
            "open_status",
            r.get("open_status"),
            "is_combo",
            r.get("is_combo"),
            "cg_price",
            r.get("cg_price"),
            "pic_url",
            r.get("pic_url"),
            "create_time",
            r.get("create_time"),
            "update_time",
            r.get("update_time")));
  }
}

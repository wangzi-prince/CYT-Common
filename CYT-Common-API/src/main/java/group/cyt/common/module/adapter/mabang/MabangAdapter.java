package group.cyt.common.module.adapter.mabang;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.adapter.support.ErpProxy;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Mabang ERP OpenAPI v2, reached only through the erp-proxy relay: the vendor authenticates and
 * throttles by source IP, so the caller's address is not an option at all.
 *
 * <p>The signature is {@code HMAC-SHA256(secret, body)} over the compact JSON body, and the bytes
 * signed are the bytes sent — any difference in spacing or escaping between the two produces a
 * signature error that reads like a wrong secret.
 */
@Component
public class MabangAdapter implements PlatformAdapter {
  private static final String PATH = "/api/v2";
  private static final Map<String, String> TRACK_STATUS =
      Map.ofEntries(
          Map.entry("1", "未上网"),
          Map.entry("2", "运输中"),
          Map.entry("3", "妥投"),
          Map.entry("4", "异常"),
          Map.entry("5", "包裹退回"),
          Map.entry("6", "未查到"),
          Map.entry("7", "到达待取"),
          Map.entry("8", "投递失败"),
          Map.entry("9", "运输过久"),
          Map.entry("12", "派送途中"));

  /** Terminal-but-bad states the CS side scans for; 妥投 alone counts as delivered. */
  private static final Map<String, String> TRACK_BAD_STATUS =
      Map.of("4", "异常", "5", "包裹退回", "8", "投递失败", "9", "运输过久");

  private final CredentialService credentials;
  private final ErpProxy proxy;

  public MabangAdapter(CredentialService credentials, ErpProxy proxy) {
    this.credentials = credentials;
    this.proxy = proxy;
  }

  public boolean supports(String platform) {
    return platform.equals("mabang");
  }

  private String cfg(String name, String system) {
    return credentials.resolve("MABANG_" + name, system).strip();
  }

  private String proxyBase(String system) {
    String own = cfg("PROXY_BASE", system);
    return own.isEmpty() ? credentials.resolve("ERP_PROXY_BASE", system).strip() : own;
  }

  private String proxyToken(String system) {
    String own = cfg("PROXY_TOKEN", system);
    return own.isEmpty() ? credentials.resolve("ERP_PROXY_TOKEN", system).strip() : own;
  }

  private void checkConfigured(String system) {
    var missing = new ArrayList<String>();
    if (cfg("APP_KEY", system).isEmpty()) missing.add("MABANG_APP_KEY");
    if (cfg("SECRET", system).isEmpty()) missing.add("MABANG_SECRET");
    if (proxyBase(system).isEmpty()) missing.add("MABANG_PROXY_BASE");
    if (proxyToken(system).isEmpty()) missing.add("MABANG_PROXY_TOKEN");
    if (!missing.isEmpty())
      throw GatewayException.config("马帮未配置：缺 " + String.join("、", missing) + "（调用会直接失败，不会走模拟）");
  }

  /** One v2 action, returning its business data section after the success check. */
  private Object call(
      String api, Map<String, Object> data, List<String> emptyMessages, double timeout, String system) {
    checkConfigured(system);
    Map<String, Object> body =
        JsonUtils.obj(
            "api",
            api,
            "appkey",
            cfg("APP_KEY", system),
            "data",
            data,
            "timestamp",
            String.valueOf(Instant.now().getEpochSecond()),
            "version",
            "1");
    String bodyStr = JsonUtils.write(body);
    Object payload =
        proxy.request(
            "mabang",
            "POST",
            PATH,
            proxyBase(system),
            proxyToken(system),
            null,
            bodyStr.getBytes(StandardCharsets.UTF_8),
            Map.of("Authorization", JsonUtils.hmac(cfg("SECRET", system), bodyStr)),
            timeout);
    if (!(payload instanceof Map))
      throw new GatewayException(502, "upstream_error", "马帮响应类型无效（非 JSON 对象）", false);
    Map<String, Object> envelope = JsonUtils.map(payload);
    String code = String.valueOf(envelope.get("code"));
    if (code.equals("200")) return parseData(envelope.get("data"));
    String message = String.valueOf(envelope.getOrDefault("message", "")).strip();
    // "no such order" is a normal answer for these two endpoints, not a failure.
    if (emptyMessages.contains(message)) return null;
    if (message.contains("频繁") || code.equals("429"))
      throw new GatewayException(429, "rate_limited", "马帮限频 code=" + code + " msg=" + message, true);
    throw new GatewayException(
        502, "upstream_error", "马帮业务错误 code=" + code + " msg=" + message, false).code(code);
  }

  /** Mabang sometimes hands back {@code data} as a JSON string instead of an object. */
  private static Object parseData(Object data) {
    if (!(data instanceof String text)) return data;
    try {
      return JsonUtils.parse(text);
    } catch (RuntimeException e) {
      throw new GatewayException(
          502, "upstream_error", "马帮 data 字符串解析失败: " + text.substring(0, Math.min(120, text.length())),
          false);
    }
  }

  private static Map<String, Object> nonNull(Map<String, Object> params) {
    var out = new LinkedHashMap<String, Object>();
    params.forEach((k, v) -> {
      if (v != null) out.put(k, v);
    });
    return out;
  }

  private static Map<String, Object> trackRow(Object value) {
    Map<String, Object> item = new LinkedHashMap<>(JsonUtils.map(value));
    var events =
        JsonUtils.list(item.get("trackInfo")).stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            // the upstream does not guarantee order
            .sorted(Comparator.comparing(t -> String.valueOf(t.getOrDefault("time", ""))))
            .map(t -> JsonUtils.obj("time", t.get("time"), "content", t.get("content")))
            .toList();
    String status = String.valueOf(item.getOrDefault("status", ""));
    item.put("status_text", TRACK_STATUS.getOrDefault(status, ""));
    item.put("status_bad", TRACK_BAD_STATUS.get(status));
    item.put("trackInfo", events);
    return item;
  }

  public Object invoke(OperationDefinition op, Map<String, Object> params, String system) {
    double timeout = op.timeoutSeconds();
    return switch (op.key()) {
      case "mabang.orders.list" -> {
        Object data =
            call("order-get-order-list-new", nonNull(params), List.of(), timeout, system);
        if (data == null) data = Map.of();
        if (data instanceof List<?> rows)
          yield JsonUtils.obj(
              "orders", rows, "total", rows.size(), "has_next", false, "next_cursor", null);
        if (!(data instanceof Map))
          throw new GatewayException(
              502, "upstream_error", "马帮订单列表响应形态异常: " + data.getClass().getSimpleName(), false);
        Map<String, Object> page = JsonUtils.map(data);
        yield JsonUtils.obj(
            "orders", page.getOrDefault("data", List.of()),
            "total", page.get("total"),
            "has_next", JsonUtils.bool(page.get("hasNext")),
            "next_cursor", page.get("nextCursor"),
            // order-get-order-list-new nests its own business code ("000") inside data
            "data_code", page.get("code"));
      }
      case "mabang.orders.track" -> {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("platformOrderId", params.get("platformOrderId"));
        for (String field : List.of("lastUpdateTime", "page"))
          if (params.get(field) != null) data.put(field, params.get(field));
        Object result =
            call("wl-query-track-info", data, List.of("暂无数据"), timeout, system);
        List<Object> infos;
        // Nothing to report is a normal answer here — an absent parcel, an empty window — so all
        // the "no rows" shapes collapse to an empty list instead of an error.
        if (result == null
            || (result instanceof String text && text.isEmpty())
            || (result instanceof List<?> rows && rows.isEmpty())
            || (result instanceof Map<?, ?> map && map.isEmpty())) infos = List.of();
        else if (result instanceof List<?> rows) infos = JsonUtils.list(rows);
        else if (result instanceof Map<?, ?> map && map.containsKey("trackInfos"))
          infos = JsonUtils.list(map.get("trackInfos"));
        else if (result instanceof Map<?, ?> map)
          throw new GatewayException(
              502, "upstream_error", "马帮轨迹响应缺少 trackInfos: " + map.keySet(), false);
        else
          throw new GatewayException(
              502,
              "upstream_error",
              "马帮轨迹响应形态异常: " + result.getClass().getSimpleName(),
              false);
        var items =
            infos.stream().filter(Map.class::isInstance).map(MabangAdapter::trackRow).toList();
        yield JsonUtils.obj("track_infos", items, "count", items.size());
      }
      default -> throw GatewayException.config("未知马帮操作: " + op.key());
    };
  }
}

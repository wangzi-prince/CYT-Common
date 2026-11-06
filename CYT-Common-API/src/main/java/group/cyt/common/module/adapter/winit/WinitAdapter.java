package group.cyt.common.module.adapter.winit;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.adapter.support.SignatureSupport;
import group.cyt.common.module.adapter.support.TestWritePolicy;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Winit overseas warehouse. One call carries two signatures made with two different secrets: {@code
 * sign} identifies the seller account (token), {@code client_sign} the application (client_secret).
 *
 * <p>The upstream is case-sensitive about field names and does not complain when it fails to
 * recognise one — it answers with an empty list. So {@code data} is spelled out per operation here,
 * including the vendor's own typos ({@code OderStartDatetime}, {@code destnationWareHouseCode},
 * {@code trackingnos}), never a copy of the caller's params.
 */
@Component
public class WinitAdapter implements PlatformAdapter {
  private static final String SANDBOX_ENDPOINT =
      "https://sandboxopenapi.winit.com.cn/openapi/service";
  private static final String PROD_ENDPOINT = "https://openapi.winit.com.cn/openapi/service";

  private static final Map<String, String> ACTIONS =
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

  private static final List<String> REQUIRED_FIELDS =
      List.of("APP_KEY", "TOKEN", "PLATFORM", "CLIENT_ID", "CLIENT_SECRET");

  /**
   * The vendor validates in steps, so a code names the step that failed and implies every earlier
   * step passed. Without this table an operator reads 03010250014 as "bad keys" and rotates secrets
   * that are fine — the actual fix is one click in the developer console.
   */
  private static final Map<String, String> ERROR_HINTS =
      Map.ofEntries(
          Map.entry(
              "02010250152",
              "应用不存在：这个 client_id 在当前环境查不到。最常见是应用建在正式环境却打沙箱（或反过来）"),
          Map.entry("02010250132", "platform 与后台「应用Code」不匹配（大小写敏感）"),
          Map.entry("02020500199", "client_sign 失败 = client_secret 不对，或签名串里 data 没按 key 排序"),
          Map.entry(
              "03010250014", "应用未获得卖家授权：去开发者平台【沙箱测试→沙箱应用→管理】生成授权链接并完成授权"),
          Map.entry("03010250005", "sign 失败 = token 不对（不是 client_secret）"),
          Map.entry("02010250003", "data 字段异常：多半是缺必填参数，不是真的「类型错误」"),
          Map.entry("02010250001", "必填字段为空，看 msg 点名的那个字段"),
          Map.entry("02010250183", "仓库不存在：warehouseCode 要 US0001 这种格式，不能传 USWC 别名"),
          Map.entry(
              "02010250442", "订单不存在：轨迹接口必须给真实单号，入库单是 WI 开头、退货单是 RT 开头"));

  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final TestWritePolicy writePolicy;

  public WinitAdapter(
      CredentialService credentials, UpstreamHttp http, TestWritePolicy writePolicy) {
    this.credentials = credentials;
    this.http = http;
    this.writePolicy = writePolicy;
  }

  public boolean supports(String platform) {
    return platform.equals("winit");
  }

  // ── 凭证与环境 ─────────────────────────────────────────────────────────

  private String envName(String system) {
    String env = credentials.resolve("WINIT_ENV", system).strip();
    return env.isEmpty() ? "sandbox" : env.toLowerCase(Locale.ROOT);
  }

  /**
   * {@code WINIT_PROD_*} / {@code WINIT_SANDBOX_*} beat the unprefixed variable. Sandbox and
   * production are separate applications whose client_id is not interchangeable; without the prefix,
   * switching to prod means overwriting the sandbox values in place — and the wrong environment
   * reads as "application not found", not as "wrong environment".
   */
  private String cfg(String field, String system) {
    String generic = credentials.resolve("WINIT_" + field, system).strip();
    if (field.equals("ENV")) return generic;
    String scoped =
        credentials
            .resolve("WINIT_" + envName(system).toUpperCase(Locale.ROOT) + "_" + field, system)
            .strip();
    return scoped.isEmpty() ? generic : scoped;
  }

  private String required(String field, String system) {
    String value = cfg(field, system);
    if (!value.isEmpty()) return value;
    var missing =
        REQUIRED_FIELDS.stream()
            .filter(f -> cfg(f, system).isEmpty())
            .map(f -> "WINIT_" + f)
            .toList();
    throw GatewayException.missing(
        "万邑通凭证缺失："
            + String.join("、", missing)
            + "。platform / client_id / client_secret 在开发者平台【沙箱测试→沙箱应用】创建应用后获得；"
            + "app_key / token 沙箱用公共账号 rebecca。");
  }

  /** An explicit URL beats the built-in pair: Winit sometimes hands out a dedicated relay. */
  private String endpoint(String system) {
    String override = cfg("API_URL", system);
    if (!override.isEmpty()) return override;
    return envName(system).equals("prod") ? PROD_ENDPOINT : SANDBOX_ENDPOINT;
  }

  // ── 请求 ──────────────────────────────────────────────────────────────

  private Map<String, Object> post(
      String action, Map<String, Object> data, double timeout, String system) {
    Map<String, Object> payload =
        JsonUtils.obj(
            "action",
            action,
            "app_key",
            required("APP_KEY", system),
            // an object, never a string: the docs type data as String, and the vendor answers
            // 02010250003 to a quoted "{}"
            "data",
            data,
            "format",
            "json",
            "language",
            "zh_CN",
            "platform",
            required("PLATFORM", system),
            "sign_method",
            "md5",
            "timestamp",
            LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            "version",
            "1.0",
            "client_id",
            required("CLIENT_ID", system));
    payload.put("client_sign", SignatureSupport.winit(payload, required("CLIENT_SECRET", system)));
    payload.put("sign", SignatureSupport.winit(payload, required("TOKEN", system)));
    Map<String, Object> result =
        JsonUtils.map(
            http.json(
                "POST",
                endpoint(system),
                Map.of("Content-Type", "application/json;charset=UTF-8"),
                payload,
                timeout));
    String code = JsonUtils.str(result, "code").strip();
    if (code.equals("0")) return result;
    String hint = ERROR_HINTS.get(code);
    String message = "万邑通返回 code=" + code + ": " + JsonUtils.str(result, "msg");
    if (hint != null) message += "（" + hint + "）";
    // A known code is a fixed mistake — config, parameters, or an unknown order — and retrying it
    // only delays the caller.
    throw new GatewayException(502, "upstream_error", message, hint == null)
        .code(code)
        .detail(result);
  }

  // ── data 构造 ─────────────────────────────────────────────────────────

  /** Optional filters are dropped when empty; sending "" reads upstream as "match nothing". */
  private static void put(Map<String, Object> data, String key, Object value) {
    if (value == null || (value instanceof String s && s.isEmpty())) return;
    data.put(key, value);
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static Map<String, Object> pageParams(Map<String, Object> params) {
    return JsonUtils.obj("pageNo", params.get("pageNo"), "pageSize", params.get("pageSize"));
  }

  private Map<String, Object> buildData(String key, Map<String, Object> params) {
    var data = new LinkedHashMap<String, Object>();
    switch (key) {
      case "warehouses.list" -> data.putAll(params);
      case "returns.list" -> {
        data.put("OderStartDatetime", params.get("OderStartDatetime"));
        data.put("OderEndDatetime", params.get("OderEndDatetime"));
        data.put("pageParams", pageParams(params));
        for (String field :
            List.of(
                "returnGoodsOrderNo",
                "expressNo",
                "outboundOrderNo",
                "customerOrderNo",
                "rmaNo",
                "warehouseCode"))
          put(data, field, params.get(field));
      }
      case "returns.items" -> {
        // pageNo/pageSize here, as strings. pageNum — what outbound.list uses — is silently
        // ignored by this endpoint, which keeps handing back page 1.
        data.put("pageNo", text(params.get("pageNo")));
        data.put("pageSize", text(params.get("pageSize")));
        for (String field : List.of("skuCode", "code", "isActive"))
          put(data, field, params.get(field));
      }
      case "returns.psc" -> {
        // channel lookup: the vendor takes no data at all, and an absent data object is not the
        // same as data={} — it must be sent as an object or the signature string changes.
      }
      case "returns.label", "returns.void" ->
          data.put("returnGoodsOrderNos", params.get("returnGoodsOrderNos"));
      case "returns.create" -> data.putAll(returnCreateData(params));
      case "inbound.list" -> {
        data.put("destnationWareHouseCode", params.get("destnationWareHouseCode"));
        data.put("inspectionWareHouseCode", params.get("inspectionWareHouseCode"));
        data.put("orderType", params.get("orderType"));
        data.put("orderCreateDateStart", params.get("orderCreateDateStart"));
        data.put("orderCreateDateEnd", params.get("orderCreateDateEnd"));
        data.put("pageParams", pageParams(params));
      }
      case "outbound.list" -> {
        data.put("dateOrderedStartDate", params.get("dateOrderedStartDate"));
        data.put("dateOrderedEndDate", params.get("dateOrderedEndDate"));
        String status = text(params.get("status"));
        // ALL by default, on purpose: omitting status quietly drops the voided orders.
        data.put("status", status.isEmpty() ? "ALL" : status);
        data.put("pageNum", params.get("pageNo"));
        data.put("pageSize", params.get("pageSize"));
        for (String field : List.of("trackingNo", "sellerOrderNo", "receiverName"))
          put(data, field, params.get(field));
      }
      case "inbound.tracking" -> data.put("orderNo", params.get("orderNo"));
      case "outbound.tracking" ->
          // All lower-case and comma-joined: a trackingNos array is not recognised, and the empty
          // answer looks like "this parcel has no events".
          data.put(
              "trackingnos",
              String.join(
                  ",",
                  JsonUtils.list(params.get("trackingNos")).stream()
                      .map(String::valueOf)
                      .toList()));
      case "inventory.list" -> {
        data.put("warehouseCode", params.get("warehouseCode"));
        data.put("pageNum", text(params.get("pageNum")));
        data.put("pageSize", text(params.get("pageSize")));
        for (String field : List.of("isActive", "inReturnInventory", "isFilterZeroInventory"))
          put(data, field, params.get(field));
      }
      default -> throw GatewayException.config("万邑通操作未实现: " + key);
    }
    return data;
  }

  /** confirm and the package/address cross-checks are gateway-side only — never sent upstream. */
  private static Map<String, Object> returnCreateData(Map<String, Object> params) {
    var data = new LinkedHashMap<String, Object>();
    data.put("warehouseCode", params.get("warehouseCode"));
    data.put("isWinitOutbound", params.get("isWinitOutbound"));
    data.put("userInfoSource", params.get("userInfoSource"));
    data.put(
        "ReturnGoodsList",
        JsonUtils.list(params.get("ReturnGoodsList")).stream()
            .filter(Map.class::isInstance)
            .map(WinitAdapter::returnGoodsRow)
            .toList());
    for (String field :
        List.of(
            "customerOrderNo",
            "outboundOrderNo",
            "shippingNo",
            "winitProductCode",
            "expressNo",
            "supplierName",
            "VATNo",
            "name",
            "email",
            "phone",
            "state",
            "country",
            "city",
            "postcode",
            "houseNo",
            "address1",
            "address2",
            "collectionDate",
            "collectionAddress"))
      put(data, field, params.get(field));
    for (String field : List.of("packageLength", "packageWidth", "packageHeight", "packageWeight"))
      put(data, field, text(params.get(field))); // the vendor's examples send these as strings
    return data;
  }

  private static Map<String, Object> returnGoodsRow(Object value) {
    Map<String, Object> item = JsonUtils.map(value);
    var row = new LinkedHashMap<String, Object>();
    row.put("productCode", item.get("productCode"));
    row.put("productNum", item.get("productNum"));
    row.put("handleMethod", item.get("handleMethod"));
    for (String field : List.of("shelveMethod", "goodMethod", "defectiveMethod", "specification"))
      put(row, field, item.get(field));
    List<Object> codes = JsonUtils.list(item.get("itemCodes"));
    if (!codes.isEmpty())
      row.put(
          "itemCodeList", codes.stream().map(code -> JsonUtils.obj("itemCode", code)).toList());
    return row;
  }

  // ── 响应整形 ──────────────────────────────────────────────────────────

  private static List<Object> rowsOf(Object data) {
    if (data instanceof List<?> list) return JsonUtils.list(list);
    // An empty object upstream means "no rows", not one row of nulls.
    if (data instanceof Map<?, ?> map && !map.isEmpty()) return List.of(data);
    return List.of();
  }

  private static Map<String, Object> pageResult(Object data, boolean inbound) {
    Map<String, Object> body = JsonUtils.map(data);
    return JsonUtils.obj(
        "pageParams", body.getOrDefault("pageParams", Map.of()),
        "list", body.getOrDefault(inbound ? "orderList" : "list", List.of()));
  }

  private static Map<String, Object> pagedResult(Object data) {
    Map<String, Object> body = JsonUtils.map(data);
    return JsonUtils.obj(
        "list", body.getOrDefault("list", List.of()),
        "total", body.get("total"),
        "currentPageNum", body.get("currentPageNum"),
        "currentPageSize", body.get("currentPageSize"));
  }

  private static Map<String, Object> outboundTrackingResult(Object data) {
    var orders = new ArrayList<Map<String, Object>>();
    for (Object node : rowsOf(data)) {
      if (!(node instanceof Map)) continue;
      Map<String, Object> order = JsonUtils.map(node);
      var trace =
          JsonUtils.list(order.get("trace")).stream()
              .filter(Map.class::isInstance)
              .map(JsonUtils::map)
              .map(
                  t ->
                      JsonUtils.obj(
                          "date", t.get("date"),
                          "timestamp", t.get("dateTimestamp"),
                          // S = a Winit warehouse move, V = a carrier scan
                          "source", t.get("type"),
                          "code", t.get("eventCode"),
                          "desc", t.get("eventDescription"),
                          "status", t.get("eventStatus"),
                          "location", t.get("location"),
                          "scan", t.get("trackingType")))
              .sorted(Comparator.comparing(row -> text(row.get("date")))) // newest first upstream
              .toList();
      orders.add(
          JsonUtils.obj(
              "order_no", order.get("orderNo"),
              "master_order_no", order.get("masterOrderNo"),
              "tracking_no", order.get("trackingNo"),
              "status", order.get("status"),
              "origin", order.get("origin"),
              "destination", order.get("destination"),
              "standard_carrier", order.get("standardCarrier"),
              "tracking_url", order.get("trackingUrl"),
              "trace", trace,
              "pod",
                  JsonUtils.list(order.get("podInfoList")).stream()
                      .filter(Map.class::isInstance)
                      .toList()));
    }
    return JsonUtils.obj("orders", orders, "count", orders.size());
  }

  private static Map<String, Object> pscResult(Object data) {
    var channels =
        JsonUtils.list(JsonUtils.map(data).get("list")).stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            .map(
                item ->
                    JsonUtils.obj(
                        "product_code", item.get("productCode"),
                        "product_name", item.get("productName"),
                        "sla", item.get("sla"),
                        "delivery_service", item.get("deliveryService"),
                        // The list mixes in Winit's internal test channels; ReturnLabel only works
                        // on the ones flagged Y, so never take the first row.
                        "return_label", text(item.get("deliveryService")).equalsIgnoreCase("Y")))
            .toList();
    return JsonUtils.obj("list", channels, "count", channels.size());
  }

  private static Map<String, Object> labelResult(Object data) {
    var rows =
        rowsOf(data).stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            .map(
                item -> {
                  String label = text(item.get("label"));
                  return JsonUtils.obj(
                      "return_goods_order_no", item.get("returnGoodsOrderNo"),
                      "tracking_no", item.get("trackingNo"),
                      // An empty PDF is legitimate — a cancelled order, or one not on ReturnLabel.
                      "label_base64", label,
                      "has_label", !label.isEmpty());
                })
            .toList();
    return JsonUtils.obj("list", rows, "count", rows.size());
  }

  private static Map<String, Object> voidResult(Object data) {
    Map<String, Object> body = JsonUtils.map(data);
    var results =
        JsonUtils.list(body.get("results")).stream()
            .filter(Map.class::isInstance)
            .map(JsonUtils::map)
            .map(
                item ->
                    JsonUtils.obj(
                        "return_goods_order", item.get("returnGoodsOrder"),
                        "ok", text(item.get("result")).equalsIgnoreCase("Y"),
                        "reason", item.get("reason")))
            .toList();
    // Not folded into one boolean: no-RMA and failed-delivery returns cannot be cancelled, so the
    // caller has to see which of the orders did not go through.
    return JsonUtils.obj(
        "success_count", body.get("successCount"),
        "failed_count", body.get("failedCount"),
        "results", results);
  }

  // ── 分发 ──────────────────────────────────────────────────────────────

  public Object invoke(OperationDefinition op, Map<String, Object> params, String system) {
    String key = op.key().substring("winit.".length());
    if (!ACTIONS.containsKey(key)) throw GatewayException.config("万邑通操作未实现: " + key);
    // Creating a return order asks the carrier for a label and costs real freight, and Winit has no
    // reliable "did I already create this" lookup — so writes go only to whitelisted destination
    // warehouses. voidOrder does not take warehouseCode upstream; it is read here purely as that gate.
    if (key.equals("returns.create") || key.equals("returns.void"))
      writePolicy.requireTarget("winit", text(params.get("warehouseCode")));
    Object data =
        post(ACTIONS.get(key), buildData(key, params), op.timeoutSeconds(), system).get("data");
    return switch (key) {
      case "warehouses.list" ->
          JsonUtils.obj(
              "warehouseList", JsonUtils.map(data).getOrDefault("warehouseList", List.of()));
      case "returns.list", "returns.items" -> pageResult(data, false);
      case "inbound.list" -> pageResult(data, true);
      case "outbound.list", "inventory.list" -> pagedResult(data);
      case "inbound.tracking" ->
          JsonUtils.obj(
              "trackingList", JsonUtils.map(data).getOrDefault("trackingList", List.of()));
      case "outbound.tracking" -> outboundTrackingResult(data);
      case "returns.psc" -> pscResult(data);
      case "returns.label" -> labelResult(data);
      case "returns.create" -> {
        Map<String, Object> body = JsonUtils.map(data);
        yield JsonUtils.obj(
            "return_goods_order_no", body.get("returnGoodsOrderNo"),
            "rma_no", body.get("rmaNo"),
            "return_address", body.get("returnAddress"));
      }
      case "returns.void" -> voidResult(data);
      default -> throw GatewayException.config("万邑通操作未实现: " + key);
    };
  }
}

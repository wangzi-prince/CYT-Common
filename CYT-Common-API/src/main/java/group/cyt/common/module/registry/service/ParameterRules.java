package group.cyt.common.module.registry.service;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

/** Cross-field constraints and normalizations that JSON Schema cannot express. */
public final class ParameterRules {
  private ParameterRules() {}

  public static Map<String, Object> normalize(String key, Map<String, Object> input) {
    var p = new LinkedHashMap<>(input);
    if (key.startsWith("wecom.") && p.containsKey("credential_profile")) {
      if (p.containsKey("app")) throw GatewayException.invalid("app 与 credential_profile 只能提供一个");
      p.put("app", p.remove("credential_profile"));
    }
    if (key.equals("gmail.messages.send"))
      for (String field : List.of("to", "cc", "bcc"))
        if (p.get(field) instanceof String s) p.put(field, List.of(s));
    if (key.startsWith("wecom.")) {
      if (p.get("to_users") instanceof String s) p.put("to_users", List.of(s));
      if (p.get("attendees") instanceof List<?> attendees)
        p.put(
            "attendees",
            attendees.stream()
                .map(v -> v instanceof String s ? JsonUtils.obj("userid", s) : v)
                .toList());
      if (Set.of("wecom.schedules.create", "wecom.schedules.update").contains(key))
        for (String field : List.of("start", "end", "op_start"))
          if (p.get(field + "_at") != null) {
            p.put(field + "_time", epoch(p.get(field + "_at")));
            p.put(field + "_at", null);
          }
    }
    return p;
  }

  public static long epoch(Object value) {
    if (value instanceof Number n) return n.longValue();
    String s = value.toString().trim();
    try {
      if (s.matches("[0-9]{10,13}")) {
        long n = Long.parseLong(s);
        return n > 100000000000L ? n / 1000 : n;
      }
      try {
        return Instant.parse(s).getEpochSecond();
      } catch (Exception ignored) {
      }
      try {
        return OffsetDateTime.parse(s).toEpochSecond();
      } catch (Exception ignored) {
      }
      return LocalDateTime.parse(s.replace(' ', 'T'))
          .atZone(ZoneId.of("Asia/Shanghai"))
          .toEpochSecond();
    } catch (Exception e) {
      throw GatewayException.invalid("时间格式无效，请使用 ISO 8601 或 Unix 秒时间戳");
    }
  }

  public static void validate(String key, Map<String, Object> p) {
    if (key.equals("gdrive.files.upload") || key.equals("gcs.objects.upload"))
      if ((p.get("content_text") == null) == (p.get("content_base64") == null))
        throw GatewayException.invalid("content_text 与 content_base64 必须且只能提供一个");
    if (key.equals("gcs.objects.list")) {
      if (JsonUtils.bool(p.get("include_folders_as_prefixes"))
          && !JsonUtils.str(p, "delimiter").equals("/"))
        throw GatewayException.invalid("include_folders_as_prefixes 需要 delimiter='/'");
      if (JsonUtils.bool(p.get("versions")) && JsonUtils.bool(p.get("soft_deleted")))
        throw GatewayException.invalid("versions 与 soft_deleted 不能同时启用");
    }
    if (key.equals("docusign.envelopes.create")) {
      if (!JsonUtils.str(p, "template_id").isBlank()) {
        if (JsonUtils.list(p.get("template_roles")).isEmpty())
          throw GatewayException.invalid("模板信封必须提供 template_roles");
      } else if (JsonUtils.list(p.get("documents")).isEmpty()
          || JsonUtils.list(p.get("recipients")).isEmpty())
        throw GatewayException.invalid("非模板信封必须提供 documents 和 recipients");
      long bytes = 0;
      for (Object d : JsonUtils.list(p.get("documents")))
        try {
          bytes +=
              Base64.getDecoder().decode(JsonUtils.str(JsonUtils.map(d), "document_base64")).length;
        } catch (Exception e) {
          throw GatewayException.invalid("文档 Base64 无效");
        }
      if (bytes > 10 * 1024 * 1024) throw GatewayException.invalid("信封附件合计不能超过 10 MB");
    }
    if (key.equals("wecom.schedules.create")
        && (p.get("start_time") == null || p.get("end_time") == null))
      throw GatewayException.invalid("创建日程必须提供开始和结束时间");
    if (key.startsWith("wecom.schedules.")
        && p.get("start_time") != null
        && p.get("end_time") != null
        && epoch(p.get("end_time")) <= epoch(p.get("start_time")))
      throw GatewayException.invalid("结束时间必须晚于开始时间");
    if (key.equals("wecom.calendars.create")) {
      if ((JsonUtils.bool(p.get("is_public")) || JsonUtils.bool(p.get("is_corp_calendar")))
          && p.get("public_range") == null) throw GatewayException.invalid("公开日历需要提供 public_range");
      Set<String> shares = new HashSet<>();
      for (Object s : JsonUtils.list(p.get("shares")))
        shares.add(JsonUtils.str(JsonUtils.map(s), "userid"));
      if (!shares.isEmpty())
        for (Object admin : JsonUtils.list(p.get("admins")))
          if (!shares.contains(admin.toString()))
            throw GatewayException.invalid("日历管理员必须在通知范围 shares 中");
    }
    if (key.equals("wecom.directory.users")) {
      boolean users = p.get("userids") != null,
          departments =
              !JsonUtils.list(p.get("department_ids")).isEmpty()
                  || !JsonUtils.list(p.get("paths")).isEmpty();
      if (users == departments) throw GatewayException.invalid("按账号或按部门查询必须且只能选择一种");
    }
    if (key.equals("quickcep.sessions.list")
        && JsonUtils.str(p, "create_date").isBlank()
        && JsonUtils.str(p, "close_date").isBlank())
      throw GatewayException.invalid("create_date 与 close_date 至少提供一个");
    if (key.equals("shopify.orders.update")
        && p.get("note") == null
        && p.get("tags") == null
        && p.get("shipping_address") == null) throw GatewayException.invalid("至少提供一项订单变更");
    if (key.equals("shopify.refunds.create")) {
      if (!JsonUtils.bool(p.get("confirm"))) throw GatewayException.invalid("退款必须 confirm=true");
      try {
        if (new BigDecimal(JsonUtils.str(p, "amount")).signum() <= 0)
          throw new NumberFormatException();
      } catch (Exception e) {
        throw GatewayException.invalid("退款金额必须为正数");
      }
    }
    if (key.equals("shopify.orders.create")
        && (!JsonUtils.bool(p.get("confirm")) || JsonUtils.str(p, "reference").isBlank()))
      throw GatewayException.invalid("补发订单需要 confirm=true 和原始 RMA 引用");
    if (key.startsWith("winit.returns.")) validateWinitReturn(key, p);
  }

  /**
   * Winit's return-order rules the vendor only enforces after charging: a label is bought from the
   * carrier at create time, and its own required-field checks depend on which branch of the form
   * was picked. Rejecting here means the caller never spends money on a malformed order.
   */
  private static void validateWinitReturn(String key, Map<String, Object> p) {
    if (key.equals("winit.returns.list") || key.equals("winit.returns.items")
        || key.equals("winit.returns.psc") || key.equals("winit.returns.label")) return;
    if (!JsonUtils.bool(p.get("confirm")))
      throw GatewayException.invalid(
          key.equals("winit.returns.create")
              ? "创建退货单会向承运商取面单、产生实际运费：必须显式传 confirm=true 才会执行"
              : "取消退货单必须显式传 confirm=true");
    if (!key.equals("winit.returns.create")) return;
    if (JsonUtils.list(p.get("ReturnGoodsList")).isEmpty())
      throw GatewayException.invalid("ReturnGoodsList 不能为空");
    for (Object value : JsonUtils.list(p.get("ReturnGoodsList"))) {
      Map<String, Object> item = JsonUtils.map(value);
      String product = JsonUtils.str(item, "productCode");
      if ("WI".equals(JsonUtils.str(item, "handleMethod"))
          && (JsonUtils.str(item, "goodMethod").isBlank()
              || JsonUtils.str(item, "defectiveMethod").isBlank()))
        throw GatewayException.invalid("商品 " + product + " 退货策略=WI 时 goodMethod / defectiveMethod 都必填");
      List<Object> codes = JsonUtils.list(item.get("itemCodes"));
      if (!codes.isEmpty() && codes.size() != JsonUtils.integer(item.get("productNum"), 0))
        throw GatewayException.invalid(
            "商品 " + product + " 的单品条码数量（" + codes.size() + "）必须等于退货数量（"
                + JsonUtils.str(item, "productNum") + "）");
    }
    if ("Y".equals(JsonUtils.str(p, "isWinitOutbound"))) {
      if (JsonUtils.str(p, "outboundOrderNo").isBlank())
        throw GatewayException.invalid("isWinitOutbound=Y 时 outboundOrderNo 必填");
      if ("IRI".equals(JsonUtils.str(p, "userInfoSource")) && JsonUtils.str(p, "name").isBlank())
        throw GatewayException.invalid("userInfoSource=IRI 时必须提供退货人姓名 name");
      return;
    }
    // Non-Winit outbound only supports the ReturnLabel service, and the parcel box must be declared.
    if (JsonUtils.str(p, "winitProductCode").isBlank())
      throw GatewayException.invalid(
          "非万邑通出库（isWinitOutbound=N）必须指定退货渠道 PSC（winitProductCode），面单只能走 ReturnLabel");
    var missing =
        Stream.of("packageLength", "packageWidth", "packageHeight", "packageWeight")
            .filter(field -> p.get(field) == null || text(p.get(field)).isEmpty())
            .toList();
    if (!missing.isEmpty())
      throw GatewayException.invalid("非万邑通出库必须提供包裹箱单信息，缺：" + String.join("、", missing));
    if (!"IRI".equals(JsonUtils.str(p, "userInfoSource")))
      throw GatewayException.invalid("非万邑通出库时发件人地址来源只能是 IRI");
    var lack =
        Stream.of("name", "country", "city", "postcode", "address1")
            .filter(field -> JsonUtils.str(p, field).isBlank())
            .toList();
    if (!lack.isEmpty()) throw GatewayException.invalid("退货人地址缺必填字段：" + String.join("、", lack));
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}

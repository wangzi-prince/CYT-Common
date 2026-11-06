package group.cyt.common.module.adapter.wecom.approval;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.registry.service.ParameterRules;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Deterministic template-aware conversion. Labels resolve only when unique; no guessed IDs. */
public final class ApprovalFormCodec {
  private static final Set<String> COMPUTED = Set.of("Tips", "Formula", "PunchCorrection");

  private ApprovalFormCodec() {}

  public static String text(Object localized) {
    if (localized instanceof Map<?, ?>) return JsonUtils.str(JsonUtils.map(localized), "text");
    var list = JsonUtils.list(localized);
    return list.stream()
        .map(JsonUtils::map)
        .filter(m -> "zh_CN".equals(m.get("lang")))
        .findFirst()
        .or(() -> list.stream().map(JsonUtils::map).findFirst())
        .map(m -> JsonUtils.str(m, "text"))
        .orElse(Objects.toString(localized, ""));
  }

  public static List<Object> controls(Map<String, Object> template) {
    return JsonUtils.list(JsonUtils.map(template.get("template_content")).get("controls"));
  }

  private static Map<String, Object> property(Map<String, Object> control) {
    return JsonUtils.map(control.get("property"));
  }

  private static String id(Map<String, Object> control) {
    return JsonUtils.str(property(control), "id");
  }

  private static String type(Map<String, Object> control) {
    return JsonUtils.str(property(control), "control");
  }

  private static String label(Map<String, Object> control) {
    return text(property(control).get("title"));
  }

  private static List<Object> children(Map<String, Object> control) {
    return JsonUtils.list(
        JsonUtils.map(JsonUtils.map(control.get("config")).get("table")).get("children"));
  }

  private static String key(Map<String, Object> c, Map<String, Object> aliases) {
    return aliases.entrySet().stream()
        .filter(e -> id(c).equals(e.getValue()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(id(c));
  }

  public static Map<String, Object> describe(
      Map<String, Object> template, Map<String, Object> aliases) {
    var fields = new ArrayList<Object>();
    var sample = new LinkedHashMap<String, Object>();
    for (Object item : controls(template)) {
      var c = JsonUtils.map(item);
      var field = field(c, aliases);
      fields.add(field);
      if (!COMPUTED.contains(type(c))) sample.put(key(c, aliases), field.get("example"));
    }
    return JsonUtils.obj(
        "name",
        text(template.get("template_names")),
        "version",
        JsonUtils.sha(JsonUtils.write(JsonUtils.sorted(template))),
        "fields",
        fields,
        "fields_example",
        sample);
  }

  private static Map<String, Object> field(Map<String, Object> c, Map<String, Object> aliases) {
    String type = type(c);
    Object sample =
        switch (type) {
          case "Number", "Money" -> "0";
          case "Date" -> "2026-11-05";
          case "Contact", "File", "RelatedApproval" -> List.of("example-id");
          case "DateRange", "Vacation", "Attendance" ->
              JsonUtils.obj(
                  "start_at",
                  "2026-11-05 09:00",
                  "end_at",
                  "2026-11-05 18:00",
                  "duration_seconds",
                  28800);
          case "Location" ->
              JsonUtils.obj(
                  "latitude",
                  "0.000000",
                  "longitude",
                  "0.000000",
                  "title",
                  "地点",
                  "address",
                  "地址",
                  "time",
                  0);
          case "Table" -> List.of();
          default -> "请填写";
        };
    var selector = JsonUtils.map(JsonUtils.map(c.get("config")).get("selector"));
    var options = new ArrayList<Object>();
    for (Object option : JsonUtils.list(selector.get("options"))) {
      var o = JsonUtils.map(option);
      options.add(JsonUtils.obj("key", o.get("key"), "label", text(o.get("value"))));
    }
    if (type.equals("Selector") && !options.isEmpty())
      sample =
          JsonUtils.str(selector, "type").equals("multi")
              ? List.of(JsonUtils.map(options.getFirst()).get("key"))
              : JsonUtils.map(options.getFirst()).get("key");
    if (type.equals("Table")) {
      var row = new LinkedHashMap<String, Object>();
      for (Object child : children(c)) {
        var cc = JsonUtils.map(child);
        if (!COMPUTED.contains(type(cc)))
          row.put(key(cc, aliases), field(cc, aliases).get("example"));
      }
      sample = List.of(row);
    }
    var out =
        JsonUtils.obj(
            "key",
            key(c, aliases),
            "id",
            id(c),
            "label",
            label(c),
            "control",
            type,
            "required",
            JsonUtils.integer(property(c).get("require"), 0) == 1,
            "read_only",
            COMPUTED.contains(type),
            "hint",
            text(property(c).get("placeholder")),
            "options",
            options,
            "config",
            c.getOrDefault("config", Map.of()),
            "example",
            sample);
    if (type.equals("Table"))
      out.put(
          "children",
          children(c).stream().map(JsonUtils::map).map(cc -> field(cc, aliases)).toList());
    return out;
  }

  public static Map<String, Object> encode(
      Map<String, Object> template, Map<String, Object> fields, Map<String, Object> aliases) {
    List<Object> errors = new ArrayList<>();
    int[] files = {0};
    var contents =
        encodeControls(controls(template), fields, aliases, "fields", false, errors, files);
    if (files[0] > 6) errors.add(JsonUtils.obj("field", "fields", "message", "单个审批单的附件合计最多 6 个"));
    if (!errors.isEmpty()) throw GatewayException.invalid("审批字段校验失败，请按模板修正").detail(errors);
    return JsonUtils.obj("contents", contents);
  }

  private static List<Object> encodeControls(
      List<Object> definitions,
      Map<String, Object> values,
      Map<String, Object> aliases,
      String path,
      boolean all,
      List<Object> errors,
      int[] files) {
    var resolved = new LinkedHashMap<String, Object>();
    for (var entry : values.entrySet()) {
      String name = entry.getKey();
      var matches =
          definitions.stream()
              .map(JsonUtils::map)
              .filter(c -> id(c).equals(name) || id(c).equals(aliases.get(name)))
              .toList();
      if (matches.isEmpty())
        matches =
            definitions.stream().map(JsonUtils::map).filter(c -> label(c).equals(name)).toList();
      if (matches.size() != 1) {
        errors.add(
            JsonUtils.obj(
                "field",
                path + "." + name,
                "message",
                matches.isEmpty() ? "模板中没有这个字段" : "字段名称重复，请使用业务别名或控件 ID"));
        continue;
      }
      String id = id(matches.getFirst());
      if (resolved.containsKey(id))
        errors.add(JsonUtils.obj("field", path + "." + name, "message", "同一个控件被重复赋值"));
      else resolved.put(id, entry.getValue());
    }
    List<Object> contents = new ArrayList<>();
    for (Object item : definitions) {
      var c = JsonUtils.map(item);
      String id = id(c), type = type(c);
      Object value = resolved.get(id);
      String fieldPath = path + "." + key(c, aliases);
      if (COMPUTED.contains(type)) {
        if (resolved.containsKey(id))
          errors.add(JsonUtils.obj("field", fieldPath, "message", "此控件由企微计算或填充，无需赋值"));
        continue;
      }
      boolean empty = empty(value);
      if (empty && JsonUtils.integer(property(c).get("require"), 0) == 1) {
        errors.add(JsonUtils.obj("field", fieldPath, "label", label(c), "message", "必填字段不能为空"));
        continue;
      }
      if (empty && !all) continue;
      try {
        Object encoded =
            empty ? Map.of() : encodeValue(c, value, aliases, fieldPath, errors, files);
        contents.add(JsonUtils.obj("control", type, "id", id, "value", encoded));
      } catch (GatewayException error) {
        errors.add(
            JsonUtils.obj("field", fieldPath, "label", label(c), "message", error.getMessage()));
      }
    }
    return contents;
  }

  private static Object encodeValue(
      Map<String, Object> c,
      Object value,
      Map<String, Object> aliases,
      String path,
      List<Object> errors,
      int[] files) {
    if (value instanceof Map<?, ?> raw && raw.containsKey("$value")) {
      var v = JsonUtils.map(raw.get("$value"));
      if (v.isEmpty()) throw GatewayException.invalid("$value 必须为非空的官方控件值对象");
      files[0] += countFiles(v);
      return v;
    }
    String type = type(c);
    var config = JsonUtils.map(c.get("config"));
    return switch (type) {
      case "Text", "Textarea" -> {
        if (!(value instanceof String)) throw GatewayException.invalid("文本字段应为字符串");
        if (type.equals("Text") && value.toString().matches("(?s).*[\\r\\n].*"))
          throw GatewayException.invalid("单行文本不能包含换行");
        yield JsonUtils.obj("text", value);
      }
      case "Number", "Money" -> {
        try {
          yield JsonUtils.obj(
              type.equals("Money") ? "new_money" : "new_number",
              new BigDecimal(value.toString()).toPlainString());
        } catch (NumberFormatException e) {
          throw GatewayException.invalid("数字或金额必须是有效的十进制数");
        }
      }
      case "Date" ->
          JsonUtils.obj(
              "date",
              JsonUtils.obj(
                  "type",
                  JsonUtils.str(JsonUtils.map(config.get("date")), "type", "day"),
                  "s_timestamp",
                  Long.toString(epoch(value))));
      case "Selector" -> selector(JsonUtils.map(config.get("selector")), value);
      case "Contact" -> {
        var contact = JsonUtils.map(config.get("contact"));
        boolean department = "department".equals(JsonUtils.str(contact, "mode"));
        List<Object> selected = list(value);
        if ("single".equals(JsonUtils.str(contact, "type")) && selected.size() > 1)
          throw GatewayException.invalid("该成员/部门字段只允许单选");
        yield JsonUtils.obj(
            department ? "departments" : "members",
            selected.stream()
                .map(
                    v ->
                        v instanceof Map<?, ?>
                            ? v
                            : JsonUtils.obj(department ? "openapi_id" : "userid", v.toString()))
                .toList());
      }
      case "File" -> {
        var selected = list(value);
        files[0] += selected.size();
        yield JsonUtils.obj(
            "files",
            selected.stream()
                .map(v -> v instanceof Map<?, ?> ? v : JsonUtils.obj("file_id", v.toString()))
                .toList());
      }
      case "RelatedApproval" ->
          JsonUtils.obj(
              "related_approval",
              list(value).stream()
                  .map(v -> v instanceof Map<?, ?> ? v : JsonUtils.obj("sp_no", v.toString()))
                  .toList());
      case "Table" -> {
        if (!(value instanceof List<?> rows) || rows.isEmpty() || rows.size() > 100)
          throw GatewayException.invalid("明细应为 1–100 行对象数组");
        List<Object> encoded = new ArrayList<>();
        int index = 0;
        for (Object row : rows) {
          if (!(row instanceof Map<?, ?>)) throw GatewayException.invalid("每行明细必须是字段对象");
          encoded.add(
              JsonUtils.obj(
                  "list",
                  encodeControls(
                      children(c),
                      JsonUtils.map(row),
                      aliases,
                      path + "[" + index++ + "]",
                      true,
                      errors,
                      files)));
        }
        yield JsonUtils.obj("children", encoded);
      }
      case "DateRange" ->
          JsonUtils.obj(
              "date_range",
              range(
                  JsonUtils.map(value),
                  JsonUtils.str(JsonUtils.map(config.get("date_range")), "type", "hour")));
      case "Attendance" -> {
        var attendance = JsonUtils.map(config.get("attendance"));
        yield JsonUtils.obj(
            "attendance",
            JsonUtils.obj(
                "type",
                attendance.getOrDefault("type", 3),
                "date_range",
                range(
                    JsonUtils.map(value),
                    JsonUtils.str(JsonUtils.map(attendance.get("date_range")), "type", "hour"))));
      }
      case "Vacation" -> {
        var v = JsonUtils.map(value);
        var options = new ArrayList<Object>();
        for (Object item : JsonUtils.list(JsonUtils.map(config.get("vacation_list")).get("item"))) {
          var o = JsonUtils.map(item);
          options.add(
              JsonUtils.obj("key", Objects.toString(o.get("id"), ""), "value", o.get("name")));
        }
        if (empty(v.get("leave_type")))
          throw GatewayException.invalid("请假字段需要 leave_type（假期类型名称或 ID）");
        var selection =
            JsonUtils.map(
                selector(JsonUtils.obj("type", "single", "options", options), v.get("leave_type"))
                    .get("selector"));
        selection.put("exp_type", 0);
        yield JsonUtils.obj(
            "vacation",
            JsonUtils.obj(
                "selector",
                selection,
                "attendance",
                JsonUtils.obj(
                    "type", 1, "date_range", range(v, JsonUtils.str(v, "granularity", "hour")))));
      }
      case "Location" -> {
        var location = JsonUtils.map(value);
        try {
          double lat = Double.parseDouble(JsonUtils.str(location, "latitude")),
              lng = Double.parseDouble(JsonUtils.str(location, "longitude"));
          if (!Double.isFinite(lat)
              || !Double.isFinite(lng)
              || Math.abs(lat) > 90
              || Math.abs(lng) > 180) throw new NumberFormatException();
        } catch (Exception e) {
          throw GatewayException.invalid("位置需要合法的 latitude 和 longitude");
        }
        yield JsonUtils.obj("location", location);
      }
      default -> throw GatewayException.invalid("此控件需要显式传 {$value: 官方控件值对象}，网关不会猜测格式");
    };
  }

  private static Map<String, Object> selector(Map<String, Object> config, Object value) {
    List<Object> selected = list(value), options = JsonUtils.list(config.get("options"));
    String mode = JsonUtils.str(config, "type", "single");
    if (mode.equals("single") && selected.size() != 1)
      throw GatewayException.invalid("单选字段只能提供一个选项");
    List<Object> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Object item : selected) {
      String requested = item.toString();
      var matches =
          options.stream()
              .map(JsonUtils::map)
              .filter(o -> requested.equals(Objects.toString(o.get("key"), "")))
              .toList();
      if (matches.isEmpty())
        matches =
            options.stream()
                .map(JsonUtils::map)
                .filter(o -> requested.equals(text(o.get("value"))))
                .toList();
      if (matches.size() != 1)
        throw GatewayException.invalid(matches.isEmpty() ? "选项不存在，请从模板选项中选择" : "选项文字重复，请使用选项 key");
      var option = matches.getFirst();
      String key = option.get("key").toString();
      if (seen.add(key)) result.add(JsonUtils.obj("key", key));
    }
    return JsonUtils.obj("selector", JsonUtils.obj("type", mode, "options", result));
  }

  private static Map<String, Object> range(Map<String, Object> v, String granularity) {
    if (v.get("start_at") == null || v.get("end_at") == null || v.get("duration_seconds") == null)
      throw GatewayException.invalid("时长字段需要 start_at、end_at 和明确的 duration_seconds；网关不会把非工作时间当作工时");
    long start = epoch(v.get("start_at")), end = epoch(v.get("end_at"));
    long duration;
    try {
      duration = new BigDecimal(v.get("duration_seconds").toString()).longValueExact();
    } catch (Exception e) {
      throw GatewayException.invalid("duration_seconds 必须是正整数");
    }
    if (end <= start || duration <= 0) throw GatewayException.invalid("结束时间必须晚于开始时间，时长必须为正数");
    if (!Set.of("hour", "halfday").contains(granularity))
      throw GatewayException.invalid("granularity 仅支持 hour 或 halfday");
    if (granularity.equals("halfday"))
      for (long t : List.of(start, end)) {
        var local = Instant.ofEpochSecond(t).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime();
        if (!local.equals(LocalTime.MIDNIGHT) && !local.equals(LocalTime.NOON))
          throw GatewayException.invalid("按半天填写时，边界必须是北京时间 00:00 或 12:00");
      }
    return JsonUtils.obj(
        "type", granularity, "new_begin", start, "new_end", end, "new_duration", duration);
  }

  public static long epoch(Object value) {
    if (value == null) throw GatewayException.invalid("时间不能为空");
    long result;
    try {
      if (value instanceof String s && s.matches("\\d{4}-\\d{2}-\\d{2}"))
        return LocalDate.parse(s).atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond();
      if (value instanceof Number || value instanceof String s && s.matches("\\d+"))
        result = new BigDecimal(value.toString()).longValueExact();
      else result = ParameterRules.epoch(value);
    } catch (DateTimeException | ArithmeticException | NumberFormatException e) {
      throw GatewayException.invalid("时间格式无效，请使用有效的 ISO 日期或整数 Unix 时间戳");
    }
    return result > 100_000_000_000L ? result / 1000 : result;
  }

  private static boolean empty(Object v) {
    return v == null
        || (v instanceof String s && s.isBlank())
        || (v instanceof Collection<?> c && c.isEmpty())
        || (v instanceof Map<?, ?> m && m.isEmpty());
  }

  private static List<Object> list(Object value) {
    return value instanceof List<?> ? JsonUtils.list(value) : List.of(value);
  }

  private static int countFiles(Object value) {
    if (value instanceof Map<?, ?> m)
      return m.entrySet().stream()
          .mapToInt(
              e ->
                  e.getKey().equals("files")
                      ? JsonUtils.list(e.getValue()).size()
                      : countFiles(e.getValue()))
          .sum();
    if (value instanceof List<?> l) return l.stream().mapToInt(ApprovalFormCodec::countFiles).sum();
    return 0;
  }
}

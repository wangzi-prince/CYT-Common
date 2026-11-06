package group.cyt.common.module.adapter.wecom.approval;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Exercises the same field conversion in mock mode; state never crosses caller boundaries. */
public final class ApprovalSimulator {
  private final Map<String, Map<String, Object>> templates = new ConcurrentHashMap<>(),
      approvals = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();

  public Map<String, Object> post(String path, Map<String, Object> p, String system) {
    String prefix = system + ":";
    if (path.equals("/oa/gettemplatedetail")) {
      String id = JsonUtils.str(p, "template_id");
      var value = templates.get(prefix + id);
      if (value == null && id.equals("demo-expense")) value = expense();
      if (value == null) throw GatewayException.missing("模拟模板不存在，可使用 demo-expense 或在模拟环境创建模板");
      return JsonUtils.read(JsonUtils.write(value));
    }
    if (path.endsWith("create_template") || path.endsWith("update_template")) {
      String id = JsonUtils.str(p, "template_id");
      if (id.isBlank()) id = "mock-template-" + sequence.incrementAndGet();
      if (path.endsWith("update_template") && !templates.containsKey(prefix + id))
        throw GatewayException.missing("模拟模板不存在");
      templates.put(
          prefix + id,
          JsonUtils.obj(
              "errcode",
              0,
              "template_names",
              p.get("template_name"),
              "template_content",
              p.get("template_content")));
      return JsonUtils.obj("errcode", 0, "template_id", id);
    }
    if (path.equals("/oa/applyevent")) {
      String no = "MOCK-" + sequence.incrementAndGet();
      var info =
          JsonUtils.obj(
              "sp_no",
              no,
              "sp_name",
              "模拟审批",
              "sp_status",
              1,
              "template_id",
              p.get("template_id"),
              "apply_time",
              System.currentTimeMillis() / 1000,
              "applyer",
              JsonUtils.obj("userid", p.get("creator_userid")),
              "apply_data",
              p.get("apply_data"),
              "sp_record",
              List.of(),
              "notifyer",
              List.of(),
              "comments",
              List.of());
      approvals.put(prefix + no, info);
      return JsonUtils.obj("errcode", 0, "sp_no", no);
    }
    if (path.equals("/oa/getapprovaldetail")) {
      var value = approvals.get(prefix + JsonUtils.str(p, "sp_no"));
      if (value == null) throw GatewayException.missing("模拟审批单不存在");
      return JsonUtils.obj("errcode", 0, "info", JsonUtils.read(JsonUtils.write(value)));
    }
    if (path.equals("/oa/getapprovalinfo")) {
      long start = Long.parseLong(p.get("starttime").toString()),
          end = Long.parseLong(p.get("endtime").toString());
      var found =
          approvals.entrySet().stream()
              .filter(e -> e.getKey().startsWith(prefix))
              .map(Map.Entry::getValue)
              .filter(
                  v ->
                      ((Number) v.get("apply_time")).longValue() >= start
                          && ((Number) v.get("apply_time")).longValue() <= end)
              .filter(
                  v ->
                      JsonUtils.list(p.get("filters")).stream()
                          .map(JsonUtils::map)
                          .allMatch(
                              f ->
                                  switch (JsonUtils.str(f, "key")) {
                                    case "template_id" ->
                                        Objects.equals(v.get("template_id"), f.get("value"));
                                    case "creator" ->
                                        Objects.equals(
                                            JsonUtils.map(v.get("applyer")).get("userid"),
                                            f.get("value"));
                                    case "sp_status" ->
                                        Objects.toString(v.get("sp_status"), "")
                                            .equals(f.get("value"));
                                    default -> true;
                                  }))
              .sorted(Comparator.comparing(v -> v.get("sp_no").toString()))
              .toList();
      int offset = JsonUtils.integer(p.get("new_cursor"), 0),
          size = JsonUtils.integer(p.get("size"), 20),
          to = Math.min(found.size(), offset + size);
      var out =
          JsonUtils.obj(
              "errcode",
              0,
              "sp_no_list",
              found.subList(Math.min(offset, to), to).stream().map(v -> v.get("sp_no")).toList());
      if (to < found.size()) out.put("new_next_cursor", Integer.toString(to));
      return out;
    }
    if (path.equals("/corp/getopenapprovaldata"))
      return JsonUtils.obj(
          "errcode", 0, "data", JsonUtils.obj("ThirdNo", p.get("thirdNo"), "OpenSpStatus", 1));
    if (path.equals("/oa/vacation/getcorpconf"))
      return JsonUtils.obj(
          "errcode", 0, "lists", List.of(JsonUtils.obj("id", 1, "name", "年假", "time_attr", 1)));
    if (path.equals("/oa/vacation/getuservacationquota"))
      return JsonUtils.obj(
          "errcode",
          0,
          "lists",
          List.of(
              JsonUtils.obj(
                  "id", 1, "assignduration", 28800, "usedduration", 0, "leftduration", 28800)));
    if (path.equals("/oa/vacation/setoneuserquota")) return JsonUtils.obj("errcode", 0);
    if (path.equals("/corp/getapprovaldata"))
      return JsonUtils.obj("errcode", 0, "data", List.of(), "count", 0);
    throw GatewayException.invalid("未知模拟审批接口");
  }

  public static Map<String, Object> expense() {
    return JsonUtils.obj(
        "errcode",
        0,
        "template_names",
        List.of(JsonUtils.obj("text", "通用费用审批（模拟）", "lang", "zh_CN")),
        "template_content",
        JsonUtils.obj(
            "controls",
            List.of(
                control("Text", "Text-01", "事由", true, Map.of()),
                control("Money", "Money-02", "金额", true, Map.of()),
                control(
                    "Selector",
                    "Selector-03",
                    "费用类型",
                    false,
                    JsonUtils.obj(
                        "selector",
                        JsonUtils.obj(
                            "type",
                            "single",
                            "options",
                            List.of(
                                JsonUtils.obj(
                                    "key",
                                    "travel",
                                    "value",
                                    JsonUtils.obj("text", "差旅", "lang", "zh_CN")),
                                JsonUtils.obj(
                                    "key",
                                    "office",
                                    "value",
                                    JsonUtils.obj("text", "办公", "lang", "zh_CN")))))),
                control("File", "File-04", "附件", false, Map.of()))));
  }

  public static Map<String, Object> control(
      String type, String id, String label, boolean required, Map<String, Object> config) {
    return JsonUtils.obj(
        "property",
        JsonUtils.obj(
            "control",
            type,
            "id",
            id,
            "title",
            List.of(JsonUtils.obj("text", label, "lang", "zh_CN")),
            "require",
            required ? 1 : 0),
        "config",
        config);
  }
}

package group.cyt.common.module.adapter.wecom.approval;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.support.CursorCodec;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WecomApprovalService {
  private final WecomApprovalClient client;
  private final GatewayStore store;
  private final GatewaySettings settings;
  private final CursorCodec cursors;
  private final CredentialService credentials;
  private final ApprovalSimulator simulator = new ApprovalSimulator();

  public WecomApprovalService(
      WecomApprovalClient client,
      GatewayStore store,
      GatewaySettings settings,
      CursorCodec cursors,
      CredentialService credentials) {
    this.client = client;
    this.store = store;
    this.settings = settings;
    this.cursors = cursors;
    this.credentials = credentials;
  }

  public boolean supports(String key) {
    return key.startsWith("wecom.approvals.")
        || key.startsWith("wecom.approval_templates.")
        || key.startsWith("wecom.approval_files.")
        || key.startsWith("wecom.approval_events.")
        || key.startsWith("wecom.vacation.");
  }

  public Object invoke(OperationDefinition op, Map<String, Object> p, String system, boolean mock) {
    String key = op.key();
    if (key.equals("wecom.approval_templates.list")) {
      var items =
          store.rows(
              "SELECT alias,template_id,field_aliases,description,updated_at FROM gateway_wecom_form WHERE env=? AND runtime=? AND `system`=? ORDER BY alias",
              settings.current(),
              mode(mock),
              system);
      return JsonUtils.obj(
          "items",
          items,
          "scope",
          "registered_only",
          "note",
          "企微没有列出企业全部审批模板的服务端接口；这里列出本系统已绑定的业务模板",
          "demo_template",
          mock ? "demo-expense" : null);
    }
    if (key.equals("wecom.approval_templates.unbind")) {
      store.update(
          "DELETE FROM gateway_wecom_form WHERE env=? AND runtime=? AND `system`=? AND alias=?",
          settings.current(),
          mode(mock),
          system,
          p.get("alias"));
      store.audit(system, "approval_form_unbind", system + ":" + p.get("alias"), Map.of());
      return JsonUtils.obj("removed", true, "upstream_deleted", false);
    }
    if (key.equals("wecom.approval_templates.create")
        || key.equals("wecom.approval_templates.update")
        || key.equals("wecom.approval_templates.clone")) return saveTemplate(key, p, system, mock);
    if (key.equals("wecom.approval_templates.get") || key.equals("wecom.approval_templates.bind")) {
      var binding = resolve(p, system, mock);
      String id = JsonUtils.str(binding, "template_id");
      var raw = post("/oa/gettemplatedetail", JsonUtils.obj("template_id", id), system, mock);
      var aliases =
          key.endsWith("bind")
              ? JsonUtils.map(p.get("field_aliases"))
              : JsonUtils.map(binding.get("aliases"));
      validateAliases(raw, aliases);
      if (key.endsWith("bind"))
        bind(JsonUtils.str(p, "alias"), id, aliases, JsonUtils.str(p, "description"), system, mock);
      var form = ApprovalFormCodec.describe(raw, aliases);
      form.put("template_id", id);
      form.put("template", binding.get("template"));
      form.put("official", raw);
      return form;
    }
    if (key.equals("wecom.approvals.prepare") || key.equals("wecom.approvals.submit")) {
      var prepared = prepare(p, system, mock);
      if (key.endsWith("prepare")) return prepared;
      if (!JsonUtils.bool(prepared.get("valid")))
        throw GatewayException.invalid("审批字段校验失败").detail(prepared.get("errors"));
      var body = JsonUtils.map(prepared.get("payload"));
      var result = post("/oa/applyevent", body, system, mock);
      String number = JsonUtils.str(result, "sp_no");
      if (number.isBlank())
        throw new GatewayException(
            502, "upstream_error", "企微未返回审批单号，请先确认原提交结果，勿更换 request_id 重试", false);
      store.insert(
          "gateway_wecom_submission",
          JsonUtils.obj(
              "env",
              settings.current(),
              "runtime",
              mode(mock),
              "system",
              system,
              "profile",
              mock ? JsonUtils.sha("mock:" + system) : client.profile(system),
              "approval_no",
              number,
              "template_id",
              body.get("template_id"),
              "request_id",
              p.get("request_id")));
      return JsonUtils.obj(
          "approval_no",
          number,
          "template_id",
          body.get("template_id"),
          "request_id",
          p.get("request_id"),
          "submitted",
          true,
          "status",
          "pending",
          "status_label",
          "审批中");
    }
    if (key.equals("wecom.approvals.track")) {
      var submission =
          store.one(
              "SELECT * FROM gateway_wecom_submission WHERE env=? AND runtime=? AND `system`=? AND request_id=?",
              settings.current(),
              mode(mock),
              system,
              p.get("request_id"));
      if (submission == null || submission.isEmpty())
        throw GatewayException.missing("本系统没有此请求号的已记录审批单；请核对原提交结果，勿换号重复提交");
      String profile = mock ? JsonUtils.sha("mock:" + system) : client.profile(system);
      if (!Objects.equals(profile, submission.get("profile")))
        throw new GatewayException(409, "credential_changed", "上游应用凭据已变更，请先核对原审批所属应用", false);
      var result = detail(JsonUtils.str(submission, "approval_no"), system, mock);
      result.put("request_id", p.get("request_id"));
      return result;
    }
    if (key.equals("wecom.approvals.get"))
      return detail(JsonUtils.str(p, "approval_no"), system, mock);
    if (key.equals("wecom.approvals.get_many"))
      return details(JsonUtils.list(p.get("approval_nos")), system, mock);
    if (key.equals("wecom.approvals.list") || key.equals("wecom.approvals.collect"))
      return query(p, system, mock, key.endsWith("collect"));
    if (key.equals("wecom.approvals.engine_get")) {
      var raw =
          post(
              "/corp/getopenapprovaldata",
              JsonUtils.obj("thirdNo", p.get("third_no")),
              system,
              mock);
      return JsonUtils.obj(
          "third_no", p.get("third_no"), "engine", "self_built_js_sdk", "data", raw.get("data"));
    }
    if (key.equals("wecom.approvals.engine_launch")) {
      var fields = new ArrayList<Object>();
      JsonUtils.map(p.get("fields"))
          .forEach(
              (name, value) ->
                  fields.add(
                      JsonUtils.obj(
                          "title", name, "type", "text", "value", Objects.toString(value, ""))));
      return JsonUtils.obj(
          "client_api",
          "thirdPartyOpenPage",
          "requires_js_sdk",
          true,
          "submitted",
          false,
          "params",
          JsonUtils.obj(
              "oaType",
              JsonUtils.str(p, "mode", "create").equals("view") ? "10002" : "10001",
              "templateId",
              p.get("template_id"),
              "thirdNo",
              p.get("third_no"),
              "extData",
              JsonUtils.obj("fieldList", fields)),
          "note",
          "请在企微客户端完成 config/agentConfig 后调用返回的 JS-SDK 参数；本接口不在服务端代替员工发起引擎审批");
    }
    if (key.equals("wecom.approvals.legacy_get")) {
      long start = ApprovalFormCodec.epoch(p.get("start_at")),
          end = ApprovalFormCodec.epoch(p.get("end_at"));
      if (start >= end || end - start > 30L * 86400)
        throw GatewayException.invalid("旧版审批查询需要正向且不超过 30 天的时间范围");
      return post(
          "/corp/getapprovaldata",
          JsonUtils.obj(
              "starttime", start, "endtime", end, "next_spnum", p.getOrDefault("next_spnum", 0)),
          system,
          mock);
    }
    if (key.equals("wecom.approval_files.upload")) {
      byte[] bytes;
      try {
        bytes = Base64.getDecoder().decode(JsonUtils.str(p, "content_base64"));
      } catch (Exception e) {
        throw GatewayException.invalid("附件 Base64 无效");
      }
      WecomApprovalClient.validateUpload(JsonUtils.str(p, "filename"), bytes);
      var raw =
          mock
              ? JsonUtils.obj(
                  "media_id",
                  "mock-file-"
                      + HexFormat.of()
                          .formatHex(JsonUtils.digest("SHA-256", bytes))
                          .substring(0, 12),
                  "created_at",
                  Long.toString(Instant.now().getEpochSecond()))
              : client.upload(JsonUtils.str(p, "filename"), bytes, system);
      return JsonUtils.obj(
          "file_id",
          raw.get("media_id"),
          "filename",
          p.get("filename"),
          "created_at",
          raw.get("created_at"),
          "temporary",
          true,
          "note",
          "请在临时素材有效期内提单，提单后由企微转为单据长期附件");
    }
    if (key.equals("wecom.approval_files.download")) {
      if (mock)
        return JsonUtils.obj(
            "file_id",
            p.get("file_id"),
            "content_type",
            "text/plain",
            "size",
            7,
            "content_base64",
            Base64.getEncoder()
                .encodeToString("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return client.download(JsonUtils.str(p, "file_id"), system);
    }
    if (key.equals("wecom.approval_events.list")) {
      if (mock)
        return JsonUtils.obj(
            "items",
            List.of(),
            "next_after_id",
            p.getOrDefault("after_id", 0),
            "delivery",
            "at_least_once",
            "deduplicate_by",
            "event_id");
      var rows =
          store.rows(
              "SELECT id,event_id,approval_no,payload_ciphertext,delivery_state,attempts,created_at FROM gateway_wecom_event WHERE env=? AND `system`=? AND id>? ORDER BY id LIMIT ?",
              settings.current(),
              system,
              p.getOrDefault("after_id", 0),
              p.getOrDefault("limit", 50));
      var items = new ArrayList<Object>();
      for (var row : rows) {
        var item = new LinkedHashMap<>(row);
        item.put(
            "event",
            JsonUtils.read(credentials.decrypt(JsonUtils.str(item, "payload_ciphertext"))));
        item.remove("payload_ciphertext");
        items.add(item);
      }
      return JsonUtils.obj(
          "items",
          items,
          "next_after_id",
          rows.isEmpty() ? p.getOrDefault("after_id", 0) : rows.getLast().get("id"),
          "delivery",
          "at_least_once",
          "deduplicate_by",
          "event_id");
    }
    if (key.equals("wecom.approval_events.retry")) {
      if (mock)
        return JsonUtils.obj(
            "queued", false, "event_id", p.get("event_id"), "note", "模拟模式不会重投真实事件");
      int updated =
          store.update(
              "UPDATE gateway_wecom_event SET delivery_state='pending',attempts=0,last_error='',next_attempt_at=UTC_TIMESTAMP(6) WHERE env=? AND `system`=? AND event_id=? AND delivery_state IN ('failed','poll_only')",
              settings.current(),
              system,
              p.get("event_id"));
      return JsonUtils.obj("queued", updated > 0, "event_id", p.get("event_id"));
    }
    if (key.startsWith("wecom.vacation.")) return vacation(key, p, system, mock);
    throw GatewayException.invalid("未知审批能力");
  }

  private Map<String, Object> post(
      String path, Map<String, Object> body, String system, boolean mock) {
    return mock ? simulator.post(path, body, system) : client.post(path, body, system);
  }

  private static String mode(boolean mock) {
    return mock ? "mock" : "live";
  }

  private Map<String, Object> resolve(Map<String, Object> p, String system, boolean mock) {
    String template = JsonUtils.str(p, "template");
    if (template.isBlank()) template = JsonUtils.str(p, "template_id");
    if (template.isBlank()) throw GatewayException.invalid("需要 template（业务别名或企微模板 ID）");
    var binding =
        store.one(
            "SELECT template_id,field_aliases FROM gateway_wecom_form WHERE env=? AND runtime=? AND `system`=? AND alias=?",
            settings.current(),
            mode(mock),
            system,
            template);
    return binding == null || binding.isEmpty()
        ? JsonUtils.obj("template", template, "template_id", template, "aliases", Map.of())
        : JsonUtils.obj(
            "template",
            template,
            "template_id",
            binding.get("template_id"),
            "aliases",
            JsonUtils.read(JsonUtils.str(binding, "field_aliases")));
  }

  private void bind(
      String alias,
      String templateId,
      Map<String, Object> aliases,
      String description,
      String system,
      boolean mock) {
    if (!alias.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}"))
      throw GatewayException.invalid("模板别名需为 1–64 位字母、数字、下划线或连字符，并以字母开头");
    store.update(
        "INSERT INTO gateway_wecom_form (env,runtime,`system`,alias,template_id,field_aliases,description) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE template_id=VALUES(template_id),field_aliases=VALUES(field_aliases),description=VALUES(description),updated_at=UTC_TIMESTAMP(6)",
        settings.current(),
        mode(mock),
        system,
        alias,
        templateId,
        JsonUtils.write(aliases),
        description);
    store.audit(
        system,
        "approval_form_bind",
        system + ":" + alias,
        JsonUtils.obj(
            "template_id", templateId, "field_names", aliases.keySet(), "runtime", mode(mock)));
  }

  private void validateAliases(Map<String, Object> template, Map<String, Object> aliases) {
    Set<String> ids = new HashSet<>();
    collectIds(ApprovalFormCodec.controls(template), ids);
    Set<String> mapped = new HashSet<>();
    for (var e : aliases.entrySet())
      if (!e.getKey().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
          || !(e.getValue() instanceof String id)
          || !ids.contains(id)
          || !mapped.add(id)) throw GatewayException.invalid("字段别名必须唯一，并明确对应模板中存在的控件 ID");
  }

  private void collectIds(List<Object> controls, Set<String> ids) {
    for (Object item : controls) {
      var c = JsonUtils.map(item);
      ids.add(JsonUtils.str(JsonUtils.map(c.get("property")), "id"));
      collectIds(
          JsonUtils.list(
              JsonUtils.map(JsonUtils.map(c.get("config")).get("table")).get("children")),
          ids);
    }
  }

  private Map<String, Object> prepare(Map<String, Object> p, String system, boolean mock) {
    var binding = resolve(p, system, mock);
    String templateId = JsonUtils.str(binding, "template_id");
    var template =
        post("/oa/gettemplatedetail", JsonUtils.obj("template_id", templateId), system, mock);
    var aliases = JsonUtils.map(binding.get("aliases"));
    var description = ApprovalFormCodec.describe(template, aliases);
    String expected = JsonUtils.str(p, "template_version");
    if (!expected.isBlank() && !expected.equals(description.get("version")))
      throw new GatewayException(409, "template_changed", "模板已变更，请重新预检后提交", false);
    try {
      var values = JsonUtils.map(p.get("fields"));
      if (p.get("apply_data") != null) {
        if (p.containsKey("fields")) throw GatewayException.invalid("fields 与 apply_data 只能提供一种");
        values = new LinkedHashMap<>();
        for (Object item : JsonUtils.list(JsonUtils.map(p.get("apply_data")).get("contents"))) {
          var c = JsonUtils.map(item);
          String id = JsonUtils.str(c, "id");
          if (values.put(id, JsonUtils.obj("$value", c.get("value"))) != null)
            throw GatewayException.invalid("apply_data 包含重复控件");
        }
      }
      var data = ApprovalFormCodec.encode(template, values, aliases);
      String applicant = JsonUtils.str(p, "applicant");
      if (applicant.isBlank()) throw GatewayException.invalid("需要申请人 applicant（成员 userid）");
      var body =
          JsonUtils.obj(
              "template_id",
              templateId,
              "creator_userid",
              applicant,
              "use_template_approver",
              1,
              "apply_data",
              data);
      if (p.get("department_id") != null) body.put("choose_department", p.get("department_id"));
      var flow = JsonUtils.list(p.get("flow"));
      if (!flow.isEmpty()) {
        var nodes = new ArrayList<Object>();
        boolean approver = false;
        for (Object item : flow) {
          var node = JsonUtils.map(item);
          String type = JsonUtils.str(node, "type", "approve");
          var users = JsonUtils.list(node.get("users"));
          if (users.isEmpty()) throw GatewayException.invalid("流程节点需要非空 users 列表");
          int nodeType =
              switch (type) {
                case "approve" -> 1;
                case "notify" -> 2;
                case "handle" -> 3;
                default -> throw GatewayException.invalid("流程节点 type 无效");
              };
          var nativeNode = JsonUtils.obj("type", nodeType, "userid", users);
          if (nodeType != 2) {
            approver = true;
            nativeNode.put(
                "apv_rel",
                switch (JsonUtils.str(node, "mode", "all")) {
                  case "all" -> 1;
                  case "any" -> 2;
                  case "sequential" -> 3;
                  default -> throw GatewayException.invalid("流程 mode 仅支持 all、any、sequential");
                });
          }
          nodes.add(nativeNode);
        }
        if (!approver) throw GatewayException.invalid("自定义流程至少需要一个审批人或办理人节点");
        body.put("use_template_approver", 0);
        body.put("process", JsonUtils.obj("node_list", nodes));
      }
      List<Object> summary = JsonUtils.list(p.get("summary"));
      if (summary.isEmpty()) {
        summary = new ArrayList<>();
        for (Object item : JsonUtils.list(data.get("contents"))) {
          var c = JsonUtils.map(item);
          var v = JsonUtils.map(c.get("value"));
          Object display = v.getOrDefault("text", v.getOrDefault("new_money", v.get("new_number")));
          if (display != null) summary.add(shortText(display.toString(), 20));
          if (summary.size() == 3) break;
        }
        if (summary.isEmpty())
          summary.add(shortText(Objects.toString(description.get("name"), "审批申请"), 20));
      }
      if (summary.size() > 3
          || summary.stream()
              .anyMatch(s -> s.toString().codePointCount(0, s.toString().length()) > 20))
        throw GatewayException.invalid("摘要最多 3 行，每行最多 20 个字符");
      body.put(
          "summary_list",
          summary.stream()
              .map(
                  s ->
                      JsonUtils.obj(
                          "summary_info", List.of(JsonUtils.obj("text", s, "lang", "zh_CN"))))
              .toList());
      return JsonUtils.obj(
          "valid",
          true,
          "errors",
          List.of(),
          "template_id",
          templateId,
          "template_version",
          description.get("version"),
          "payload",
          body,
          "uses_template_flow",
          flow.isEmpty());
    } catch (GatewayException e) {
      return JsonUtils.obj(
          "valid",
          false,
          "template_id",
          templateId,
          "template_version",
          description.get("version"),
          "errors",
          e.detail == null ? List.of(JsonUtils.obj("message", e.getMessage())) : e.detail);
    }
  }

  private static String shortText(String value, int max) {
    return value.codePointCount(0, value.length()) <= max
        ? value
        : value.substring(0, value.offsetByCodePoints(0, max));
  }

  private Object saveTemplate(
      String operation, Map<String, Object> p, String system, boolean mock) {
    if (!operation.endsWith("clone")
        && p.containsKey("template_content")
        && p.containsKey("fields"))
      throw GatewayException.invalid("fields 与 template_content 只能提供一种，避免覆盖错误的模板结构");
    String requestedAlias = JsonUtils.str(p, "alias");
    if (!requestedAlias.isBlank() && !requestedAlias.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}"))
      throw GatewayException.invalid("模板别名必须以字母开头，只能包含字母、数字、下划线或连字符");
    String templateId = "";
    Map<String, Object> definition;
    Map<String, Object> aliases = new LinkedHashMap<>();
    if (operation.endsWith("clone")) {
      var source = resolve(p, system, mock);
      definition =
          post(
              "/oa/gettemplatedetail",
              JsonUtils.obj("template_id", source.get("template_id")),
              system,
              mock);
      definition =
          JsonUtils.obj(
              "template_name",
              List.of(JsonUtils.obj("text", p.get("name"), "lang", "zh_CN")),
              "template_content",
              definition.get("template_content"));
      aliases.putAll(JsonUtils.map(source.get("aliases")));
    } else {
      List<Object> existing = List.of();
      Map<String, Object> oldAliases = Map.of();
      if (operation.endsWith("update")) {
        var binding = resolve(p, system, mock);
        templateId = JsonUtils.str(binding, "template_id");
        oldAliases = JsonUtils.map(binding.get("aliases"));
        existing =
            ApprovalFormCodec.controls(
                post(
                    "/oa/gettemplatedetail",
                    JsonUtils.obj("template_id", templateId),
                    system,
                    mock));
      }
      if (p.get("template_content") != null)
        definition =
            JsonUtils.obj(
                "template_name",
                List.of(JsonUtils.obj("text", p.get("name"), "lang", "zh_CN")),
                "template_content",
                p.get("template_content"));
      else
        definition =
            JsonUtils.obj(
                "template_name",
                List.of(JsonUtils.obj("text", p.get("name"), "lang", "zh_CN")),
                "template_content",
                JsonUtils.obj(
                    "controls",
                    buildControls(JsonUtils.list(p.get("fields")), aliases, existing, oldAliases)));
    }
    if (!templateId.isBlank()) definition.put("template_id", templateId);
    var result =
        post(
            templateId.isBlank() ? "/oa/approval/create_template" : "/oa/approval/update_template",
            definition,
            system,
            mock);
    if (templateId.isBlank()) templateId = JsonUtils.str(result, "template_id");
    if (templateId.isBlank())
      throw new GatewayException(502, "upstream_error", "企微未返回模板 ID，请先核对创建结果", false);
    if (!JsonUtils.str(p, "alias").isBlank())
      bind(
          JsonUtils.str(p, "alias"),
          templateId,
          aliases,
          JsonUtils.str(p, "description"),
          system,
          mock);
    return JsonUtils.obj(
        "template_id",
        templateId,
        "alias",
        p.get("alias"),
        "field_aliases",
        aliases,
        "flow_note",
        operation.endsWith("update") ? "企微保留已有审批流程和规则" : "新模板使用企微默认流程，请在企微管理后台确认审批人及规则");
  }

  private List<Object> buildControls(
      List<Object> fields,
      Map<String, Object> aliases,
      List<Object> existing,
      Map<String, Object> oldAliases) {
    if (fields.isEmpty() || fields.size() > 100)
      throw GatewayException.invalid("模板需要 1–100 个字段，或显式提供官方 template_content");
    var controls = new ArrayList<Object>();
    for (Object item : fields) {
      var f = JsonUtils.map(item);
      String key = JsonUtils.str(f, "key"),
          label = JsonUtils.str(f, "label"),
          type = JsonUtils.str(f, "control", "Text");
      if (!key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
          || label.isBlank()
          || label.codePointCount(0, label.length()) > 40
          || aliases.containsKey(key)) throw GatewayException.invalid("模板字段需要唯一 key 和 label");
      if (!Set.of(
              "Text",
              "Textarea",
              "Number",
              "Money",
              "Date",
              "Selector",
              "Contact",
              "Tips",
              "File",
              "Table",
              "DateRange",
              "Vacation",
              "Attendance",
              "Location",
              "RelatedApproval",
              "BankAccount",
              "PhoneNumber")
          .contains(type)) throw GatewayException.invalid("模板控件类型无效");
      var candidates =
          existing.stream()
              .map(JsonUtils::map)
              .filter(
                  c -> {
                    var prop = JsonUtils.map(c.get("property"));
                    return Objects.equals(prop.get("id"), oldAliases.get(key))
                        || label.equals(ApprovalFormCodec.text(prop.get("title")));
                  })
              .toList();
      if (candidates.size() > 1 && !f.containsKey("id"))
        throw GatewayException.invalid("已有模板字段名不唯一，更新时请显式提供控件 id");
      Map<String, Object> previous = candidates.size() == 1 ? candidates.getFirst() : Map.of();
      String id = JsonUtils.str(f, "id");
      if (id.isBlank() && !previous.isEmpty())
        id = JsonUtils.str(JsonUtils.map(previous.get("property")), "id");
      if (id.isBlank())
        id = type + "-" + Long.parseUnsignedLong(JsonUtils.sha(key).substring(0, 12), 16);
      if (!id.matches(java.util.regex.Pattern.quote(type) + "-\\d+"))
        throw GatewayException.invalid("控件 id 必须为对应控件类型-数字；变更已有控件类型请新建字段");
      if (aliases.containsValue(id)) throw GatewayException.invalid("模板内控件 id 不能重复");
      aliases.put(key, id);
      var config = new LinkedHashMap<>(JsonUtils.map(f.get("config")));
      if (type.equals("Selector")) {
        var options = new ArrayList<Object>();
        int index = 0;
        for (Object v : JsonUtils.list(f.get("options"))) {
          var o = JsonUtils.map(v);
          options.add(
              JsonUtils.obj(
                  "key",
                  o.getOrDefault("key", "option-" + ++index),
                  "value",
                  JsonUtils.obj(
                      "text", v instanceof String ? v : o.get("label"), "lang", "zh_CN")));
        }
        if (options.isEmpty()) throw GatewayException.invalid("选择控件需要 options");
        config.put(
            "selector",
            JsonUtils.obj(
                "type",
                JsonUtils.bool(f.get("multiple")) ? "multi" : "single",
                "options",
                options));
      }
      if (type.equals("Table"))
        config.put(
            "table",
            JsonUtils.obj(
                "children",
                buildControls(
                    JsonUtils.list(f.get("children")),
                    aliases,
                    JsonUtils.list(
                        JsonUtils.map(JsonUtils.map(previous.get("config")).get("table"))
                            .get("children")),
                    oldAliases)));
      if (type.equals("Date")) config.putIfAbsent("date", JsonUtils.obj("type", "day"));
      if (type.equals("Contact"))
        config.putIfAbsent("contact", JsonUtils.obj("type", "multi", "mode", "user"));
      controls.add(
          ApprovalSimulator.control(type, id, label, JsonUtils.bool(f.get("required")), config));
    }
    return controls;
  }

  private Map<String, Object> detail(String no, String system, boolean mock) {
    var response = post("/oa/getapprovaldetail", JsonUtils.obj("sp_no", no), system, mock);
    var info = JsonUtils.map(response.get("info"));
    if (info.isEmpty()) throw new GatewayException(502, "upstream_error", "企微未返回审批详情", false);
    int code = JsonUtils.integer(info.get("sp_status"), 0);
    var status = status(code);
    var result =
        JsonUtils.obj(
            "approval_no",
            info.getOrDefault("sp_no", no),
            "name",
            info.get("sp_name"),
            "template_id",
            info.get("template_id"),
            "status_code",
            code,
            "status",
            status.get("status"),
            "status_label",
            status.get("label"),
            "terminal",
            status.get("terminal"),
            "applicant",
            JsonUtils.map(info.get("applyer")).get("userid"),
            "submitted_at",
            info.get("apply_time"),
            "fields",
            normalizeContents(
                JsonUtils.list(JsonUtils.map(info.get("apply_data")).get("contents"))),
            "timeline",
            info.getOrDefault("sp_record", List.of()),
            "notify",
            info.getOrDefault("notifyer", List.of()),
            "comments",
            info.getOrDefault("comments", List.of()),
            "official",
            info);
    var steps = new ArrayList<Object>();
    var pending = new LinkedHashSet<String>();
    int position = 0;
    for (Object record : JsonUtils.list(info.get("sp_record"))) {
      var node = JsonUtils.map(record);
      var people = new ArrayList<Object>();
      int nodeCode = JsonUtils.integer(node.get("sp_status"), 0);
      for (Object branch : JsonUtils.list(node.get("details"))) {
        var person = JsonUtils.map(branch);
        String userid = JsonUtils.str(JsonUtils.map(person.get("approver")), "userid");
        int personCode = JsonUtils.integer(person.get("sp_status"), 0);
        if (code == 1 && nodeCode == 1 && personCode == 1 && !userid.isBlank()) pending.add(userid);
        people.add(
            JsonUtils.obj(
                "userid",
                userid,
                "status_code",
                personCode,
                "status",
                stepStatus(personCode),
                "comment",
                person.get("speech"),
                "acted_at",
                person.get("sptime"),
                "attachments",
                person.getOrDefault("media_id", List.of())));
      }
      int attr = JsonUtils.integer(node.get("approverattr"), 0);
      steps.add(
          JsonUtils.obj(
              "position",
              ++position,
              "status_code",
              nodeCode,
              "status",
              stepStatus(nodeCode),
              "mode",
              attr == 1 ? "any" : attr == 2 ? "all" : "unknown",
              "approvers",
              people));
    }
    result.put("steps", steps);
    result.put("pending_approvers", new ArrayList<>(pending));
    result.put(
        "notes",
        JsonUtils.list(info.get("comments")).stream()
            .map(JsonUtils::map)
            .map(
                c ->
                    JsonUtils.obj(
                        "id",
                        c.get("commentid"),
                        "userid",
                        JsonUtils.map(c.get("commentUserInfo")).get("userid"),
                        "text",
                        c.get("commentcontent"),
                        "created_at",
                        c.get("commenttime"),
                        "attachments",
                        c.getOrDefault("media_id", List.of())))
            .toList());
    var forms = new ArrayList<Object>();
    for (var binding :
        store.rows(
            "SELECT alias,field_aliases FROM gateway_wecom_form WHERE env=? AND runtime=? AND `system`=? AND template_id=? ORDER BY alias",
            settings.current(),
            mode(mock),
            system,
            info.get("template_id"))) {
      var aliases = JsonUtils.map(JsonUtils.read(JsonUtils.str(binding, "field_aliases", "{}")));
      forms.add(
          JsonUtils.obj(
              "template",
              binding.get("alias"),
              "values",
              businessValues(JsonUtils.list(result.get("fields")), aliases)));
    }
    result.put("business_forms", forms);
    return result;
  }

  // Node status 4 means transferred, unlike the application status 4 (cancelled).
  private static String stepStatus(int code) {
    return switch (code) {
      case 1 -> "pending";
      case 2 -> "approved";
      case 3 -> "rejected";
      case 4 -> "transferred";
      case 11 -> "returned";
      case 12 -> "added_approver";
      case 13 -> "approved_and_added_approver";
      default -> "unknown";
    };
  }

  private static Map<String, Object> businessValues(
      List<Object> fields, Map<String, Object> aliases) {
    var result = new LinkedHashMap<String, Object>();
    for (Object item : fields) {
      var field = JsonUtils.map(item);
      String id = JsonUtils.str(field, "id");
      String key =
          aliases.entrySet().stream()
              .filter(e -> id.equals(e.getValue()))
              .map(Map.Entry::getKey)
              .findFirst()
              .orElse(id);
      Object value = field.get("value");
      if ("Table".equals(field.get("control")))
        value =
            JsonUtils.list(value).stream()
                .map(row -> businessValues(JsonUtils.list(row), aliases))
                .toList();
      result.put(key, value);
    }
    return result;
  }

  public static Map<String, Object> status(int code) {
    String[] v =
        switch (code) {
          case 1 -> new String[] {"pending", "审批中"};
          case 2 -> new String[] {"approved", "已通过"};
          case 3 -> new String[] {"rejected", "已驳回"};
          case 4 -> new String[] {"cancelled", "已撤销"};
          case 6 -> new String[] {"approval_revoked", "通过后撤销"};
          case 7 -> new String[] {"deleted", "已删除"};
          case 10 -> new String[] {"paid", "已支付"};
          default -> new String[] {"unknown", "未知状态"};
        };
    return JsonUtils.obj(
        "status", v[0], "label", v[1], "terminal", Set.of(2, 3, 4, 6, 7, 10).contains(code));
  }

  private List<Object> normalizeContents(List<Object> contents) {
    var result = new ArrayList<Object>();
    for (Object item : contents) {
      var c = JsonUtils.map(item);
      var value = JsonUtils.map(c.get("value"));
      Object friendly = value;
      for (String key :
          List.of(
              "text",
              "new_number",
              "new_money",
              "date",
              "members",
              "departments",
              "files",
              "related_approval",
              "location",
              "formula",
              "date_range",
              "attendance",
              "vacation"))
        if (value.containsKey(key)) {
          friendly = value.get(key);
          break;
        }
      if (value.containsKey("selector"))
        friendly =
            JsonUtils.list(JsonUtils.map(value.get("selector")).get("options")).stream()
                .map(JsonUtils::map)
                .map(
                    o ->
                        JsonUtils.obj(
                            "key", o.get("key"), "label", ApprovalFormCodec.text(o.get("value"))))
                .toList();
      if (value.containsKey("children"))
        friendly =
            JsonUtils.list(value.get("children")).stream()
                .map(JsonUtils::map)
                .map(r -> normalizeContents(JsonUtils.list(r.get("list"))))
                .toList();
      result.add(
          JsonUtils.obj(
              "id",
              c.get("id"),
              "label",
              ApprovalFormCodec.text(c.get("title")),
              "control",
              c.get("control"),
              "value",
              friendly));
    }
    return result;
  }

  private Map<String, Object> details(List<Object> nos, String system, boolean mock) {
    var items = new ArrayList<Object>();
    var errors = new ArrayList<Object>();
    for (Object no : new LinkedHashSet<>(nos))
      try {
        items.add(detail(no.toString(), system, mock));
      } catch (GatewayException e) {
        errors.add(
            JsonUtils.obj(
                "approval_no",
                no,
                "code",
                e.code == null ? e.type : e.code,
                "message",
                e.getMessage()));
      }
    return JsonUtils.obj(
        "items",
        items,
        "errors",
        errors,
        "summary",
        JsonUtils.obj("requested", nos.size(), "succeeded", items.size(), "failed", errors.size()));
  }

  private Object query(Map<String, Object> p, String system, boolean mock, boolean collect) {
    String scope = settings.current() + ":" + mode(mock) + ":" + system + ":wecom-approvals";
    var state = cursors.decode(scope, JsonUtils.str(p, "cursor"));
    long end =
        p.get("end_at") != null
            ? ApprovalFormCodec.epoch(p.get("end_at"))
            : state.containsKey("end")
                ? ((Number) state.get("end")).longValue()
                : Instant.now().getEpochSecond();
    long start =
        p.get("start_at") != null
            ? ApprovalFormCodec.epoch(p.get("start_at"))
            : state.containsKey("start")
                ? ((Number) state.get("start")).longValue()
                : end - 7 * 86400;
    if (end <= start || end - start > 366L * 86400)
      throw GatewayException.invalid("查询范围须为正数且不超过 366 天；网关自动按不超过 31 天拆分");
    var filters = new ArrayList<Object>();
    String template = JsonUtils.str(p, "template");
    if (!template.isBlank())
      filters.add(
          JsonUtils.obj(
              "key", "template_id", "value", resolve(p, system, mock).get("template_id")));
    if (!JsonUtils.str(p, "applicant").isBlank())
      filters.add(JsonUtils.obj("key", "creator", "value", p.get("applicant")));
    if (p.get("status") != null) {
      int code = statusCode(p.get("status"));
      filters.add(JsonUtils.obj("key", "sp_status", "value", Integer.toString(code)));
    }
    for (Object department : JsonUtils.list(p.get("department_ids")))
      filters.add(JsonUtils.obj("key", "department", "value", department.toString()));
    if (p.get("record_type") != null)
      filters.add(JsonUtils.obj("key", "record_type", "value", p.get("record_type").toString()));
    String fingerprint =
        JsonUtils.sha(
            JsonUtils.write(JsonUtils.obj("start", start, "end", end, "filters", filters)));
    if (!state.isEmpty() && !fingerprint.equals(state.get("query")))
      throw GatewayException.invalid("游标与当前筛选条件不匹配");
    long window = state.containsKey("window") ? ((Number) state.get("window")).longValue() : start;
    String cursor = JsonUtils.str(state, "upstream");
    int pageSize = JsonUtils.integer(p.get("page_size"), 20),
        max = collect ? JsonUtils.integer(p.get("max_items"), 100) : pageSize,
        maxPages = collect ? JsonUtils.integer(p.get("max_pages"), 10) : 1;
    var ids = new LinkedHashSet<Object>();
    int pages = 0;
    boolean more = true;
    Set<String> visited = new HashSet<>();
    while (more && pages < maxPages && ids.size() < max) {
      long windowEnd = Math.min(end, window + 31L * 86400);
      String marker = window + ":" + cursor;
      if (!visited.add(marker))
        throw new GatewayException(502, "upstream_error", "企微返回重复游标，已停止翻页以避免死循环", false);
      var response =
          post(
              "/oa/getapprovalinfo",
              JsonUtils.obj(
                  "starttime",
                  Long.toString(window),
                  "endtime",
                  Long.toString(windowEnd),
                  "new_cursor",
                  cursor,
                  "size",
                  Math.min(pageSize, max - ids.size()),
                  "filters",
                  filters),
              system,
              mock);
      var found = JsonUtils.list(response.get("sp_no_list"));
      if (found.size() > Math.min(pageSize, max - ids.size()))
        throw new GatewayException(502, "upstream_error", "企微返回数量超出请求上限，已停止以避免丢失分页位置", false);
      ids.addAll(found);
      pages++;
      String next = JsonUtils.str(response, "new_next_cursor");
      if (!next.isBlank()) {
        if (next.equals(cursor))
          throw new GatewayException(502, "upstream_error", "企微分页游标未推进", false);
        cursor = next;
      } else if (windowEnd < end) {
        window = windowEnd;
        cursor = "";
      } else more = false;
    }
    String next =
        more
            ? cursors.encode(
                scope,
                JsonUtils.obj(
                    "start",
                    start,
                    "end",
                    end,
                    "query",
                    fingerprint,
                    "window",
                    window,
                    "upstream",
                    cursor))
            : null;
    Map<String, Object> result =
        JsonUtils.obj(
            "approval_nos",
            new ArrayList<>(ids),
            "pages",
            pages,
            "page",
            JsonUtils.obj("has_more", more, "next_cursor", next, "count", ids.size()),
            "partial",
            more,
            "deduplicate_by",
            "approval_no");
    if (JsonUtils.bool(p.getOrDefault("include_details", true)))
      result.putAll(details(new ArrayList<>(ids), system, mock));
    else result.put("items", ids.stream().map(no -> JsonUtils.obj("approval_no", no)).toList());
    if (!JsonUtils.list(result.get("errors")).isEmpty()) result.put("partial", true);
    return result;
  }

  private int statusCode(Object value) {
    String s = value.toString();
    if (s.matches("1|2|3|4|6|7|10")) return Integer.parseInt(s);
    return switch (s) {
      case "pending" -> 1;
      case "approved" -> 2;
      case "rejected" -> 3;
      case "cancelled" -> 4;
      case "approval_revoked" -> 6;
      case "deleted" -> 7;
      case "paid" -> 10;
      default -> throw GatewayException.invalid("审批状态无效");
    };
  }

  private Object vacation(String key, Map<String, Object> p, String system, boolean mock) {
    if (key.endsWith("config")) {
      var result = post("/oa/vacation/getcorpconf", Map.of(), system, mock);
      result.put("items", vacationRules(result));
      return result;
    }
    if (key.endsWith("get")) {
      var raw =
          post(
              "/oa/vacation/getuservacationquota",
              JsonUtils.obj("userid", p.get("userid")),
              system,
              mock);
      var errors = new ArrayList<Object>();
      var rules = new ArrayList<Map<String, Object>>();
      try {
        rules.addAll(vacationRules(post("/oa/vacation/getcorpconf", Map.of(), system, mock)));
      } catch (GatewayException e) {
        errors.add(JsonUtils.obj("source", "vacation_config", "code", e.type));
      }
      return JsonUtils.obj(
          "userid",
          p.get("userid"),
          "partial",
          !errors.isEmpty(),
          "errors",
          errors,
          "items",
          JsonUtils.list(raw.get("lists")).stream()
              .map(JsonUtils::map)
              .map(
                  v -> {
                    var rule =
                        rules.stream()
                            .filter(
                                r ->
                                    Objects.toString(r.get("vacation_id"))
                                        .equals(Objects.toString(v.get("id"))))
                            .findFirst()
                            .orElse(Map.of());
                    String unit = JsonUtils.str(rule, "unit", "unknown");
                    var item =
                        JsonUtils.obj(
                            "vacation_id",
                            v.get("id"),
                            "name",
                            v.get("vacationname"),
                            "remaining_seconds",
                            v.get("leftduration"),
                            "assigned_seconds",
                            v.get("assignduration"),
                            "used_seconds",
                            v.get("usedduration"),
                            "official",
                            v);
                    item.put("unit", unit);
                    item.put("rule", rule);
                    item.put(
                        "remaining",
                        unit.equals("unknown") || v.get("leftduration") == null
                            ? null
                            : new BigDecimal(v.get("leftduration").toString())
                                .divide(
                                    BigDecimal.valueOf(unit.equals("days") ? 86400 : 3600),
                                    java.math.MathContext.DECIMAL64));
                    return item;
                  })
              .toList());
    }
    if (!JsonUtils.bool(p.get("confirm"))) throw GatewayException.invalid("修改假期余额需要 confirm=true");
    String unit = JsonUtils.str(p, "unit");
    var rule =
        vacationRules(post("/oa/vacation/getcorpconf", Map.of(), system, mock)).stream()
            .filter(
                r ->
                    Objects.toString(r.get("vacation_id"))
                        .equals(Objects.toString(p.get("vacation_id"))))
            .findFirst()
            .orElseThrow(() -> GatewayException.invalid("企业假期配置中没有该 vacation_id"));
    if (!unit.equals(rule.get("unit")))
      throw GatewayException.invalid("余额单位与企业假期规则不一致，应使用 " + rule.get("unit"));
    long seconds;
    try {
      seconds =
          new BigDecimal(p.get("remaining").toString())
              .multiply(BigDecimal.valueOf(unit.equals("days") ? 86400 : 3600))
              .longValueExact();
    } catch (Exception e) {
      throw GatewayException.invalid("假期余额不是有效的时间数值");
    }
    int step = unit.equals("days") ? 8640 : 360;
    if (seconds < 0 || seconds > 86400000L || seconds % step != 0)
      throw GatewayException.invalid("余额需为 0.1 天/小时的整倍数，且不能超过 1000 天");
    var raw =
        post(
            "/oa/vacation/setoneuserquota",
            JsonUtils.obj(
                "userid",
                p.get("userid"),
                "vacation_id",
                p.get("vacation_id"),
                "leftduration",
                seconds,
                "time_attr",
                unit.equals("days") ? 0 : 1,
                "remarks",
                p.getOrDefault("remarks", "")),
            system,
            mock);
    return JsonUtils.obj(
        "updated",
        true,
        "userid",
        p.get("userid"),
        "remaining",
        p.get("remaining"),
        "unit",
        unit,
        "official",
        raw);
  }

  private static List<Map<String, Object>> vacationRules(Map<String, Object> response) {
    return JsonUtils.list(response.get("lists")).stream()
        .map(JsonUtils::map)
        .map(
            v -> {
              int attr = JsonUtils.integer(v.get("time_attr"), -1);
              int duration = JsonUtils.integer(v.get("duration_type"), -1);
              int allocation =
                  JsonUtils.integer(JsonUtils.map(v.get("quota_attr")).get("type"), -1);
              return JsonUtils.obj(
                  "vacation_id",
                  v.get("id"),
                  "name",
                  v.get("name"),
                  "unit",
                  attr == 0 ? "days" : attr == 1 ? "hours" : "unknown",
                  "calendar",
                  duration == 0 ? "natural_days" : duration == 1 ? "workdays" : "unknown",
                  "allocation",
                  switch (allocation) {
                    case 0 -> "unlimited";
                    case 1 -> "annual";
                    case 2 -> "manual";
                    case 3 -> "monthly";
                    default -> "unknown";
                  },
                  "official",
                  v);
            })
        .toList();
  }
}

package group.cyt.common.module.adapter.wecom;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.adapter.support.TokenCache;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import group.cyt.common.module.registry.service.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class WecomAdapter implements PlatformAdapter {
  private record Context(
      String app,
      String corp,
      String secret,
      String contactSecret,
      int agent,
      String base,
      Map<String, String> headers) {}

  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final TokenCache cache;
  private final OperationRegistry registry;

  public WecomAdapter(
      CredentialService credentials,
      UpstreamHttp http,
      TokenCache cache,
      OperationRegistry registry) {
    this.credentials = credentials;
    this.http = http;
    this.cache = cache;
    this.registry = registry;
  }

  public boolean supports(String platform) {
    return platform.equals("wecom");
  }

  public Object invoke(OperationDefinition op, Map<String, Object> p, String system) {
    Context c = context(p, system);
    String key = op.key().substring(6);
    if (key.startsWith("hr.")) {
      String prefix = c.app().equals("default") ? "WEWORK" : "WEWORK_APP_" + c.app().toUpperCase(Locale.ROOT);
      String hrSecret = credentials.resolve(prefix + "_HR_SECRET", system);
      if (hrSecret.isBlank()) hrSecret = credentials.resolve("WEWORK_HR_SECRET", system);
      if (!hrSecret.isBlank()) c = new Context(c.app(), c.corp(), hrSecret, c.contactSecret(), c.agent(), c.base(), c.headers());
      if (key.equals("hr.get_fields"))
        return call(c, "GET", "/hr/get_fields", Map.of(), null, false);
      if (key.equals("hr.get_staff_info"))
        return call(c, "POST", "/hr/get_staff_info", Map.of(),
            JsonUtils.obj("userid", p.get("userid"), "get_all", false, "fieldids", p.get("fieldids")), false);
      throw GatewayException.invalid("未知企微花名册操作");
    }
    if (key.equals("auth.access_token"))
      return JsonUtils.obj(
          "errcode",
          0,
          "errmsg",
          "ok",
          "access_token",
          token(c, false, JsonUtils.bool(p.get("refresh"))),
          "expires_in",
          7080,
          "cached",
          !JsonUtils.bool(p.get("refresh")));
    if (key.startsWith("directory.")) return directory(c, key, p);
    if (key.equals("users.get"))
      return call(c, "GET", "/user/get", JsonUtils.obj("userid", p.get("userid")), null, false);
    if (key.equals("users.resolve_code"))
      return call(c, "GET", "/auth/getuserinfo", JsonUtils.obj("code", p.get("code")), null, false);
    if (key.startsWith("messages.")) {
      String type = key.endsWith("markdown") ? "markdown" : "text";
      Map<String, Object> body =
          JsonUtils.obj(
              "msgtype",
              type,
              "agentid",
              c.agent(),
              type,
              JsonUtils.obj("content", p.get("content")),
              "touser",
              joined(p.get("to_users")));
      if (type.equals("text")) {
        body.put("toparty", joined(p.get("to_party")));
        body.put("totag", joined(p.get("to_tags")));
      }
      for (String f :
          List.of("safe", "enable_id_trans", "enable_duplicate_check", "duplicate_check_interval"))
        body.put(f, p.get(f));
      var result = call(c, "POST", "/message/send", Map.of(), body, false);
      result.put("app", c.app());
      result.put("agentid", c.agent());
      return result;
    }
    if (key.equals("schedules.batch_create")) {
      List<Object> created = new ArrayList<>(), failed = new ArrayList<>();
      int index = 0;
      for (Object item : JsonUtils.list(p.get("items"))) {
        try {
          Map<String, Object> validated =
              registry.validate(registry.get("wecom.schedules.create"), JsonUtils.map(item));
          var result = scheduleCreate(c, validated);
          result.put("index", index);
          created.add(result);
        } catch (GatewayException e) {
          failed.add(JsonUtils.obj("index", index, "code", e.code, "message", e.getMessage()));
        }
        index++;
      }
      return JsonUtils.obj(
          "created",
          created,
          "failed",
          failed,
          "summary",
          JsonUtils.obj("total", index, "ok", created.size(), "failed", failed.size()));
    }
    if (key.equals("schedules.copy")) {
      var response =
          call(
              c,
              "POST",
              "/oa/schedule/get",
              Map.of(),
              JsonUtils.obj("schedule_id_list", List.of(p.get("schedule_id"))),
              false);
      var list = JsonUtils.list(response.get("schedule_list"));
      if (list.isEmpty()) throw GatewayException.missing("源日程不存在");
      var source = new LinkedHashMap<>(JsonUtils.map(list.getFirst()));
      source.remove("schedule_id");
      for (String f : List.of("cal_id", "summary", "start_at", "end_at"))
        if (p.get(f) != null) source.put(f, p.get(f));
      if (JsonUtils.str(p, "summary").isBlank())
        source.put("summary", JsonUtils.str(source, "summary", "日程") + "（副本）");
      if (p.get("start_at") != null)
        source.put("start_time", ParameterRules.epoch(p.get("start_at")));
      if (p.get("end_at") != null) source.put("end_time", ParameterRules.epoch(p.get("end_at")));
      return scheduleCreate(c, source);
    }
    if (key.equals("schedules.create")) return scheduleCreate(c, p);
    if (key.equals("schedules.list") || key.equals("schedules.list_all"))
      return schedules(c, key, p);
    if (key.startsWith("calendars.")) {
      String action = key.substring(10);
      if (action.equals("get_many")) {
        var r =
            call(
                c,
                "POST",
                "/oa/calendar/get",
                Map.of(),
                JsonUtils.obj("cal_id_list", p.get("cal_id_list")),
                false);
        r.put(
            "items",
            JsonUtils.list(r.get("calendar_list")).stream()
                .map(
                    x -> {
                      var v = new LinkedHashMap<>(JsonUtils.map(x));
                      v.put("id", v.get("cal_id"));
                      return v;
                    })
                .toList());
        return r;
      }
      if (action.equals("delete"))
        return call(
            c,
            "POST",
            "/oa/calendar/del",
            Map.of(),
            JsonUtils.obj("cal_id", p.get("cal_id")),
            false);
      var calendar = nonNull(p);
      calendar.remove("skip_public_range");
      if (JsonUtils.bool(calendar.get("is_corp_calendar"))) {
        calendar.remove("color");
        calendar.remove("set_as_default");
      }
      var body = JsonUtils.obj("calendar", calendar);
      if (action.equals("update")) body.put("skip_public_range", p.get("skip_public_range"));
      var r =
          call(
              c,
              "POST",
              "/oa/calendar/" + (action.equals("create") ? "add" : "update"),
              Map.of(),
              body,
              false);
      if (action.equals("create")) r.put("summary", p.get("summary"));
      return r;
    }
    if (key.equals("schedules.get_many")) {
      var r =
          call(
              c,
              "POST",
              "/oa/schedule/get",
              Map.of(),
              JsonUtils.obj("schedule_id_list", p.get("schedule_id_list")),
              false);
      r.put(
          "items",
          JsonUtils.list(r.get("schedule_list")).stream()
              .map(x -> scheduleView(JsonUtils.map(x)))
              .toList());
      return r;
    }
    if (key.equals("schedules.update")) {
      var schedule = nonNull(p);
      for (String f : List.of("start_at", "end_at", "op_start_at", "agentid")) schedule.remove(f);
      var body = JsonUtils.obj("schedule", schedule);
      if (p.get("agentid") != null) body.put("agentid", p.get("agentid"));
      var r = call(c, "POST", "/oa/schedule/update", Map.of(), body, false);
      r.put("schedule_id", p.get("schedule_id"));
      return r;
    }
    if (key.equals("schedules.delete"))
      return call(
          c,
          "POST",
          "/oa/schedule/del",
          Map.of(),
          JsonUtils.obj("schedule_id", p.get("schedule_id")),
          false);
    if (key.equals("attendees.add") || key.equals("attendees.remove"))
      return call(
          c,
          "POST",
          "/oa/schedule/" + (key.endsWith("add") ? "add_attendees" : "del_attendees"),
          Map.of(),
          JsonUtils.obj("schedule_id", p.get("schedule_id"), "attendees", p.get("attendees")),
          false);
    throw GatewayException.config("未知企微操作: " + key);
  }

  private Context context(Map<String, Object> p, String system) {
    String requested = JsonUtils.str(p, "app").replace('-', '_');
    String selected = system.replace('-', '_');
    if (!requested.isBlank() && !requested.equals(selected))
      throw GatewayException.forbidden("不能使用其他系统的企业微信应用");
    String app = requested.isBlank() ? selected : requested;
    String prefix = "WEWORK_APP_" + app.toUpperCase(Locale.ROOT);
    String secret = credentials.resolve(prefix + "_SECRET", system);
    if (secret.isBlank()) {
      app = "default";
      prefix = "WEWORK";
      secret = credentials.required(prefix + "_SECRET", system);
    }
    String corp = credentials.resolve(prefix + "_CORP_ID", system);
    if (corp.isBlank()) corp = credentials.required("WEWORK_CORP_ID", system);
    String contact = credentials.resolve(prefix + "_CONTACT_SECRET", system);
    if (contact.isBlank()) contact = credentials.resolve("WEWORK_CONTACT_SECRET", system);
    int agent = JsonUtils.integer(credentials.resolve(prefix + "_AGENT_ID", system), 0);
    String proxy = credentials.resolve("WEWORK_PROXY_TOKEN", system),
        base = "https://qyapi.weixin.qq.com/cgi-bin";
    if (!proxy.isBlank()) {
      base = credentials.resolve("WEWORK_PROXY_BASE", system);
      if (base.isBlank()) base = "https://erp-proxy.cyt.group/wxwork";
      base = base.replaceAll("/$", "") + "/cgi-bin";
    }
    return new Context(
        app,
        corp,
        secret,
        contact,
        agent,
        base,
        proxy.isBlank() ? Map.of() : Map.of("X-Proxy-Token", proxy));
  }

  private String token(Context c, boolean contact, boolean refresh) {
    String secret = contact && !c.contactSecret().isBlank() ? c.contactSecret() : c.secret();
    return cache.get(
        "wecom:" + c.corp() + ":" + secret,
        refresh,
        () ->
            check(
                http.object(
                    "GET",
                    UpstreamHttp.query(
                        c.base() + "/gettoken",
                        JsonUtils.obj("corpid", c.corp(), "corpsecret", secret)),
                    c.headers(),
                    null)));
  }

  private Map<String, Object> call(
      Context c,
      String method,
      String path,
      Map<String, Object> query,
      Object body,
      boolean contact) {
    for (int attempt = 0; attempt < 2; attempt++) {
      var q = new LinkedHashMap<>(query);
      q.put("access_token", token(c, contact, attempt > 0));
      var r =
          JsonUtils.map(
              http.json(method, UpstreamHttp.query(c.base() + path, q), c.headers(), body, 120));
      String code = JsonUtils.str(r, "errcode");
      if (attempt == 0 && Set.of("40014", "42001").contains(code)) continue;
      return check(r);
    }
    throw GatewayException.config("企微令牌刷新失败");
  }

  private Map<String, Object> check(Map<String, Object> r) {
    String code = JsonUtils.str(r, "errcode");
    if (!code.isBlank() && !code.equals("0"))
      throw new GatewayException(
              code.equals("45009") ? 429 : 502,
              code.equals("45009") ? "rate_limited" : "upstream_error",
              "企业微信业务调用失败",
              code.equals("45009"))
          .code("WECOM_" + code);
    return r;
  }

  private Map<String, Object> scheduleCreate(Context c, Map<String, Object> p) {
    var schedule = nonNull(p);
    schedule.remove("start_at");
    schedule.remove("end_at");
    schedule.remove("agentid");
    schedule.values().removeIf(v -> v instanceof List<?> l && l.isEmpty());
    var body = JsonUtils.obj("schedule", schedule);
    if (p.get("agentid") != null) body.put("agentid", p.get("agentid"));
    var result = call(c, "POST", "/oa/schedule/add", Map.of(), body, false);
    result.put("cal_id", p.get("cal_id"));
    result.put("summary", p.get("summary"));
    return result;
  }

  private Object schedules(Context c, String key, Map<String, Object> p) {
    boolean all = key.endsWith("list_all");
    int offset = JsonUtils.integer(p.get("offset"), 0),
        size = JsonUtils.integer(p.get("page_size"), JsonUtils.integer(p.get("limit"), 100));
    if (p.get("cursor") != null)
      try {
        offset =
            JsonUtils.integer(
                JsonUtils.read(
                        new String(
                            Base64.getUrlDecoder().decode(p.get("cursor").toString()),
                            StandardCharsets.UTF_8))
                    .get("offset"),
                offset);
      } catch (Exception ignored) {
      }
    List<Object> items = new ArrayList<>();
    int pages = 0, max = JsonUtils.integer(p.get("max_items"), 10000);
    while (true) {
      var r =
          call(
              c,
              "POST",
              "/oa/schedule/get_by_calendar",
              Map.of(),
              JsonUtils.obj("cal_id", p.get("cal_id"), "offset", offset, "limit", size),
              false);
      var raw = JsonUtils.list(r.get("schedule_list"));
      pages++;
      items.addAll(raw.stream().map(x -> scheduleView(JsonUtils.map(x))).toList());
      offset += raw.size();
      boolean more = !raw.isEmpty() && raw.size() >= size;
      if (!all) {
        String next =
            more
                ? Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                        JsonUtils.write(JsonUtils.obj("offset", offset))
                            .getBytes(StandardCharsets.UTF_8))
                : null;
        return JsonUtils.obj(
            "items",
            items,
            "page",
            JsonUtils.obj(
                "next_cursor", next, "has_more", more, "page_size", size, "count", items.size()),
            "schedule_list",
            raw);
      }
      if (items.size() > max) throw GatewayException.invalid("日程数量超过 max_items，请分批获取");
      if (!more) return JsonUtils.obj("items", items, "total", items.size(), "pages", pages);
    }
  }

  private Object directory(Context c, String key, Map<String, Object> p) {
    var rawDepartments =
        JsonUtils.list(call(c, "GET", "/department/list", Map.of(), null, true).get("department"))
            .stream()
            .map(JsonUtils::map)
            .toList();
    Map<Integer, Map<String, Object>> byId = new LinkedHashMap<>();
    for (var d : rawDepartments) byId.put(JsonUtils.integer(d.get("id"), 0), d);
    Map<Integer, String> paths = new HashMap<>(), names = new HashMap<>();
    for (var d : rawDepartments) {
      int id = JsonUtils.integer(d.get("id"), 0);
      names.put(id, JsonUtils.str(d, "name"));
      paths.put(id, departmentPath(id, byId, new HashSet<>()));
    }
    List<Object> deptViews =
        rawDepartments.stream()
            .map(
                d ->
                    (Object)
                        JsonUtils.obj(
                            "id",
                            d.get("id"),
                            "name",
                            d.get("name"),
                            "parent_id",
                            d.get("parentid"),
                            "path",
                            paths.get(JsonUtils.integer(d.get("id"), 0)),
                            "order",
                            d.getOrDefault("order", 0)))
            .toList();
    Set<Integer> wanted = new LinkedHashSet<>();
    for (Object id : JsonUtils.list(p.get("department_ids"))) wanted.add(JsonUtils.integer(id, 0));
    List<Object> missingPaths = new ArrayList<>(), resolved = new ArrayList<>();
    for (Object requested : JsonUtils.list(p.get("paths"))) {
      String path = requested.toString().replaceAll("^/+|/+$", "");
      var matches =
          paths.entrySet().stream()
              .filter(
                  e ->
                      e.getValue().equals(path)
                          || e.getValue().endsWith("/" + path)
                          || names.get(e.getKey()).equals(path))
              .toList();
      if (matches.size() != 1) missingPaths.add(requested);
      else {
        wanted.add(matches.getFirst().getKey());
        resolved.add(JsonUtils.obj("path", requested, "id", matches.getFirst().getKey()));
      }
    }
    if (key.equals("directory.departments")) {
      var selected =
          wanted.isEmpty() && JsonUtils.list(p.get("paths")).isEmpty()
              ? deptViews
              : deptViews.stream()
                  .filter(d -> wanted.contains(JsonUtils.integer(JsonUtils.map(d).get("id"), 0)))
                  .toList();
      var missing = wanted.stream().filter(i -> !byId.containsKey(i)).toList();
      return JsonUtils.obj(
          "departments",
          selected,
          "resolved_paths",
          resolved,
          "missing_departments",
          missing,
          "missing_paths",
          missingPaths,
          "counts",
          JsonUtils.obj(
              "departments",
              selected.size(),
              "missing",
              missing.size(),
              "missing_paths",
              missingPaths.size()),
          "app",
          c.app());
    }
    Map<String, Map<String, Object>> users = new LinkedHashMap<>();
    List<Object> missing = new ArrayList<>();
    String mode = "contact_secret";
    if (p.get("userids") != null) {
      mode = "user_get";
      for (Object id : JsonUtils.list(p.get("userids")))
        try {
          var u = call(c, "GET", "/user/get", JsonUtils.obj("userid", id), null, true);
          users.put(JsonUtils.str(u, "userid"), u);
        } catch (GatewayException e) {
          if ("WECOM_60111".equals(e.code)) missing.add(id);
          else throw e;
        }
    } else {
      if (key.equals("directory.list")) wanted.add(JsonUtils.integer(p.get("department_id"), 1));
      for (int department : wanted) {
        var q =
            JsonUtils.obj(
                "department_id",
                department,
                "fetch_child",
                JsonUtils.integer(p.get("fetch_child"), 1));
        if (!c.contactSecret().isBlank()) {
          for (Object u :
              JsonUtils.list(call(c, "GET", "/user/list", q, null, true).get("userlist")))
            users.put(JsonUtils.str(JsonUtils.map(u), "userid"), JsonUtils.map(u));
        } else {
          mode = "app_secret_simplelist";
          List<Object> simple;
          try {
            simple =
                JsonUtils.list(call(c, "GET", "/user/simplelist", q, null, false).get("userlist"));
          } catch (GatewayException e) {
            if (e.status == 429) throw e;
            simple = JsonUtils.list(call(c, "GET", "/user/list", q, null, false).get("userlist"));
            mode = "app_secret_user_list";
          }
          for (Object v : simple) {
            var u = JsonUtils.map(v);
            String id = JsonUtils.str(u, "userid");
            if (!users.containsKey(id))
              users.put(id, call(c, "GET", "/user/get", JsonUtils.obj("userid", id), null, false));
          }
        }
      }
    }
    List<Object> views = users.values().stream().map(u -> userView(u, names)).toList();
    var result =
        JsonUtils.obj(
            "users",
            views,
            "missing",
            missing,
            "missing_paths",
            missingPaths,
            "resolved_paths",
            resolved,
            "counts",
            JsonUtils.obj(
                "departments",
                deptViews.size(),
                "users",
                views.size(),
                "missing",
                missing.size(),
                "missing_paths",
                missingPaths.size()),
            "mode",
            mode,
            "app",
            c.app());
    if (key.equals("directory.list")) result.put("departments", deptViews);
    return result;
  }

  private String departmentPath(
      int id, Map<Integer, Map<String, Object>> departments, Set<Integer> visited) {
    if (!visited.add(id)) throw new GatewayException(502, "upstream_error", "企微部门树存在循环", false);
    var d = departments.get(id);
    if (d == null) return "";
    int parent = JsonUtils.integer(d.get("parentid"), 0);
    String prefix = parent == 0 ? "" : departmentPath(parent, departments, visited);
    return prefix.isBlank() ? JsonUtils.str(d, "name") : prefix + "/" + JsonUtils.str(d, "name");
  }

  private Object userView(Map<String, Object> u, Map<Integer, String> names) {
    List<Object> ids = new ArrayList<>(JsonUtils.list(u.get("department")));
    Object main = u.get("main_department");
    if (main != null && ids.remove(main)) ids.addFirst(main);
    return JsonUtils.obj(
        "userid",
        JsonUtils.str(u, "userid"),
        "name",
        JsonUtils.str(u, "name"),
        "alias",
        JsonUtils.str(u, "alias"),
        "position",
        JsonUtils.str(u, "position"),
        "department_ids",
        ids,
        "main_department",
        main,
        "main_department_name",
        names.getOrDefault(JsonUtils.integer(main, 0), ""),
        "department_names",
        ids.stream()
            .map(id -> names.getOrDefault(JsonUtils.integer(id, 0), id.toString()))
            .toList(),
        "mobile",
        JsonUtils.str(u, "mobile"),
        "telephone",
        JsonUtils.str(u, "telephone"),
        "email",
        JsonUtils.str(u, "email"),
        "avatar",
        JsonUtils.str(u, "avatar"),
        "status",
        u.get("status"),
        "active",
        JsonUtils.integer(u.get("status"), 0) == 1);
  }

  private Map<String, Object> scheduleView(Map<String, Object> raw) {
    var view = new LinkedHashMap<>(raw);
    view.put("id", raw.get("schedule_id"));
    for (String t : List.of("start", "end"))
      if (raw.get(t + "_time") != null)
        view.put(
            t + "_at",
            Instant.ofEpochSecond(ParameterRules.epoch(raw.get(t + "_time")))
                .atOffset(ZoneOffset.ofHours(8))
                .toString());
    int status = JsonUtils.integer(raw.get("status"), 0);
    view.put("status_code", status);
    view.put(
        "status",
        switch (status) {
          case 0 -> "active";
          case 1 -> "cancelled";
          default -> "unknown";
        });
    view.put(
        "attendees",
        JsonUtils.list(raw.get("attendees")).stream()
            .map(
                x -> {
                  var a = JsonUtils.map(x);
                  int code = JsonUtils.integer(a.get("response_status"), 0);
                  return JsonUtils.obj(
                      "userid",
                      a.get("userid"),
                      "status_code",
                      code,
                      "status",
                      switch (code) {
                        case 0 -> "pending";
                        case 1 -> "tentative";
                        case 2 -> "accepted";
                        case 3 -> "accepted_once";
                        case 4 -> "declined";
                        default -> "unknown";
                      });
                })
            .toList());
    return view;
  }

  private static String joined(Object list) {
    return String.join("|", JsonUtils.list(list).stream().map(Object::toString).toList());
  }

  private static Map<String, Object> nonNull(Map<String, Object> p) {
    Map<String, Object> r = new LinkedHashMap<>();
    p.forEach(
        (k, v) -> {
          if (v != null) r.put(k, v);
        });
    return r;
  }
}

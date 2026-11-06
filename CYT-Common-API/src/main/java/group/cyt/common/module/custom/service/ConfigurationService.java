package group.cyt.common.module.custom.service;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.NetworkPolicy;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfigurationService {
  public enum Kind {
    MODULE(
        "gateway_module",
        Set.of(
            "key",
            "name",
            "description",
            "service_kind",
            "base_url",
            "auth_type",
            "auth_name",
            "secret_ref",
            "token_url",
            "client_id_ref",
            "client_secret_ref",
            "refresh_token_ref",
            "scopes",
            "headers_json",
            "timeout_s",
            "enabled")),
    API(
        "gateway_custom_api",
        Set.of(
            "key",
            "module_key",
            "name",
            "description",
            "base_url",
            "method",
            "path",
            "auth_type",
            "auth_name",
            "secret_ref",
            "headers_json",
            "body_template",
            "params_schema_json",
            "response_path",
            "response_schema_json",
            "pagination_json",
            "retry_max",
            "timeout_s",
            "sensitive",
            "enabled",
            "category",
            "sort_order")),
    CALLBACK(
        "gateway_callback",
        Set.of("system", "platform", "url", "secret_ref", "description", "enabled")),
    ENVIRONMENT(
        "gateway_environment",
        Set.of(
            "key",
            "label",
            "description",
            "is_production",
            "base_urls_json",
            "extra_vars_json",
            "enabled"));
    public final String table;
    public final Set<String> fields;

    Kind(String table, Set<String> fields) {
      this.table = table;
      this.fields = fields;
    }
  }

  private final GatewayStore store;
  private final OperationRegistry registry;
  private final NetworkPolicy network;
  private final GatewaySettings settings;

  public ConfigurationService(
      GatewayStore store,
      OperationRegistry registry,
      NetworkPolicy network,
      GatewaySettings settings) {
    this.store = store;
    this.registry = registry;
    this.network = network;
    this.settings = settings;
  }

  public List<Map<String, Object>> list(Kind kind) {
    return store.rows("SELECT * FROM " + kind.table + " ORDER BY id");
  }

  public Map<String, Object> get(Kind kind, long id) {
    var row = store.one("SELECT * FROM " + kind.table + " WHERE id=?", id);
    if (row == null) throw GatewayException.missing("配置不存在");
    return row;
  }

  @Transactional
  public long save(Kind kind, Long id, Map<String, Object> body, String actor) {
    Map<String, Object> data = new LinkedHashMap<>();
    body.forEach(
        (k, v) -> {
          if (kind.fields.contains(k) && v != null) data.put(k, v);
        });
    if (id == null) {
      if (kind == Kind.API) {
        // An API may inherit the module address; the persisted empty override is still NOT NULL.
        data.putIfAbsent("base_url", "");
        data.putIfAbsent("params_schema_json", "[]");
        data.putIfAbsent("pagination_json", "{}");
        data.putIfAbsent("body_template", "");
      }
      if (kind == Kind.API || kind == Kind.MODULE) data.putIfAbsent("headers_json", "{}");
    }
    Map<String, Object> merged = new LinkedHashMap<>();
    if (id != null) merged.putAll(get(kind, id));
    merged.putAll(data);
    validate(kind, merged);
    if (id != null
        && (kind == Kind.MODULE || kind == Kind.ENVIRONMENT)
        && !Objects.equals(get(kind, id).get("key"), merged.get("key")))
      throw GatewayException.invalid("配置标识不可修改，请新建配置");
    data.put("updated_at", GatewayStore.now());
    if (id == null) {
      data.put("created_by", actor);
      id = store.insert(kind.table, data);
    } else store.edit(kind.table, id, data);
    store.audit(
        actor,
        kind.name().toLowerCase(Locale.ROOT) + "_save",
        id.toString(),
        JsonUtils.obj("fields", data.keySet()));
    return id;
  }

  @Transactional
  public void delete(Kind kind, long id, String actor) {
    var row = get(kind, id);
    if (kind == Kind.MODULE
        && store.count("SELECT COUNT(*) FROM gateway_custom_api WHERE module_key=?", row.get("key"))
            > 0) throw GatewayException.invalid("请先移除模块下的接口");
    if (kind == Kind.ENVIRONMENT
        && (settings.current().equals(row.get("key"))
            || store.count("SELECT COUNT(*) FROM gateway_secret WHERE env=?", row.get("key")) > 0))
      throw GatewayException.invalid("当前运行环境或包含凭据的环境不能删除");
    store.update("DELETE FROM " + kind.table + " WHERE id=?", id);
    store.audit(
        actor, kind.name().toLowerCase(Locale.ROOT) + "_delete", Long.toString(id), Map.of());
  }

  private void validate(Kind kind, Map<String, Object> d) {
    for (var e : d.entrySet())
      if (e.getValue() instanceof String s && s.length() > 2_000_000)
        throw GatewayException.invalid("配置字段过长");
    if (JsonUtils.str(d, "description").length() > 255 || JsonUtils.str(d, "name").length() > 64)
      throw GatewayException.invalid("名称或说明过长");
    if (d.containsKey("enabled")) d.put("enabled", JsonUtils.bool(d.get("enabled")));
    for (String field :
        List.of("headers_json", "pagination_json", "base_urls_json", "response_schema_json"))
      if (d.get(field) != null && !(JsonUtils.parse(JsonUtils.str(d, field)) instanceof Map<?, ?>))
        throw GatewayException.invalid(field + " 必须是 JSON 对象");
    if (d.get("response_schema_json") != null) {
      var response = JsonUtils.tree(JsonUtils.parse(JsonUtils.str(d, "response_schema_json")));
      validateResponseRefs(response);
    }
    for (String field : List.of("params_schema_json", "extra_vars_json"))
      if (d.containsKey(field) && !(JsonUtils.parse(JsonUtils.str(d, field)) instanceof List<?>))
        throw GatewayException.invalid(field + " 必须是 JSON 数组");
    if (!JsonUtils.str(d, "body_template").isBlank())
      JsonUtils.parse(JsonUtils.str(d, "body_template"));
    for (String field :
        List.of("secret_ref", "client_id_ref", "client_secret_ref", "refresh_token_ref")) {
      String ref = JsonUtils.str(d, field);
      if (!ref.isBlank() && !ref.matches("CUSTOM_[A-Z0-9_]{1,57}"))
        throw GatewayException.invalid("凭据引用必须以 CUSTOM_ 开头");
    }
    if (kind == Kind.ENVIRONMENT) {
      if (!JsonUtils.str(d, "key").matches("[a-z][a-z0-9_-]{1,31}"))
        throw GatewayException.invalid("环境标识不合法");
      return;
    }
    if (kind == Kind.CALLBACK) {
      if (store.one("SELECT id FROM gateway_system WHERE `key`=?", d.get("system")) == null)
        throw GatewayException.invalid("系统不存在");
      if (JsonUtils.str(d, "platform").isBlank()) throw GatewayException.invalid("平台不能为空");
      URI u = URI.create(JsonUtils.str(d, "url"));
      if (u.getHost() == null
          || !Set.of("http", "https").contains(u.getScheme())
          || u.getUserInfo() != null) throw GatewayException.invalid("回调地址无效");
      return;
    }
    String key = JsonUtils.str(d, "key"),
        base = JsonUtils.str(d, "base_url"),
        auth = JsonUtils.str(d, "auth_type", "none");
    if (kind == Kind.MODULE) {
      if (!Set.of("third_party", "internal")
          .contains(JsonUtils.str(d, "service_kind", "third_party")))
        throw GatewayException.invalid("服务来源必须为 third_party 或 internal");
      if (!key.matches("[a-z][a-z0-9_]{1,31}")
          || registry.contract().path("platforms").findValuesAsText("platform").contains(key))
        throw GatewayException.invalid("模块标识无效或与内置平台冲突");
    } else {
      if (!key.matches("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*")
          || registry.builtin(key)) throw GatewayException.invalid("操作标识无效或与内置操作冲突");
      String module = JsonUtils.str(d, "module_key");
      if (!module.isBlank()) {
        if (!key.startsWith(module + ".")) throw GatewayException.invalid("操作标识必须以模块标识开头");
        var m = store.one("SELECT * FROM gateway_module WHERE `key`=?", module);
        if (m == null) throw GatewayException.invalid("模块不存在");
        if (base.isBlank()) base = JsonUtils.str(m, "base_url");
      }
      String path = JsonUtils.str(d, "path", "/");
      if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\"))
        throw GatewayException.invalid("接口路径必须是站内绝对路径");
      if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE")
          .contains(JsonUtils.str(d, "method", "GET")))
        throw GatewayException.invalid("HTTP 方法不支持");
      int retries = JsonUtils.integer(d.get("retry_max"), 1);
      if (retries < 1 || retries > 5) throw GatewayException.invalid("重试次数必须为 1–5");
    }
    network.resolve(base);
    if (!Set.of("none", "inherit", "bearer", "header", "basic", "query", "oauth2_refresh")
        .contains(auth)) throw GatewayException.invalid("鉴权类型不支持");
    if (Set.of("bearer", "header", "basic", "query").contains(auth)
        && JsonUtils.str(d, "secret_ref").isBlank()) throw GatewayException.invalid("请设置凭据引用名称");
    if (auth.equals("oauth2_refresh")) {
      network.resolve(JsonUtils.str(d, "token_url"));
      for (String name : List.of("client_id_ref", "client_secret_ref", "refresh_token_ref"))
        if (JsonUtils.str(d, name).isBlank()) throw GatewayException.invalid("OAuth2 配置缺少 " + name);
    }
    double timeout = d.get("timeout_s") instanceof Number n ? n.doubleValue() : 30;
    if (timeout < 1 || timeout > 120) throw GatewayException.invalid("超时必须为 1–120 秒");
  }

  private void validateResponseRefs(com.fasterxml.jackson.databind.JsonNode node) {
    if (node.isObject()) {
      if (node.has("$ref") && !node.path("$ref").asText().startsWith("#/$defs/"))
        throw GatewayException.invalid("响应契约仅支持本地 #/$defs/ 引用");
      node.elements().forEachRemaining(this::validateResponseRefs);
    } else if (node.isArray()) node.elements().forEachRemaining(this::validateResponseRefs);
  }
}

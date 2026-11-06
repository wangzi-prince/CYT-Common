package group.cyt.common.module.credential.controller;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.auth.service.AuthenticationService;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.custom.service.ConfigurationService;
import group.cyt.common.module.custom.service.ConfigurationService.Kind;
import group.cyt.common.module.registry.service.OperationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api")
public class CredentialController {
  private final CredentialService secrets;
  private final GatewayStore store;
  private final GatewaySettings settings;
  private final ConfigurationService config;
  private final OperationRegistry registry;

  public CredentialController(
      CredentialService secrets,
      GatewayStore store,
      GatewaySettings settings,
      ConfigurationService config,
      OperationRegistry registry) {
    this.secrets = secrets;
    this.store = store;
    this.settings = settings;
    this.config = config;
    this.registry = registry;
  }

  @GetMapping("/environments")
  public Object environments() {
    var items = config.list(Kind.ENVIRONMENT);
    for (var e : items) {
      e.put("is_current", e.get("key").equals(settings.current()));
      e.put(
          "secret_count",
          store.count("SELECT COUNT(*) FROM gateway_secret WHERE env=?", e.get("key")));
    }
    return JsonUtils.obj(
        "current",
        settings.current(),
        "writable",
        secrets.writable(),
        "items",
        items,
        "issues",
        List.of());
  }

  @PostMapping("/environments")
  public Object create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    return JsonUtils.obj(
        "ok",
        true,
        "id",
        config.save(Kind.ENVIRONMENT, null, body, AuthenticationService.actor(req)));
  }

  @PutMapping("/environments/{id}")
  public Object update(
      @PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
    return JsonUtils.obj(
        "ok",
        true,
        "id",
        config.save(Kind.ENVIRONMENT, id, body, AuthenticationService.actor(req)));
  }

  @DeleteMapping("/environments/{id}")
  public Object delete(@PathVariable long id, HttpServletRequest req) {
    config.delete(Kind.ENVIRONMENT, id, AuthenticationService.actor(req));
    return JsonUtils.obj("ok", true);
  }

  private Map<String, Object> env(String key) {
    var e = store.one("SELECT * FROM gateway_environment WHERE `key`=?", key);
    if (e == null) throw GatewayException.invalid("环境不存在: " + key);
    return e;
  }

  @GetMapping("/environments/{key}/secrets")
  public Object listSecrets(@PathVariable String key) {
    var e = env(key);
    return JsonUtils.obj(
        "env",
        e,
        "is_current",
        settings.current().equals(key),
        "writable",
        secrets.writable(),
        "platforms",
        settings.current().equals(key) ? secrets.inventory(enabledSystemKeys()) : List.of(),
        "systems",
        store.rows("SELECT `key`,display_name FROM gateway_system WHERE enabled=true"),
        "portal_system",
        "portal",
        "db_secrets",
        store.rows(
            "SELECT name,CHAR_LENGTH(ciphertext) AS length,updated_by,updated_at FROM gateway_secret WHERE env=? ORDER BY name",
            key));
  }

  @PutMapping("/environments/{key}/secrets")
  public Object saveSecrets(
      @PathVariable String key, @RequestBody Map<String, Object> body, HttpServletRequest req) {
    env(key);
    Map<String, String> values = new LinkedHashMap<>();
    JsonUtils.map(body.get("values"))
        .forEach(
            (k, v) -> {
              if (!(v instanceof String)) throw GatewayException.invalid("凭据值必须是字符串");
              values.put(k, v.toString());
            });
    if (values.isEmpty() || values.size() > 200) throw GatewayException.invalid("每次提交 1–200 个凭据");
    secrets.save(key, values, AuthenticationService.actor(req));
    return JsonUtils.obj("ok", true, "saved", values.size());
  }

  @DeleteMapping("/environments/{key}/secrets/{name}")
  public Object deleteSecret(
      @PathVariable String key, @PathVariable String name, HttpServletRequest req) {
    env(key);
    return store.atomic(
        () -> {
          store.update("DELETE FROM gateway_secret WHERE env=? AND name=?", key, name);
          store.audit(
              AuthenticationService.actor(req), "secret_delete", key + ":" + name, Map.of());
          return JsonUtils.obj("ok", true);
        });
  }

  @PostMapping("/environments/{key}/secrets/copy-to-system")
  public Object copy(
      @PathVariable String key, @RequestBody Map<String, Object> body, HttpServletRequest req) {
    env(key);
    if (!settings.current().equals(key)) throw GatewayException.invalid("复制共享凭据仅支持当前运行环境");
    String platform = JsonUtils.str(body, "platform"), system = JsonUtils.str(body, "system");
    if (store.one("SELECT id FROM gateway_system WHERE `key`=?", system) == null)
      throw GatewayException.invalid("系统不存在");
    Map<String, String> values = new LinkedHashMap<>();
    for (var p : registry.contract().path("credentials"))
      if (p.path("platform").asText().equals(platform))
        for (var v : p.path("vars"))
          if (v.path("per_system").asBoolean()) {
            String value = secrets.raw(v.path("name").asText()),
                name =
                    v.path("scoped").asText().replace("{SYSTEM}", system.toUpperCase(Locale.ROOT));
            if (!value.isBlank()
                && !name.isBlank()
                && store.count(
                        "SELECT COUNT(*) FROM gateway_secret WHERE env=? AND name=?", key, name)
                    == 0) values.put(name, value);
          }
    if (!values.isEmpty()) secrets.save(key, values, AuthenticationService.actor(req));
    return JsonUtils.obj("ok", true, "copied", values.size());
  }

  @GetMapping("/credentials")
  public Object credentials() {
    var systems = enabledSystemKeys();
    var items = secrets.inventory(systems);
    return JsonUtils.obj(
        "env",
        settings.current(),
        "systems",
        systems,
        "portal_system",
        "portal",
        "summary",
        secrets.summary(items),
        "items",
        items,
        "writable",
        secrets.writable());
  }

  private List<String> enabledSystemKeys() {
    return store.rows("SELECT `key` FROM gateway_system WHERE enabled=true").stream()
        .map(r -> JsonUtils.str(r, "key"))
        .toList();
  }

  @PostMapping("/credentials/reload")
  public Object reload() {
    return JsonUtils.obj(
        "ok", true, "newly_set", List.of(), "env_file", "database", "note", "凭据在每次调用时读取，数据库更新立即生效");
  }
}

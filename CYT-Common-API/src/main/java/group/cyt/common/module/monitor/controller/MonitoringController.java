package group.cyt.common.module.monitor.controller;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.AdapterDispatcher;
import group.cyt.common.module.auth.service.AuthenticationService;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.grant.service.QuotaService;
import group.cyt.common.module.monitor.service.MonitoringService;
import group.cyt.common.module.registry.service.OperationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MonitoringController {
  private final MonitoringService monitoring;
  private final OperationRegistry registry;
  private final QuotaService quota;
  private final GatewaySettings settings;
  private final GatewayStore store;
  private final AdapterDispatcher adapters;
  private final CredentialService credentials;

  public MonitoringController(
      MonitoringService monitoring,
      OperationRegistry registry,
      QuotaService quota,
      GatewaySettings settings,
      GatewayStore store,
      AdapterDispatcher adapters,
      CredentialService credentials) {
    this.monitoring = monitoring;
    this.registry = registry;
    this.quota = quota;
    this.settings = settings;
    this.store = store;
    this.adapters = adapters;
    this.credentials = credentials;
  }

  @GetMapping({"/health", "/healthz"})
  public Object health() {
    store.count("SELECT 1");
    return JsonUtils.obj(
        "ok",
        true,
        "service",
        "cyt-common-api-java",
        "database",
        "mysql",
        "quota_backend",
        quota.backend(),
        "env",
        settings.current());
  }

  @GetMapping("/admin/api/meta")
  public Object meta(HttpServletRequest request) {
    return JsonUtils.obj(
        "name",
        "cyt-common-api",
        "version",
        "1.0.0-java",
        "env",
        settings.current(),
        "dev_mode",
        settings.dev(),
        "actor",
        AuthenticationService.actor(request),
        "auth_modes",
        settings.modes(),
        "quota_backend",
        quota.backend(),
        "operation_count",
        registry.operations().size(),
        "custom_operation_count",
        store.count("SELECT COUNT(*) FROM gateway_custom_api WHERE enabled=true"),
        "systems",
        store.rows("SELECT `key` FROM gateway_system WHERE enabled=true").stream()
            .map(s -> s.get("key"))
            .toList(),
        "platforms",
        platforms());
  }

  @GetMapping({"/admin/api/platforms", "/admin/api/health"})
  public Object platformList() {
    return JsonUtils.obj("platforms", platforms());
  }

  private List<Map<String, Object>> platforms() {
    List<Map<String, Object>> out = new ArrayList<>();
    var ops = registry.operations();
    for (var p : registry.contract().path("platforms")) {
      String platform = p.path("platform").asText();
      var platformOps = ops.stream().filter(o -> o.platform().equals(platform)).toList();
      boolean mock = !platformOps.isEmpty() && adapters.mock(platformOps.getFirst());
      List<String> missing = new ArrayList<>();
      for (var spec : registry.contract().path("credentials"))
        if (spec.path("platform").asText().equals(platform)) {
          Set<String> accounts = new LinkedHashSet<>();
          String accountNames = credentials.raw(spec.path("accounts_var").asText());
          String defaultAccount = credentials.raw(spec.path("default_account_var").asText());
          if (defaultAccount.isBlank()) defaultAccount = spec.path("account_fallback").asText();
          for (String account : (accountNames + "," + defaultAccount).split(","))
            if (!account.isBlank())
              accounts.add(CredentialService.accountKey(account.trim()));
          for (var variable : spec.path("vars")) {
            if (!variable.path("required").asBoolean(true)) continue;
            String name = variable.path("name").asText();
            if (variable.path("account_scoped").asBoolean()) {
              if (accounts.isEmpty()) missing.add(name);
              for (String account : accounts) {
                String expanded = name.replace("{ACCOUNT}", account);
                if (credentials.raw(expanded).isBlank()) missing.add(expanded);
              }
            } else if (credentials.raw(name).isBlank()) missing.add(name);
          }
        }
      boolean configured = missing.isEmpty();
      out.add(
          JsonUtils.obj(
              "platform",
              platform,
              "display_name",
              p.path("display_name").asText(),
              "platform_quota",
              p.path("platform_quota").isNull() ? null : p.path("platform_quota").asText(),
              "mock",
              mock,
              "configured",
              configured,
              "operation_count",
              platformOps.size(),
              "sensitive_count",
              platformOps.stream().filter(o -> o.sensitive()).count(),
              "read_count",
              platformOps.stream().filter(o -> o.readOnly()).count(),
              "missing",
              missing,
              "note",
              mock
                  ? "模拟模式：请求由本地模拟适配器处理"
                  : configured ? "真实模式：使用当前环境凭据调用平台" : "凭据尚未配齐，真实调用将返回配置错误"));
    }
    for (var m : store.rows("SELECT * FROM gateway_module WHERE enabled=true"))
      out.add(
          JsonUtils.obj(
              "platform",
              m.get("key"),
              "display_name",
              m.get("name"),
              "platform_quota",
              null,
              "mock",
              false,
              "configured",
              true,
              "operation_count",
              ops.stream().filter(o -> o.platform().equals(m.get("key"))).count(),
              "sensitive_count",
              0,
              "note",
              "自定义 HTTP 模块"));
    return out;
  }

  @GetMapping("/admin/api/overview")
  public Object overview(@RequestParam(defaultValue = "24") int hours) {
    return monitoring.overview(hours);
  }

  @GetMapping("/admin/api/calls")
  public Object calls(@RequestParam Map<String, String> query) {
    return monitoring.calls(query);
  }

  @GetMapping("/admin/api/calls/{trace}")
  public Object detail(@PathVariable String trace) {
    return monitoring.detail(trace);
  }

  @GetMapping("/admin/api/audit")
  public Object audit(@RequestParam Map<String, String> query) {
    return monitoring.audit(query);
  }

  @GetMapping("/admin/api/facets")
  public Object facets(@RequestParam Map<String, String> query) {
    return monitoring.facets(query);
  }

  @GetMapping("/admin/api/export/calls.csv")
  public ResponseEntity<String> csv(@RequestParam Map<String, String> query) {
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header("Content-Disposition", "attachment; filename=cyt-common-calls.csv")
        .body(monitoring.csv(query));
  }

  @PostMapping("/admin/api/maintenance/purge")
  public Object purge(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    int days = MonitoringService.bounded(JsonUtils.str(body, "days", "30"), 30, 1, 36500);
    if (!JsonUtils.bool(body.get("confirm"))) throw GatewayException.invalid("清理日志需要 confirm=true");
    return store.atomic(
        () -> {
          int deleted =
              store.update(
                  "DELETE FROM gateway_call_log WHERE called_at<?",
                  java.sql.Timestamp.from(
                      java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS)));
          store.audit(
              AuthenticationService.actor(request),
              "logs_purge",
              "gateway_call_log",
              JsonUtils.obj("days", days, "deleted", deleted));
          return JsonUtils.obj("ok", true, "deleted", deleted);
        });
  }
}

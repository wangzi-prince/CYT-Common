package group.cyt.common.core.config;

import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.AdapterDispatcher;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class GatewayBootstrap implements ApplicationRunner {
  private final GatewayStore store;
  private final GatewaySettings settings;
  private final OperationRegistry registry;

  public GatewayBootstrap(
      GatewayStore store, GatewaySettings settings, OperationRegistry registry) {
    this.store = store;
    this.settings = settings;
    this.registry = registry;
  }

  @Override
  public void run(ApplicationArguments args) {
    var environment =
        store.one("SELECT * FROM gateway_environment WHERE `key`=?", settings.current());
    boolean production =
        settings.production()
            || environment != null && JsonUtils.bool(environment.get("is_production"));
    if (!production
        && settings.flag("JAVA_SEED_ENABLED", settings.mock())
        && store.count("SELECT COUNT(*) FROM gateway_system") == 0
        && store.count("SELECT COUNT(*) FROM gateway_grant") == 0
        && store.count("SELECT COUNT(*) FROM gateway_admin_audit WHERE action='demo_initialize'")
            == 0) seed();
    if (production) {
      settings.markProductionEnvironment();
      List<String> issues = new ArrayList<>();
      if (settings.mock()) issues.add("MOCK_ALL 必须为 false");
      for (var op : registry.operations())
        for (String flag : AdapterDispatcher.mockFlags(op))
          if (settings.flag(flag, false) && !issues.contains("生产环境不能开启模拟: " + flag))
            issues.add("生产环境不能开启模拟: " + flag);
      if (settings.flag("GATEWAY_DEV_MODE", false)) issues.add("不能开启 GATEWAY_DEV_MODE");
      if (settings.flag("CUSTOM_API_ALLOW_LOOPBACK", false))
        issues.add("不能开启 CUSTOM_API_ALLOW_LOOPBACK");
      if (settings.get("ADMIN_JWT_SECRET").length() < 32) issues.add("ADMIN_JWT_SECRET 至少 32 字符");
      if (settings.get("ADMIN_PASSWORD").isBlank()
          && settings.get("ADMIN_PASSWORD_HASH").isBlank()
          && settings.modes().contains("admin_jwt")) issues.add("必须配置管理员密码或摘要");
      if (settings.get("ADMIN_PASSWORD").equals("admin123")) issues.add("不能使用默认管理员密码");
      if (settings.current().equals("local")) issues.add("生产环境不能使用 ENV=local");
      if (!issues.isEmpty())
        throw new IllegalStateException("生产配置校验失败: " + String.join("；", issues));
    }
  }

  private void seed() {
    store.atomic(
        () -> {
          for (var s : registry.contract().path("systems")) {
            String key = s.path("key").asText();
            if (store.count("SELECT COUNT(*) FROM gateway_system WHERE `key`=?", key) == 0)
              store.insert(
                  "gateway_system",
                  JsonUtils.obj(
                      "key",
                      key,
                      "display_name",
                      s.path("display_name").asText(),
                      "description",
                      s.path("description").asText(),
                      "enabled",
                      true));
          }
          for (var g : registry.contract().path("grants")) {
            String system = g.path("system").asText(),
                pattern = g.path("operation_pattern").asText();
            if (store.count(
                    "SELECT COUNT(*) FROM gateway_grant WHERE `system`=? AND operation_pattern=?",
                    system,
                    pattern)
                == 0)
              store.insert(
                  "gateway_grant",
                  JsonUtils.obj(
                      "system",
                      system,
                      "operation_pattern",
                      pattern,
                      "quota",
                      g.path("quota").asText(),
                      "note",
                      g.path("note").asText(),
                      "granted_by",
                      "seed"));
          }
          for (String key : List.of("local", "staging", "production"))
            if (store.count("SELECT COUNT(*) FROM gateway_environment WHERE `key`=?", key) == 0)
              store.insert(
                  "gateway_environment",
                  JsonUtils.obj(
                      "key",
                      key,
                      "label",
                      switch (key) {
                        case "local" -> "本地开发";
                        case "staging" -> "预发布环境";
                        default -> "生产环境";
                      },
                      "description",
                      "",
                      "is_production",
                      key.equals("production"),
                      "created_by",
                      "seed"));
          store.audit("bootstrap", "demo_initialize", "local", Map.of());
          return null;
        });
  }
}

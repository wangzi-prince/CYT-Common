package group.cyt.common.core.config;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class GatewaySettings {
  private final Environment env;
  private volatile boolean productionEnvironment;

  public GatewaySettings(Environment env) {
    this.env = env;
  }

  public String get(String key) {
    return get(key, "");
  }

  public String get(String key, String fallback) {
    return env.getProperty(key, fallback);
  }

  public boolean flag(String key, boolean fallback) {
    return JsonUtils.bool(get(key, Boolean.toString(fallback)));
  }

  public List<String> csv(String key, String fallback) {
    return Arrays.stream(get(key, fallback).split(","))
        .map(String::trim)
        .filter(x -> !x.isBlank())
        .toList();
  }

  public String current() {
    return get("ENV", "local").trim().toLowerCase(Locale.ROOT);
  }

  public boolean production() {
    return productionEnvironment || Set.of("prod", "production", "prd").contains(current());
  }

  public void markProductionEnvironment() {
    productionEnvironment = true;
  }

  public boolean dev() {
    return !production() && flag("GATEWAY_DEV_MODE", false);
  }

  public boolean devLogin() {
    return !production()
        && (Set.of("local", "dev", "development", "staging", "test", "qa", "preview")
                .contains(current())
            || flag("ADMIN_DEV_LOGIN", false));
  }

  public boolean mock() {
    return flag("MOCK_ALL", true);
  }

  public List<String> modes() {
    return csv("ADMIN_AUTH_MODES", "admin_jwt").stream()
        .filter(x -> !x.equals("static") || current().equals("local"))
        .toList();
  }

  public String jwtKey() {
    String s = get("ADMIN_JWT_SECRET");
    if (!s.isBlank()) return s;
    if (production()) throw GatewayException.config("生产环境必须配置 ADMIN_JWT_SECRET");
    return LOCAL_KEY;
  }

  private static final String LOCAL_KEY = JsonUtils.random(48);
}

package group.cyt.common.module.adapter.discord;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.credential.service.CredentialService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Fixed Discord endpoints, shared HTTP connections and credential-isolated metadata caching. */
@Component
public class DiscordClient {
  private static final String API = "https://discord.com/api/v10";

  private record Entry(long expires, String json) {}

  private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CompletableFuture<Object>> pending =
      new ConcurrentHashMap<>();
  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final GatewaySettings settings;

  public DiscordClient(CredentialService credentials, UpstreamHttp http, GatewaySettings settings) {
    this.credentials = credentials;
    this.http = http;
    this.settings = settings;
  }

  public String token(String account, String system) {
    String key =
        account.isBlank()
            ? "DISCORD_BOT_TOKEN"
            : "DISCORD_" + CredentialService.accountKey(account) + "_BOT_TOKEN";
    String value = credentials.resolve(key, system).strip();
    if (value.isEmpty()) throw GatewayException.config("Discord Bot Token 尚未配置");
    return value;
  }

  public List<String> accounts() {
    var names = credentials.accounts("discord");
    return names.isEmpty() && !credentials.raw("DISCORD_BOT_TOKEN").isBlank() ? List.of("") : names;
  }

  public Object request(
      String account,
      String system,
      String method,
      String path,
      Map<String, Object> params,
      Object body,
      int ttl,
      boolean refresh) {
    String token = token(account, system);
    Supplier<Object> fetch =
        () -> {
          String url = UpstreamHttp.query(API + path, params == null ? Map.of() : params);
          var response =
              http.quietExchange(
                  method,
                  url,
                  Map.of(
                      "Authorization",
                      "Bot " + token,
                      "User-Agent",
                      "CYT-Common/1.0",
                      "Content-Type",
                      "application/json"),
                  body == null ? null : JsonUtils.write(body).getBytes(StandardCharsets.UTF_8),
                  25);
          if (response.body().length > 8 * 1024 * 1024)
            throw new GatewayException(502, "upstream_error", "Discord 响应过大", false);
          Object data;
          try {
            data =
                response.body().length == 0
                    ? Map.of()
                    : JsonUtils.parse(new String(response.body(), StandardCharsets.UTF_8));
          } catch (GatewayException e) {
            throw new GatewayException(502, "upstream_error", "Discord 返回了无效的 JSON", false);
          }
          if (response.status() >= 400) {
            var detail = JsonUtils.map(data);
            int status = response.status();
            var error =
                new GatewayException(
                        status == 429 ? 429 : 502,
                        status == 429
                            ? "rate_limited"
                            : status == 401 || status == 403 ? "auth_failed" : "upstream_error",
                        JsonUtils.str(detail, "message", "Discord 请求失败"),
                        status == 429 || status >= 500)
                    .detail(detail);
            if (status == 429) {
              Object delay = detail.getOrDefault("retry_after", 1);
              error.retryAfter =
                  delay instanceof Number n ? Math.max(1, (int) Math.ceil(n.doubleValue())) : 1;
            }
            throw error;
          }
          return data;
        };
    if (!method.equals("GET") || ttl <= 0) return fetch.get();
    String key =
        settings.current()
            + ":"
            + system
            + ":"
            + account
            + ":"
            + JsonUtils.sha(token)
            + ":"
            + path
            + ":"
            + JsonUtils.write(JsonUtils.sorted(params == null ? Map.of() : params));
    Entry hit = cache.get(key);
    if (!refresh && hit != null && hit.expires() > System.nanoTime())
      return JsonUtils.parse(hit.json());
    String flight = key + ":" + refresh;
    var mine = new CompletableFuture<Object>();
    var existing = pending.putIfAbsent(flight, mine);
    if (existing != null) {
      try {
        return JsonUtils.parse(JsonUtils.write(existing.join()));
      } catch (CompletionException e) {
        if (e.getCause() instanceof RuntimeException cause) throw cause;
        throw e;
      }
    }
    try {
      Object value = fetch.get();
      if (cache.size() >= 512) cache.clear();
      cache.put(
          key,
          new Entry(System.nanoTime() + TimeUnit.SECONDS.toNanos(ttl), JsonUtils.write(value)));
      mine.complete(value);
      return JsonUtils.parse(JsonUtils.write(value));
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      pending.remove(flight, mine);
    }
  }

  public Map<String, Object> identity(String account, String system, boolean refresh) {
    var row =
        JsonUtils.map(request(account, system, "GET", "/users/@me", Map.of(), null, 60, refresh));
    return JsonUtils.obj(
        "id", row.get("id"), "username", row.get("username"), "bot", row.get("bot"));
  }
}

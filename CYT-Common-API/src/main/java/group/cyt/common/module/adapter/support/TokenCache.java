package group.cyt.common.module.adapter.support;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class TokenCache {
  private record Token(String value, long expires) {}

  private final Map<String, Token> cache = new ConcurrentHashMap<>();

  public String get(String identity, boolean refresh, Supplier<Map<String, Object>> loader) {
    long now = System.currentTimeMillis();
    String key = JsonUtils.sha(identity);
    Token old = cache.get(key);
    if (!refresh && old != null && old.expires() > now + 120000) return old.value();
    var result = loader.get();
    String token = JsonUtils.str(result, "access_token");
    if (token.isBlank()) throw new GatewayException(502, "auth_failed", "上游未返回有效访问令牌", false);
    if (cache.size() > 2048) cache.entrySet().removeIf(e -> e.getValue().expires() < now);
    cache.put(
        key,
        new Token(
            token, now + Math.max(1, JsonUtils.integer(result.get("expires_in"), 3600)) * 1000L));
    return token;
  }
}

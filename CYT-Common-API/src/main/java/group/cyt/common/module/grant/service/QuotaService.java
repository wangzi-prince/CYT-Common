package group.cyt.common.module.grant.service;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class QuotaService implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
  private static final DefaultRedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          "redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',ARGV[1]-ARGV[2]); local n=redis.call('ZCARD',KEYS[1]); if n>=tonumber(ARGV[3]) then return 0 end; redis.call('ZADD',KEYS[1],ARGV[1],ARGV[4]); redis.call('PEXPIRE',KEYS[1],ARGV[2]); return 1",
          Long.class);
  private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();
  private LettuceConnectionFactory factory;
  private StringRedisTemplate redis;
  private volatile boolean fallback;

  public QuotaService(GatewaySettings settings) {
    String url = settings.get("REDIS_URL");
    if (!url.isBlank()) {
      URI uri = URI.create(url);
      var c =
          new RedisStandaloneConfiguration(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort());
      if (uri.getUserInfo() != null) {
        String[] p = uri.getUserInfo().split(":", 2);
        if (p.length == 2) {
          if (!p[0].isBlank()) c.setUsername(p[0]);
          c.setPassword(p[1]);
        } else c.setPassword(p[0]);
      }
      if (uri.getPath() != null && uri.getPath().length() > 1)
        c.setDatabase(Integer.parseInt(uri.getPath().substring(1)));
      factory = new LettuceConnectionFactory(c);
      factory.afterPropertiesSet();
      redis = new StringRedisTemplate(factory);
    }
  }

  public static long[] parse(String quota) {
    try {
      String[] parts = quota.split("/", 2);
      long n = Long.parseLong(parts[0]);
      long window =
          switch (parts[1]) {
            case "s", "sec", "second" -> 1000;
            case "m", "min", "minute" -> 60000;
            case "h", "hour" -> 3600000;
            case "d", "day" -> 86400000;
            default -> throw new IllegalArgumentException();
          };
      if (n < 1 || n > 1000000) throw new IllegalArgumentException();
      return new long[] {n, window};
    } catch (Exception e) {
      throw GatewayException.invalid("配额格式无效，例如 60/min");
    }
  }

  public String backend() {
    return redis == null || fallback ? "memory" : "redis";
  }

  public void consume(String key, String quota) {
    if (quota == null || quota.isBlank()) return;
    long[] q = parse(quota);
    long now = System.currentTimeMillis();
    if (redis != null) {
      try {
        Long ok =
            redis.execute(
                SCRIPT,
                List.of("cyt:quota:" + key),
                Long.toString(now),
                Long.toString(q[1]),
                Long.toString(q[0]),
                JsonUtils.random(18));
        fallback = false;
        if (!Long.valueOf(1).equals(ok)) throw limited(q[1]);
        return;
      } catch (GatewayException e) {
        throw e;
      } catch (Exception e) {
        if (!fallback) log.warn("Redis quota unavailable; falling back to process memory");
        fallback = true;
      }
    }
    var bucket = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (bucket) {
      while (!bucket.isEmpty() && bucket.peekFirst() <= now - q[1]) bucket.removeFirst();
      if (bucket.size() >= q[0]) throw limited(q[1] - (now - bucket.peekFirst()));
      bucket.addLast(now);
    }
  }

  private GatewayException limited(long ms) {
    var ex = new GatewayException(429, "rate_limited", "调用频率超过配额", true);
    ex.retryAfter = (int) Math.max(1, (ms + 999) / 1000);
    return ex;
  }

  @PreDestroy
  public void close() {
    if (factory != null) factory.destroy();
  }
}

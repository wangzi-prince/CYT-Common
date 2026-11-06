package group.cyt.common.module.auth.service;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
  private final GatewaySettings settings;
  private final GatewayStore store;
  private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
  private final RemoteJWKSet<SecurityContext> googleKeys = createGoogleKeys();

  private static RemoteJWKSet<SecurityContext> createGoogleKeys() {
    try {
      return new RemoteJWKSet<>(URI.create("https://www.googleapis.com/oauth2/v3/certs").toURL());
    } catch (java.net.MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  public AuthenticationService(GatewaySettings settings, GatewayStore store) {
    this.settings = settings;
    this.store = store;
  }

  public String system(HttpServletRequest request) {
    if (settings.dev() && !header(request, "X-System-Id").isBlank())
      return enabled(header(request, "X-System-Id").trim().toLowerCase(Locale.ROOT));
    String token = bearer(request);
    if (token.startsWith("cyt_") && settings.flag("GATEWAY_APIKEY_ENABLED", true)) {
      String[] parts = token.split("_", 3);
      if (parts.length != 3) throw GatewayException.unauth("API Key 格式无效");
      var key =
          store.one(
              "SELECT * FROM gateway_system_apikey WHERE prefix=? AND revoked=false", parts[1]);
      if (key == null
          || !JsonUtils.equal(JsonUtils.sha(parts[2]), JsonUtils.str(key, "secret_hash")))
        throw GatewayException.unauth("API Key 无效或已吊销");
      String system = enabled(JsonUtils.str(key, "system"));
      store.update(
          "UPDATE gateway_system_apikey SET last_used_at=? WHERE id=?",
          GatewayStore.now(),
          key.get("id"));
      return system;
    }
    try {
      var processor = new DefaultJWTProcessor<SecurityContext>();
      processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, googleKeys));
      var claims = processor.process(token, null);
      if (claims.getExpirationTime() == null
          || claims.getExpirationTime().before(new Date())
          || claims.getIssueTime() == null
          || claims.getAudience() == null
          || settings.get("GATEWAY_AUDIENCE").isBlank()
          || !claims.getAudience().contains(settings.get("GATEWAY_AUDIENCE"))
          || !Set.of("https://accounts.google.com", "accounts.google.com")
              .contains(claims.getIssuer())) throw GatewayException.unauth("Google ID token 声明无效");
      String email = Objects.toString(claims.getStringClaim("email"), "").toLowerCase(Locale.ROOT);
      var allowed = settings.csv("GATEWAY_ALLOWED_SA_EMAILS", "");
      if (email.isBlank() || (!allowed.isEmpty() && !allowed.contains(email)))
        throw GatewayException.forbidden("该服务账号未获准调用网关");
      String mapping = settings.get("GATEWAY_SA_SYSTEM_MAP");
      Map<String, Object> map = new HashMap<>();
      if (mapping.startsWith("{")) map = JsonUtils.read(mapping);
      else
        for (String pair : mapping.split(",")) {
          String[] p = pair.split("[:=]", 2);
          if (p.length == 2) map.put(p[0].trim(), p[1].trim());
        }
      String system = JsonUtils.str(map, email);
      if (system.isBlank()) throw GatewayException.forbidden("未配置服务账号到系统的显式映射");
      return enabled(system);
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw GatewayException.unauth("调用方身份凭证无效");
    }
  }

  public String enabled(String system) {
    var row = store.one("SELECT enabled FROM gateway_system WHERE `key`=?", system);
    if (row == null || !JsonUtils.bool(row.get("enabled")))
      throw GatewayException.forbidden("系统未注册或已停用: " + system);
    return system;
  }

  public String admin(HttpServletRequest request) {
    var modes = settings.modes();
    String bearer = header(request, "Authorization");
    String token =
        bearer.toLowerCase(Locale.ROOT).startsWith("bearer ") ? bearer.substring(7).trim() : "";
    if (modes.contains("portal_proxy") && !header(request, "X-Portal-Signature").isBlank()) {
      String user = header(request, "X-Portal-User"),
          ts = header(request, "X-Portal-Ts"),
          secret = settings.get("PORTAL_PROXY_SECRET");
      long seconds;
      try {
        seconds = Long.parseLong(ts);
      } catch (Exception e) {
        throw GatewayException.unauth("代理签名时间无效");
      }
      if (secret.isBlank()) throw GatewayException.unauth("未配置 Portal 代理密钥");
      String expected =
          JsonUtils.hmac(
              secret,
              user + "\n" + ts + "\n" + request.getMethod() + "\n" + request.getRequestURI());
      if (secret.isBlank()
          || user.isBlank()
          || Math.abs(Instant.now().getEpochSecond() - seconds) > 60
          || !JsonUtils.equal(expected, header(request, "X-Portal-Signature")))
        throw GatewayException.unauth("代理签名无效或已过期");
      return "portal:" + user;
    }
    if (!token.isBlank()) {
      if (modes.contains("admin_jwt")) {
        var claims = verify(token, settings.jwtKey(), "cyt-common-api-admin", null);
        if (claims != null) return claims.getSubject();
      }
      if (modes.contains("portal_jwt")) {
        var claims =
            verify(
                token,
                settings.get("PORTAL_JWT_SECRET"),
                null,
                settings.get("PORTAL_JWT_AUDIENCE", "common-api-admin"));
        if (claims != null) {
          try {
            if (!Set.of("admin", "ops", "owner").contains(claims.getStringClaim("role")))
              throw GatewayException.unauth("角色无管理权限");
          } catch (java.text.ParseException e) {
            throw GatewayException.unauth("角色无效");
          }
          return "portal:" + claims.getSubject();
        }
      }
    }
    String staticToken = settings.get("ADMIN_TOKEN");
    if (modes.contains("static")
        && !staticToken.isBlank()
        && JsonUtils.equal(staticToken, header(request, "X-Admin-Token"))) return "admin";
    throw GatewayException.unauth("请登录管理后台");
  }

  private JWTClaimsSet verify(String token, String secret, String issuer, String audience) {
    try {
      if (secret.isBlank()) return null;
      SignedJWT jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
          || !jwt.verify(new MACVerifier(secret))) return null;
      var c = jwt.getJWTClaimsSet();
      long now = System.currentTimeMillis();
      if (c.getSubject() == null
          || c.getSubject().isBlank()
          || c.getExpirationTime() == null
          || c.getExpirationTime().getTime() <= now) return null;
      if (c.getNotBeforeTime() != null && c.getNotBeforeTime().getTime() > now + 60000) return null;
      if (c.getIssueTime() != null && c.getIssueTime().getTime() > now + 60000) return null;
      if (issuer != null && !issuer.equals(c.getIssuer())) return null;
      if (audience != null && !c.getAudience().contains(audience)) return null;
      return c;
    } catch (Exception e) {
      return null;
    }
  }

  public Map<String, Object> session(String actor) {
    try {
      Instant now = Instant.now();
      long hours = JsonUtils.integer(settings.get("ADMIN_SESSION_HOURS", "8"), 8);
      Instant exp = now.plusSeconds(Math.max(1, Math.min(24, hours)) * 3600);
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("cyt-common-api-admin")
              .subject(actor)
              .claim("role", "admin")
              .issueTime(Date.from(now))
              .expirationTime(Date.from(exp))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(settings.jwtKey()));
      return JsonUtils.obj(
          "token", jwt.serialize(), "expires_at", exp.getEpochSecond(), "actor", actor);
    } catch (JOSEException e) {
      throw GatewayException.config("后台会话密钥必须至少 32 字节");
    }
  }

  public Map<String, Object> login(String username, String password, String ip) {
    Deque<Long> bucket = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
    synchronized (bucket) {
      long now = System.currentTimeMillis();
      while (!bucket.isEmpty() && bucket.peekFirst() < now - 300000) bucket.removeFirst();
      if (bucket.size() >= 8) {
        var ex = new GatewayException(429, "rate_limited", "登录失败次数过多，请稍后重试", true);
        ex.retryAfter = 300;
        throw ex;
      }
      String expected = settings.get("ADMIN_PASSWORD"), hash = settings.get("ADMIN_PASSWORD_HASH");
      boolean valid =
          JsonUtils.equal(username.trim(), settings.get("ADMIN_USERNAME", "admin"))
              && (!hash.isBlank()
                  ? JsonUtils.equal(hash.toLowerCase(Locale.ROOT), JsonUtils.sha(password))
                  : !expected.isBlank() && JsonUtils.equal(password, expected));
      if (!valid) {
        bucket.addLast(now);
        throw GatewayException.unauth("账号或密码不正确");
      }
      bucket.clear();
      return session(username.trim());
    }
  }

  public static String actor(HttpServletRequest request) {
    return Objects.toString(request.getAttribute("admin.actor"), "admin");
  }

  public static String header(HttpServletRequest request, String name) {
    return Objects.toString(request.getHeader(name), "");
  }

  private static String bearer(HttpServletRequest request) {
    String h = header(request, "Authorization");
    if (!h.toLowerCase(Locale.ROOT).startsWith("bearer ")) throw GatewayException.unauth("缺少调用方凭证");
    return h.substring(7).trim();
  }
}

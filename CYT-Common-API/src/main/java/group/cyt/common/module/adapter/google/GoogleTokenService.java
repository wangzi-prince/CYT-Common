package group.cyt.common.module.adapter.google;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.support.*;
import group.cyt.common.module.credential.service.CredentialService;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class GoogleTokenService {
  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final TokenCache cache;

  public GoogleTokenService(CredentialService credentials, UpstreamHttp http, TokenCache cache) {
    this.credentials = credentials;
    this.http = http;
    this.cache = cache;
  }

  public String user(String system) {
    String id = credentials.required("GOOGLE_CLIENT_ID", system),
        secret = credentials.required("GOOGLE_CLIENT_SECRET", system),
        refresh = credentials.resolve("GOOGLE_REFRESH_TOKEN", system);
    if (refresh.isBlank()) refresh = credentials.required("GMAIL_REFRESH_TOKEN", system);
    String refreshToken = refresh;
    return cache.get(
        "google-user:" + system + ":" + id + ":" + secret + ":" + refresh,
        false,
        () ->
            JsonUtils.map(
                http.form(
                    "https://oauth2.googleapis.com/token",
                    Map.of(
                        "client_id",
                        id,
                        "client_secret",
                        secret,
                        "refresh_token",
                        refreshToken,
                        "grant_type",
                        "refresh_token"),
                    Map.of())));
  }

  public String service(String system) {
    String raw = credentials.resolve("GOOGLE_SA_JSON", system);
    if (raw.isBlank()) raw = credentials.resolve("GCS_SA_JSON", system);
    if (raw.isBlank()) raw = credentials.required("GOOGLE_APPLICATION_CREDENTIALS", system);
    try {
      if (!raw.strip().startsWith("{")) raw = Files.readString(Path.of(raw));
    } catch (Exception e) {
      throw GatewayException.config("无法读取 Google 服务账号文件");
    }
    var sa = JsonUtils.read(raw);
    String key = raw;
    return cache.get(
        "google-sa:" + system + ":" + key,
        false,
        () -> {
          try {
            Instant now = Instant.now();
            var claims =
                new JWTClaimsSet.Builder()
                    .issuer(JsonUtils.str(sa, "client_email"))
                    .audience("https://oauth2.googleapis.com/token")
                    .claim("scope", "https://www.googleapis.com/auth/devstorage.read_write")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(3600)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(
                new RSASSASigner(SignatureSupport.privateKey(JsonUtils.str(sa, "private_key"))));
            return JsonUtils.map(
                http.form(
                    "https://oauth2.googleapis.com/token",
                    Map.of(
                        "grant_type",
                        "urn:ietf:params:oauth:grant-type:jwt-bearer",
                        "assertion",
                        jwt.serialize()),
                    Map.of()));
          } catch (JOSEException e) {
            throw GatewayException.config("Google 服务账号签名失败");
          }
        });
  }
}

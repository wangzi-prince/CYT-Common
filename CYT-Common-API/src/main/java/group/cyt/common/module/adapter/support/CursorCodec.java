package group.cyt.common.module.adapter.support;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Opaque, tamper-evident cursors cannot be reused for another caller or query. */
@Component
public class CursorCodec {
  private final GatewaySettings settings;

  public CursorCodec(GatewaySettings settings) {
    this.settings = settings;
  }

  public String encode(String scope, Map<String, Object> value) {
    String body =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                JsonUtils.write(JsonUtils.obj("v", 1, "scope", scope, "state", value))
                    .getBytes(StandardCharsets.UTF_8));
    return body + "." + signature(body);
  }

  public Map<String, Object> decode(String scope, String cursor) {
    if (cursor.isBlank()) return Map.of();
    try {
      if (cursor.length() > 16000) throw new IllegalArgumentException();
      String[] parts = cursor.split("\\.");
      if (parts.length != 2 || !JsonUtils.equal(signature(parts[0]), parts[1]))
        throw new IllegalArgumentException();
      var value =
          JsonUtils.read(
              new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
      if (!scope.equals(value.get("scope")) || JsonUtils.integer(value.get("v"), 0) != 1)
        throw new IllegalArgumentException();
      return JsonUtils.map(value.get("state"));
    } catch (Exception e) {
      throw GatewayException.invalid("游标无效或已过期，请使用相同系统、筛选条件重新查询");
    }
  }

  private String signature(String body) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            JsonUtils.hmac(
                settings.jwtKey().getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)));
  }
}

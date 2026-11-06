package group.cyt.common.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import group.cyt.common.core.exception.GatewayException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class JsonUtils {
  public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private JsonUtils() {}

  public static Map<String, Object> obj(Object... entries) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) out.put(String.valueOf(entries[i]), entries[i + 1]);
    return out;
  }

  public static String write(Object v) {
    try {
      return MAPPER.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON serialization failed", e);
    }
  }

  public static JsonNode tree(Object v) {
    return MAPPER.valueToTree(v);
  }

  public static Map<String, Object> read(String s) {
    try {
      return MAPPER.readValue(s, new TypeReference<>() {});
    } catch (Exception e) {
      throw GatewayException.invalid("无效的 JSON 对象");
    }
  }

  public static Object parse(String s) {
    try {
      return MAPPER.readValue(s, Object.class);
    } catch (Exception e) {
      throw GatewayException.invalid("无效的 JSON");
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object v) {
    return v instanceof Map<?, ?> ? (Map<String, Object>) v : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  public static List<Object> list(Object v) {
    return v instanceof List<?> ? (List<Object>) v : List.of();
  }

  public static String str(Map<String, Object> m, String k) {
    return Objects.toString(m.get(k), "");
  }

  public static String str(Map<String, Object> m, String k, String fallback) {
    return Objects.toString(m.get(k), fallback);
  }

  public static boolean bool(Object v) {
    return v instanceof Boolean b
        ? b
        : v instanceof Number n
            ? n.intValue() != 0
            : Set.of("true", "1", "yes", "on")
                .contains(Objects.toString(v, "").toLowerCase(Locale.ROOT));
  }

  public static int integer(Object v, int d) {
    try {
      return Integer.parseInt(String.valueOf(v));
    } catch (Exception e) {
      return d;
    }
  }

  public static Object at(Object v, String path) {
    if (path == null || path.isBlank()) return v;
    for (String p : path.split("\\.")) {
      if (v instanceof Map<?, ?> m) v = m.get(p);
      else if (v instanceof List<?> l) {
        int i = integer(p, -1);
        v = i >= 0 && i < l.size() ? l.get(i) : null;
      } else return null;
    }
    return v;
  }

  public static Object sorted(Object v) {
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> out = new TreeMap<>();
      m.forEach((k, x) -> out.put(k.toString(), sorted(x)));
      return out;
    }
    if (v instanceof List<?> l) return l.stream().map(JsonUtils::sorted).toList();
    return v;
  }

  public static byte[] digest(String algorithm, byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha(String s) {
    return HexFormat.of().formatHex(digest("SHA-256", s.getBytes(StandardCharsets.UTF_8)));
  }

  public static byte[] hmac(byte[] key, byte[] bytes) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hmac(String key, String text) {
    return HexFormat.of()
        .formatHex(
            hmac(key.getBytes(StandardCharsets.UTF_8), text.getBytes(StandardCharsets.UTF_8)));
  }

  public static boolean equal(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  public static String random(int bytes) {
    byte[] b = new byte[bytes];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}

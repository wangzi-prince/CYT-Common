package group.cyt.common.module.adapter.support;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

public final class SignatureSupport {
  private SignatureSupport() {}

  public static String winit(Map<String, Object> params, String secret) {
    StringBuilder canonical = new StringBuilder(secret);
    for (String field :
        List.of(
            "action",
            "app_key",
            "data",
            "format",
            "platform",
            "sign_method",
            "timestamp",
            "version")) {
      Object v = params.get(field);
      if (v == null || v.equals("")) continue;
      canonical
          .append(field)
          .append(
              v instanceof Map<?, ?> || v instanceof List<?>
                  ? JsonUtils.write(JsonUtils.sorted(v))
                  : v);
    }
    return md5(canonical.append(secret).toString());
  }

  public static String md5(String value) {
    return HexFormat.of()
        .withUpperCase()
        .formatHex(JsonUtils.digest("MD5", value.getBytes(StandardCharsets.UTF_8)));
  }

  public static String lingxingCanonical(Map<String, Object> params) {
    List<String> pairs = new ArrayList<>();
    new TreeMap<>(params)
        .forEach(
            (k, v) -> {
              if (v != null && !v.equals(""))
                pairs.add(
                    k
                        + "="
                        + (v instanceof Map<?, ?> || v instanceof List<?>
                            ? JsonUtils.write(JsonUtils.sorted(v))
                            : v));
            });
    return String.join("&", pairs);
  }

  public static String lingxing(String appId, Map<String, Object> params) {
    try {
      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(appId.getBytes(StandardCharsets.UTF_8), "AES"));
      return Base64.getEncoder()
          .encodeToString(
              cipher.doFinal(md5(lingxingCanonical(params)).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw GatewayException.config("领星 App ID 必须是有效的 AES 密钥（16/24/32 字节）");
    }
  }

  public static String quickcep(String publicKey, String accessId, long timestamp) {
    try {
      String pem =
          publicKey
              .replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replaceAll("\\s", "");
      PublicKey key =
          KeyFactory.getInstance("RSA")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
      Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
      cipher.init(Cipher.ENCRYPT_MODE, key);
      return Base64.getEncoder()
          .encodeToString(
              cipher.doFinal(
                  ("{\"timestamp\":" + timestamp + ", \"access_id\": \"" + accessId + "\"}")
                      .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw GatewayException.config("QuickCEP RSA 公钥配置无效");
    }
  }

  public static java.security.interfaces.RSAPrivateKey privateKey(String pem) {
    try {
      pem = pem.replace("\\n", "\n");
      boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
      byte[] bytes =
          Base64.getDecoder().decode(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
      if (pkcs1) {
        byte[] version = {2, 1, 0},
            oid = {48, 13, 6, 9, 42, (byte) 134, 72, (byte) 134, (byte) 247, 13, 1, 1, 1, 5, 0};
        byte[] octet = der(4, bytes);
        byte[] seq = new byte[version.length + oid.length + octet.length];
        System.arraycopy(version, 0, seq, 0, version.length);
        System.arraycopy(oid, 0, seq, version.length, oid.length);
        System.arraycopy(octet, 0, seq, version.length + oid.length, octet.length);
        bytes = der(48, seq);
      }
      return (java.security.interfaces.RSAPrivateKey)
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    } catch (Exception e) {
      throw GatewayException.config("RSA 私钥配置无效");
    }
  }

  private static byte[] der(int tag, byte[] value) {
    int lengthBytes =
        value.length < 128 ? 0 : value.length < 256 ? 1 : value.length < 65536 ? 2 : 3;
    byte[] result = new byte[2 + lengthBytes + value.length];
    result[0] = (byte) tag;
    result[1] = (byte) (lengthBytes == 0 ? value.length : 0x80 | lengthBytes);
    for (int i = 0; i < lengthBytes; i++)
      result[2 + i] = (byte) (value.length >> (8 * (lengthBytes - i - 1)));
    System.arraycopy(value, 0, result, 2 + lengthBytes, value.length);
    return result;
  }
}

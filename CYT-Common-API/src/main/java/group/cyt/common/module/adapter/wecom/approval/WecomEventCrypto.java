package group.cyt.common.module.adapter.wecom.approval;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Implements the WeCom documented SHA-1 + AES-CBC/32-byte-padding envelope. */
public final class WecomEventCrypto {
  private WecomEventCrypto() {}

  public static String decrypt(
      String token,
      String encodingKey,
      String receiver,
      String signature,
      String timestamp,
      String nonce,
      String encrypted) {
    if (token.isBlank() || encodingKey.length() != 43 || receiver.isBlank())
      throw GatewayException.config("需要配置企微回调 Token、43 位 EncodingAESKey 和企业 ID");
    try {
      if (signature == null
          || timestamp == null
          || nonce == null
          || encrypted == null
          || !timestamp.matches("\\d{1,16}")
          || nonce.length() > 256
          || encrypted.length() > 1_500_000) throw new IllegalArgumentException();
      var pieces = new ArrayList<>(List.of(token, timestamp, nonce, encrypted));
      Collections.sort(pieces);
      String expected =
          HexFormat.of()
              .formatHex(
                  JsonUtils.digest(
                      "SHA-1", String.join("", pieces).getBytes(StandardCharsets.UTF_8)));
      if (!JsonUtils.equal(expected, signature)) throw new IllegalArgumentException();
      byte[] key = Base64.getDecoder().decode(encodingKey + "="),
          data = Base64.getDecoder().decode(encrypted);
      Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new IvParameterSpec(Arrays.copyOf(key, 16)));
      byte[] plain = cipher.doFinal(data);
      int padding = plain[plain.length - 1] & 255;
      if (padding < 1 || padding > 32 || padding > plain.length)
        throw new IllegalArgumentException();
      for (int i = plain.length - padding; i < plain.length; i++)
        if ((plain[i] & 255) != padding) throw new IllegalArgumentException();
      int length = ByteBuffer.wrap(plain, 16, 4).getInt(), end = 20 + length;
      if (length < 0 || end < 20 || end > plain.length - padding)
        throw new IllegalArgumentException();
      if (!JsonUtils.equal(
          receiver, new String(plain, end, plain.length - padding - end, StandardCharsets.UTF_8)))
        throw new IllegalArgumentException();
      return new String(plain, 20, length, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw GatewayException.unauth("企微回调验签或解密失败");
    }
  }

  public static Map<String, Object> xml(byte[] data) {
    if (data.length > 1024 * 1024) throw GatewayException.invalid("企微回调内容过大");
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException error)
                throws org.xml.sax.SAXException {
              throw error;
            }
          });
      var root = builder.parse(new ByteArrayInputStream(data)).getDocumentElement();
      if (!root.getTagName().equals("xml")) throw new IllegalArgumentException();
      return JsonUtils.map(element(root, 0, new int[] {0}));
    } catch (Exception e) {
      throw GatewayException.invalid("企微回调 XML 格式无效");
    }
  }

  private static Object element(Element node, int depth, int[] count) {
    if (depth > 24 || ++count[0] > 20000) throw new IllegalArgumentException();
    var values = new LinkedHashMap<String, Object>();
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element e) {
        Object value = element(e, depth + 1, count);
        String key = e.getTagName();
        if (values.containsKey(key)) {
          Object old = values.get(key);
          var list =
              old instanceof List<?>
                  ? new ArrayList<>(JsonUtils.list(old))
                  : new ArrayList<>(List.of(old));
          list.add(value);
          values.put(key, list);
        } else values.put(key, value);
      }
    return values.isEmpty() ? node.getTextContent() : values;
  }
}

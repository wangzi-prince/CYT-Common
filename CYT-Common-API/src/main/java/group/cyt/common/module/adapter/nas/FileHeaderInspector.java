package group.cyt.common.module.adapter.nas;

import group.cyt.common.core.util.JsonUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Inspects bounded headers only; never decodes pixels or claims a file is safe/complete. */
public final class FileHeaderInspector {
  private FileHeaderInspector() {}

  public static Map<String, Object> inspect(byte[] bytes, String declared, boolean truncated) {
    String mime = null, format = null;
    Integer width = null, height = null;
    var warnings = new ArrayList<String>();
    boolean image = false;
    if (starts(bytes, 0, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})) {
      mime = "image/png";
      format = "png";
      image = true;
    } else if (bytes.length >= 3
        && (bytes[0] & 255) == 255
        && (bytes[1] & 255) == 216
        && (bytes[2] & 255) == 255) {
      mime = "image/jpeg";
      format = "jpeg";
      image = true;
    } else if (ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a")) {
      mime = "image/gif";
      format = "gif";
      image = true;
    } else if (ascii(bytes, 0, "BM")) {
      mime = "image/bmp";
      format = "bmp";
      image = true;
    } else if (ascii(bytes, 0, "%PDF-")) {
      mime = "application/pdf";
      format = "pdf";
    } else if (starts(bytes, 0, new byte[] {80, 75, 3, 4})
        || starts(bytes, 0, new byte[] {80, 75, 5, 6})
        || starts(bytes, 0, new byte[] {80, 75, 7, 8})) {
      mime = "application/zip";
      format = "zip";
    } else if (ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) {
      mime = "image/webp";
      format = "webp";
      image = true;
      // https://developers.google.com/speed/webp/docs/riff_container
      if (ascii(bytes, 12, "VP8X") && bytes.length >= 30 && little(bytes, 16, 4) >= 10) {
        width = (int) little(bytes, 24, 3) + 1;
        height = (int) little(bytes, 27, 3) + 1;
        if ((long) width * height > 0xffffffffL) {
          width = null;
          height = null;
        }
      } else if (ascii(bytes, 12, "VP8 ")
          && bytes.length >= 30
          && starts(bytes, 23, new byte[] {(byte) 0x9d, 1, 0x2a})) {
        width = (int) little(bytes, 26, 2) & 0x3fff;
        height = (int) little(bytes, 28, 2) & 0x3fff;
      } else if (ascii(bytes, 12, "VP8L") && bytes.length >= 25 && (bytes[20] & 255) == 0x2f) {
        long bits = little(bytes, 21, 4);
        if ((bits >>> 29) == 0) {
          width = (int) (bits & 0x3fff) + 1;
          height = (int) ((bits >>> 14) & 0x3fff) + 1;
        }
      }
    }
    if (image && !"webp".equals(format)) {
      try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        if (readers.hasNext()) {
          var reader = readers.next();
          try {
            reader.setInput(input, true, true);
            width = reader.getWidth(0);
            height = reader.getHeight(0);
          } finally {
            reader.dispose();
          }
        }
      } catch (Exception ignored) {
        warnings.add(truncated ? "header_incomplete" : "invalid_image_header");
      }
    }
    if (width != null && (width <= 0 || height == null || height <= 0)) {
      width = null;
      height = null;
    }
    if (image && width == null && warnings.isEmpty())
      warnings.add(
          truncated ? "header_incomplete_or_unsupported" : "invalid_or_unsupported_image_header");
    String normalized =
        declared == null ? "" : declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    Boolean matches =
        mime == null || normalized.isBlank() || normalized.equals("application/octet-stream")
            ? null
            : mime.equals(normalized);
    if (Boolean.FALSE.equals(matches)) warnings.add("declared_mime_mismatch");
    return JsonUtils.obj(
        "format",
        format,
        "detected_content_type",
        mime,
        "width",
        width,
        "height",
        height,
        "declared_type_matches",
        matches,
        "header_truncated",
        truncated,
        "warnings",
        warnings,
        "scope",
        "header_only",
        "integrity_verified",
        false);
  }

  private static boolean ascii(byte[] data, int offset, String value) {
    return starts(data, offset, value.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static boolean starts(byte[] data, int offset, byte[] prefix) {
    if (offset < 0 || data.length - offset < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) if (data[offset + i] != prefix[i]) return false;
    return true;
  }

  private static long little(byte[] data, int offset, int length) {
    long value = 0;
    for (int i = 0; i < length; i++) value |= (long) (data[offset + i] & 255) << (8 * i);
    return value;
  }
}

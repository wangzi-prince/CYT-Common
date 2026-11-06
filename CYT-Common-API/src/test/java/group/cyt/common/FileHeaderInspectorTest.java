package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;

import group.cyt.common.module.adapter.nas.FileHeaderInspector;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FileHeaderInspectorTest {
  @Test
  void readsImageHeadersWithoutTrustingMimeAndDoesNotClaimIntegrity() throws Exception {
    for (String format : new String[] {"png", "jpeg", "gif"}) {
      var output = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(7, 3, BufferedImage.TYPE_INT_RGB), format, output);
      var info = FileHeaderInspector.inspect(output.toByteArray(), "application/pdf", false);
      assertEquals("image/" + format, info.get("detected_content_type"));
      assertEquals(7, info.get("width"));
      assertEquals(3, info.get("height"));
      assertEquals(false, info.get("declared_type_matches"));
      assertEquals(false, info.get("integrity_verified"));
    }
  }

  @Test
  void recognizesWebpCanvasAndReturnsUnknownInsteadOfInventingDimensions() {
    byte[] webp = new byte[30];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
    System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 8);
    webp[16] = 10;
    webp[24] = 6;
    webp[27] = 2;
    var info = FileHeaderInspector.inspect(webp, "image/webp", true);
    assertEquals(7, info.get("width"));
    assertEquals(3, info.get("height"));
    assertNull(
        FileHeaderInspector.inspect(new byte[] {1, 2, 3}, "image/png", false)
            .get("detected_content_type"));
    assertNull(
        FileHeaderInspector.inspect(
                new byte[] {(byte) 255, (byte) 216, (byte) 255}, "image/jpeg", true)
            .get("width"));
  }
}

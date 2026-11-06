package group.cyt.common.module.registry.controller;

import group.cyt.common.core.util.JsonUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class IntegrationGuide {
  private IntegrationGuide() {}

  static String render(Object spec) throws IOException {
    try (var input =
        IntegrationGuide.class.getResourceAsStream("/templates/integration-guide.html")) {
      if (input == null) throw new IOException("Integration guide resource missing");
      String json =
          JsonUtils.write(spec)
              .replace("&", "\\u0026")
              .replace("<", "\\u003c")
              .replace(">", "\\u003e");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8)
          .replace("__CYT_OPENAPI__", json);
    }
  }
}

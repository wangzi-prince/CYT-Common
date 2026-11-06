package group.cyt.common.module.registry.controller;

import static org.junit.jupiter.api.Assertions.*;

import group.cyt.common.core.util.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntegrationGuideTest {
  @Test
  void embeddedCatalogCannotCloseTheDataScriptOrInjectMarkup() throws Exception {
    String summary = "</script><script>alert('catalog')</script> & 模板";
    var spec = Map.of("info", Map.of("title", summary), "paths", Map.of());
    String html = IntegrationGuide.render(spec);
    assertFalse(html.contains(summary));
    assertFalse(html.contains("__CYT_OPENAPI__"));
    String open = "<script id=\"openapi-data\" type=\"application/json\">";
    int start = html.indexOf(open) + open.length();
    String embedded = html.substring(start, html.indexOf("</script>", start));
    assertEquals(summary, JsonUtils.MAPPER.readTree(embedded).path("info").path("title").asText());
    assertTrue(html.contains("textContent"));
    assertFalse(html.contains("innerHTML"));
  }
}

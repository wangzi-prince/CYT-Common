package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class OperationContractTest {
  static Stream<Map<String, Object>> cases() throws Exception {
    List<Map<String, Object>> cases = new ArrayList<>();
    for (String resource :
        List.of("/operation-cases.json", "/business-cases.json", "/discord-cases.json"))
      try (var input = OperationContractTest.class.getResourceAsStream(resource)) {
        cases.addAll(
            JsonUtils.MAPPER.<List<Map<String, Object>>>readValue(
                input, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
      }
    return cases.stream();
  }

  @ParameterizedTest(name = "{index}: Python parameter contract")
  @MethodSource("cases")
  void matchesOriginalPythonNormalization(Map<String, Object> item) throws Exception {
    var registry = new OperationRegistry(mock(GatewayStore.class));
    String key = JsonUtils.str(item, "operation");
    var op = registry.get(key);
    var normalized = registry.validate(op, JsonUtils.map(item.get("params")));
    assertEquals(
        JsonUtils.write(JsonUtils.sorted(item.get("normalized"))),
        JsonUtils.write(JsonUtils.sorted(normalized)),
        key);
  }

  @Test
  void capabilityReviewCoversBuiltinsAndOnlyRecommendsRegisteredOperations() throws Exception {
    var registry = new OperationRegistry(mock(GatewayStore.class));
    try (var input = getClass().getResourceAsStream("/capability-review.json")) {
      var review = JsonUtils.MAPPER.readTree(input);
      Set<String> keys = new HashSet<>();
      Set<String> depths = Set.of("deep", "enhanced", "basic", "supporting", "test_only");
      Map<String, Integer> counts = new HashMap<>();
      Map<String, String> byKey = new HashMap<>();
      for (var item : review.path("items")) {
        assertTrue(keys.add(item.path("key").asText()), "duplicate review");
        registry.get(item.path("key").asText());
        assertFalse(item.path("current").asText().isBlank());
        assertFalse(item.path("proposal").asText().isBlank());
        String depth = item.path("depth").asText();
        assertTrue(depths.contains(depth), item.path("key").asText());
        counts.merge(depth, 1, Integer::sum);
        byKey.put(item.path("key").asText(), depth);
        assertFalse(item.path("depth_reason").asText().isBlank());
        assertFalse(item.path("remaining_gap").asText().isBlank());
        assertFalse(item.path("evidence").isEmpty());
        for (var source : item.path("evidence")) {
          assertTrue(source.path("path").asText().startsWith("src/main/java/"));
          assertTrue(source.path("line").asInt() > 0);
          assertFalse(source.path("anchor").asText().isBlank());
        }
        for (var recommended : item.path("recommended_operations"))
          registry.get(recommended.asText());
      }
      assertEquals(registry.contract().path("operations").size(), keys.size());
      var summary = review.path("depth_summary");
      assertEquals(keys.size(), summary.path("total").asInt());
      for (String depth : depths)
        assertEquals(
            counts.getOrDefault(depth, 0).intValue(), summary.path("counts").path(depth).asInt());
      assertFalse(summary.path("all_deep").asBoolean(true));
      // A business tag, a recommended replacement, and auto-pagination are different from depth.
      assertEquals("deep", byKey.get("wecom.approvals.submit"));
      assertEquals("basic", byKey.get("wecom.approval_templates.list"));
      assertEquals("enhanced", byKey.get("shopify.orders.collect"));
      assertEquals("enhanced", byKey.get("nas.objects.read_head"));
      assertEquals("supporting", byKey.get("wecom.auth.access_token"));
      assertEquals(4, summary.path("auxiliary_interfaces").size());
    }
  }

  @Test
  void contractAndParameterFixturesComeFromTheSameExport() throws Exception {
    var registry = new OperationRegistry(mock(GatewayStore.class));
    int operations = registry.contract().path("operations").size();
    Set<String> platforms = new HashSet<>();
    registry
        .contract()
        .path("operations")
        .forEach(node -> platforms.add(node.path("platform").asText()));
    Set<String> declared = new HashSet<>();
    registry
        .contract()
        .path("platforms")
        .forEach(node -> declared.add(node.path("platform").asText()));

    assertTrue(operations > 0, "contract.json 里没有操作，导出可能失败");
    assertEquals(operations, cases().count(), "操作目录与参数夹具条数不一致：只重导了其中一个");
    assertEquals(platforms, declared, "操作引用的平台未在平台清单中声明");
  }
}

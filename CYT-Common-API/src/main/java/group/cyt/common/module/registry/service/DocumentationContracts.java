package group.cyt.common.module.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.util.*;
import org.springframework.stereotype.Service;

/** Documentation metadata is additive; execution contracts and existing URLs stay unchanged. */
@Service
public class DocumentationContracts {
  private final GatewayStore store;
  private final JsonNode contracts;
  private final Set<String> builtins = new HashSet<>();

  public DocumentationContracts(GatewayStore store) throws Exception {
    this.store = store;
    try (var input = getClass().getResourceAsStream("/response-contracts.json")) {
      contracts = JsonUtils.MAPPER.readTree(input);
    }
    try (var input = getClass().getResourceAsStream("/discord-response-contracts.json")) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) contracts)
          .setAll(
              (com.fasterxml.jackson.databind.node.ObjectNode) JsonUtils.MAPPER.readTree(input));
    }
    for (String resource :
        List.of("/contract.json", "/business-contract.json", "/discord-contract.json")) {
      try (var input = getClass().getResourceAsStream(resource)) {
        for (var op : JsonUtils.MAPPER.readTree(input).path("operations"))
          builtins.add(op.path("key").asText());
      }
    }
  }

  private Map<String, Object> custom(OperationDefinition op) {
    if (builtins.contains(op.key())) return null;
    return store.one(
        "SELECT a.response_schema_json,m.service_kind FROM gateway_custom_api a LEFT JOIN gateway_module m ON a.module_key=m.`key` WHERE a.`key`=?",
        op.key());
  }

  public String direction(OperationDefinition op) {
    var row = custom(op);
    return row != null && "internal".equals(row.get("service_kind")) ? "internal" : "third_party";
  }

  public List<OperationDefinition> filter(List<OperationDefinition> operations, String direction) {
    if (!Set.of("all", "internal", "third_party").contains(direction))
      throw GatewayException.invalid("文档方向必须为 third_party、internal 或 all");
    if (direction.equals("all")) return operations;
    var internal = new HashSet<String>();
    for (var row :
        store.rows(
            "SELECT a.`key` FROM gateway_custom_api a JOIN gateway_module m ON a.module_key=m.`key` WHERE m.service_kind='internal'"))
      internal.add(JsonUtils.str(row, "key"));
    return operations.stream()
        .filter(op -> internal.contains(op.key()) == direction.equals("internal"))
        .toList();
  }

  public Object response(OperationDefinition op) {
    var row = custom(op);
    if (row != null) {
      var business =
          JsonUtils.map(JsonUtils.parse(JsonUtils.str(row, "response_schema_json", "{}")));
      if (business.isEmpty()) business.put("description", "业务响应字段尚未登记，请由提供方补齐响应契约；不会根据模拟数据推测。");
      var wrapper =
          JsonUtils.obj(
              "type",
              "object",
              "description",
              "配置型 HTTP 接口的数据包装；业务结果位于 data.data。",
              "properties",
              JsonUtils.obj(
                  "status_code",
                  field("integer", "提供方 HTTP 响应状态码"),
                  "data",
                  business,
                  "mock",
                  field("boolean", "是否为模拟提供方结果")));
      if (business.containsKey("$defs")) wrapper.put("$defs", business.remove("$defs"));
      return wrapper;
    }
    var declared = contracts.path(op.key());
    if (!declared.isMissingNode()) return JsonUtils.parse(declared.toString());
    if (op.key().endsWith(".collect") && !op.platform().equals("wecom"))
      return JsonUtils.parse(contracts.path("$collection").toString());
    return JsonUtils.obj("description", "此接口尚未登记业务响应字段契约。下方仅展示网关协议；业务字段需按实际实现补齐，不能将示例当成保证。");
  }

  public Map<String, Object> describe(OperationDefinition op) {
    var result = JsonUtils.map(JsonUtils.parse(JsonUtils.write(op)));
    result.put("integration_kind", direction(op));
    result.put("response_schema", response(op));
    return result;
  }

  public Map<String, Object> envelope(OperationDefinition op) {
    var data = JsonUtils.map(response(op));
    var envelope =
        JsonUtils.obj(
            "type",
            "object",
            "properties",
            JsonUtils.obj(
                "ok",
                field("boolean", "网关是否执行成功；失败时检查 error"),
                "data",
                data,
                "meta",
                JsonUtils.obj(
                    "type",
                    "object",
                    "description",
                    "本次网关执行的追踪信息",
                    "properties",
                    JsonUtils.obj(
                        "trace_id",
                        field("string", "请求追踪标识，用于定位调用日志"),
                        "latency_ms",
                        field("integer", "网关执行耗时，单位毫秒"),
                        "platform",
                        field("string", "实际服务或提供方标识"),
                        "mock",
                        field("boolean", "true 表示模拟调用，不代表真实业务已执行"),
                        "idempotent_replay",
                        field("boolean", "是否复用同一幂等请求的已完成结果")))));
    if (data.containsKey("$defs")) envelope.put("$defs", data.remove("$defs"));
    return envelope;
  }

  private static Object field(String type, String description) {
    return JsonUtils.obj("type", type, "description", description);
  }
}

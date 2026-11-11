package group.cyt.common.module.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** The versioned snapshot contains API metadata only. Java executes all runtime behavior. */
@Service
public class OperationRegistry {
  private final Map<String, OperationDefinition> builtins = new LinkedHashMap<>();
  private final Map<String, String> aliases = new HashMap<>();
  private final Map<String, JsonSchema> validators = new ConcurrentHashMap<>();
  private final GatewayStore store;
  private final JsonNode contract;

  public OperationRegistry(GatewayStore store) throws Exception {
    this.store = store;
    try (var input = getClass().getResourceAsStream("/contract.json")) {
      contract = JsonUtils.MAPPER.readTree(input);
    }
    // Business capabilities are maintained independently of the legacy Python export.
    try (var input = getClass().getResourceAsStream("/business-contract.json")) {
      if (input != null) {
        var business = JsonUtils.MAPPER.readTree(input);
        // Add optional native fields without changing legacy examples/defaults or the Python
        // export.
        for (var extension : business.path("native_extensions")) {
          var target =
              java.util.stream.StreamSupport.stream(
                      contract.path("operations").spliterator(), false)
                  .filter(n -> n.path("key").asText().equals(extension.path("key").asText()))
                  .findFirst()
                  .orElseThrow(() -> new IllegalStateException("Native extension target missing"));
          var schema = (ObjectNode) target.path("schema");
          if (extension.has("properties"))
            ((ObjectNode) schema.path("properties"))
                .setAll((ObjectNode) extension.path("properties"));
          if (extension.has("definitions"))
            ((ObjectNode) schema.withObject("/$defs"))
                .setAll((ObjectNode) extension.path("definitions"));
        }
        ((ArrayNode) contract.path("operations")).addAll((ArrayNode) business.path("operations"));
        for (var credential : business.path("credentials")) {
          var existing =
              java.util.stream.StreamSupport.stream(
                      contract.path("credentials").spliterator(), false)
                  .filter(
                      c -> c.path("platform").asText().equals(credential.path("platform").asText()))
                  .findFirst();
          if (existing.isPresent())
            ((ArrayNode) existing.get().path("vars")).addAll((ArrayNode) credential.path("vars"));
        }
      }
    }
    try (var input = getClass().getResourceAsStream("/discord-contract.json")) {
      var discord = JsonUtils.MAPPER.readTree(input);
      for (String section : List.of("operations", "platforms", "credentials"))
        ((ArrayNode) contract.path(section)).addAll((ArrayNode) discord.path(section));
    }
    for (JsonNode n : contract.path("operations")) {
      var op = JsonUtils.MAPPER.treeToValue(n, OperationDefinition.class);
      if (builtins.putIfAbsent(op.key(), op) != null)
        throw new IllegalStateException("Duplicate operation " + op.key());
    }
    contract
        .path("aliases")
        .fields()
        .forEachRemaining(e -> aliases.put(e.getKey(), e.getValue().asText()));
  }

  public JsonNode contract() {
    return contract;
  }

  public String canonical(String key) {
    if (aliases.containsKey(key)) return aliases.get(key);
    return key.split("\\.").length == 2 ? key.replaceFirst("\\.", ".actions.") : key;
  }

  public Set<String> acceptedNames(String key) {
    Set<String> names = new HashSet<>();
    names.add(key);
    aliases.forEach(
        (old, current) -> {
          if (current.equals(key)) names.add(old);
        });
    return names;
  }

  public static String path(String key) {
    return "/integrations/" + key.replace('.', '/').replace('_', '-');
  }

  public static boolean matches(String pattern, String key) {
    if (pattern.equals("*")) return true;
    if (pattern.endsWith(".*")) {
      String prefix = pattern.substring(0, pattern.length() - 2);
      return key.equals(prefix) || key.startsWith(prefix + ".");
    }
    return pattern.equals(key);
  }

  public List<OperationDefinition> operations() {
    List<OperationDefinition> all = new ArrayList<>(builtins.values());
    for (var row :
        store.rows(
            "SELECT a.* FROM gateway_custom_api a LEFT JOIN gateway_module m ON a.module_key=m.`key` WHERE a.enabled=true AND (a.module_key='' OR m.enabled=true)"))
      all.add(custom(row));
    var overrides = store.rows("SELECT * FROM gateway_op_order");
    return all.stream()
        .map(
            op -> {
              var r =
                  overrides.stream()
                      .filter(x -> op.key().equals(x.get("operation_key")))
                      .findFirst()
                      .orElse(null);
              if (r == null) return op;
              return new OperationDefinition(
                  op.key(),
                  op.platform(),
                  op.summary(),
                  op.description(),
                  op.quota(),
                  op.sensitive(),
                  op.idempotent(),
                  op.timeoutSeconds(),
                  op.retryMax(),
                  op.since(),
                  op.deprecated(),
                  op.tags(),
                  JsonUtils.str(r, "category", op.category()),
                  JsonUtils.integer(r.get("sort_order"), op.sortOrder()),
                  op.path(),
                  op.schema());
            })
        .sorted(
            Comparator.comparing(OperationDefinition::platform)
                .thenComparing(OperationDefinition::sortOrder)
                .thenComparing(OperationDefinition::key))
        .toList();
  }

  public OperationDefinition get(String key) {
    String current = canonical(key);
    if (builtins.containsKey(current)) return builtins.get(current);
    var row =
        store.one(
            "SELECT a.* FROM gateway_custom_api a LEFT JOIN gateway_module m ON a.module_key=m.`key` WHERE a.`key`=? AND a.enabled=true AND (a.module_key='' OR m.enabled=true)",
            current);
    if (row == null)
      throw new GatewayException(404, "operation_not_found", "未注册的操作: " + key, false);
    return custom(row);
  }

  public boolean builtin(String key) {
    return builtins.containsKey(canonical(key));
  }

  public Map<String, Object> validate(OperationDefinition op, Map<String, Object> params) {
    ObjectNode input =
        JsonUtils.tree(ParameterRules.normalize(op.key(), params == null ? Map.of() : params))
            .deepCopy();
    defaults(input, op.schema(), op.schema());
    String cacheKey = op.key() + ":" + JsonUtils.sha(op.schema().toString());
    JsonSchema validator =
        validators.computeIfAbsent(
            cacheKey,
            k ->
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(op.schema()));
    var errors = validator.validate(input);
    if (!errors.isEmpty())
      throw GatewayException.invalid("入参校验失败")
          .detail(errors.stream().map(e -> JsonUtils.obj("message", e.getMessage())).toList());
    Map<String, Object> validated =
        JsonUtils.MAPPER.convertValue(
            input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    ParameterRules.validate(op.key(), validated);
    return validated;
  }

  private void defaults(JsonNode value, JsonNode schema, JsonNode root) {
    if (schema.has("$ref")) schema = root.at(schema.get("$ref").asText().substring(1));
    if (value instanceof ObjectNode object) {
      var fields = schema.path("properties").fields();
      while (fields.hasNext()) {
        var f = fields.next();
        if (!object.has(f.getKey()) && f.getValue().has("default"))
          object.set(f.getKey(), f.getValue().get("default").deepCopy());
        if (object.has(f.getKey())) defaults(object.get(f.getKey()), f.getValue(), root);
      }
    } else if (value.isArray())
      for (JsonNode item : value) defaults(item, schema.path("items"), root);
    for (JsonNode branch : schema.path("anyOf"))
      if (branch.path("type").asText().equals("object")
          || branch.path("type").asText().equals("array")
          || branch.has("$ref")) defaults(value, branch, root);
  }

  public OperationDefinition custom(Map<String, Object> row) {
    String key = JsonUtils.str(row, "key");
    ObjectNode schema = JsonUtils.MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    ObjectNode props = schema.putObject("properties");
    ArrayNode required = schema.putArray("required");
    for (Object v :
        JsonUtils.list(JsonUtils.parse(JsonUtils.str(row, "params_schema_json", "[]")))) {
      var p = JsonUtils.map(v);
      String name = JsonUtils.str(p, "name");
      if (name.isBlank()) continue;
      ObjectNode field = JsonUtils.MAPPER.createObjectNode();
      String type = JsonUtils.str(p, "type", "string");
      field.put(
          "type",
          type.equals("int")
              ? "integer"
              : type.equals("float") ? "number" : type.equals("bool") ? "boolean" : type);
      if (p.containsKey("default") && p.get("default") != null)
        field.set("default", JsonUtils.tree(p.get("default")));
      field.put("description", JsonUtils.str(p, "description"));
      props.set(name, field);
      if (JsonUtils.bool(p.get("required"))) required.add(name);
    }
    return new OperationDefinition(
        key,
        key.split("\\.")[0],
        JsonUtils.str(row, "name"),
        JsonUtils.str(row, "description"),
        "60/min",
        JsonUtils.bool(row.get("sensitive")),
        false,
        ((Number) row.getOrDefault("timeout_s", 30)).doubleValue(),
        JsonUtils.integer(row.get("retry_max"), 1),
        "2026-10",
        false,
        JsonUtils.str(row, "method", "GET").equals("GET") ? List.of("read") : List.of(),
        JsonUtils.str(row, "category"),
        JsonUtils.integer(row.get("sort_order"), 0),
        path(key),
        schema);
  }
}

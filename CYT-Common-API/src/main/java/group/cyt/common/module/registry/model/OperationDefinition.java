package group.cyt.common.module.registry.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OperationDefinition(
    String key,
    String platform,
    String summary,
    String description,
    String quota,
    boolean sensitive,
    boolean idempotent,
    @JsonProperty("timeout_s") double timeoutSeconds,
    @JsonProperty("retry_max") int retryMax,
    String since,
    boolean deprecated,
    List<String> tags,
    String category,
    @JsonProperty("sort_order") int sortOrder,
    String path,
    JsonNode schema) {
  public boolean readOnly() {
    return tags != null && tags.contains("read");
  }
}

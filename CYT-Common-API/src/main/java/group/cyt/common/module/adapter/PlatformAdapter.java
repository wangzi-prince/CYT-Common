package group.cyt.common.module.adapter;

import group.cyt.common.module.registry.model.OperationDefinition;
import java.util.Map;

public interface PlatformAdapter {
  boolean supports(String platform);

  Object invoke(OperationDefinition operation, Map<String, Object> params, String system);
}

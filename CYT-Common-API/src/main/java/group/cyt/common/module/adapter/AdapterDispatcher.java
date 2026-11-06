package group.cyt.common.module.adapter;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.module.adapter.support.BusinessWorkflows;
import group.cyt.common.module.adapter.wecom.approval.WecomApprovalService;
import group.cyt.common.module.registry.model.OperationDefinition;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdapterDispatcher {
  private final List<PlatformAdapter> adapters;
  private final MockAdapter mock;
  private final GatewaySettings settings;
  private final OperationRegistry registry;
  private final WecomApprovalService approvals;
  private final BusinessWorkflows workflows;

  public AdapterDispatcher(
      List<PlatformAdapter> adapters,
      MockAdapter mock,
      GatewaySettings settings,
      OperationRegistry registry,
      WecomApprovalService approvals,
      BusinessWorkflows workflows) {
    this.adapters = adapters;
    this.mock = mock;
    this.settings = settings;
    this.registry = registry;
    this.approvals = approvals;
    this.workflows = workflows;
  }

  public boolean mock(OperationDefinition op) {
    return registry.builtin(op.key())
        && (settings.mock()
            || settings.flag(op.platform().toUpperCase(Locale.ROOT) + "_MOCK", false));
  }

  public String environment() {
    return settings.current();
  }

  public Object invoke(OperationDefinition op, Map<String, Object> params, String system) {
    if (approvals.supports(op.key())) return approvals.invoke(op, params, system, mock(op));
    if (workflows.supports(op.key())) return workflows.invoke(op, params, system, mock(op));
    if (mock(op)) return mock.invoke(op, params, system);
    return adapters.stream()
        .filter(
            a -> a.supports(op.platform()) || a.supports("custom") && !registry.builtin(op.key()))
        .findFirst()
        .orElseThrow(() -> GatewayException.config("平台适配器未加载: " + op.platform()))
        .invoke(op, params, system);
  }
}

package group.cyt.common.module.gateway.controller;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.auth.service.AuthenticationService;
import group.cyt.common.module.gateway.service.GatewayExecutor;
import group.cyt.common.module.registry.service.OperationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class IntegrationController {
  private final AuthenticationService authentication;
  private final GatewayExecutor executor;
  private final OperationRegistry registry;

  public IntegrationController(
      AuthenticationService authentication, GatewayExecutor executor, OperationRegistry registry) {
    this.authentication = authentication;
    this.executor = executor;
    this.registry = registry;
  }

  @PostMapping("/integrations/{platform}/{resource}/{action}")
  public Object invoke(
      @PathVariable String platform,
      @PathVariable String resource,
      @PathVariable String action,
      @RequestBody Map<String, Object> params,
      HttpServletRequest request) {
    String key =
        platform.replace('-', '_')
            + "."
            + resource.replace('-', '_')
            + "."
            + action.replace('-', '_');
    return executor.execute(
        authentication.system(request),
        key,
        params,
        request.getHeader("Idempotency-Key"),
        request.getHeader("X-Trace-Id"));
  }

  @PostMapping("/integrations/batch")
  public Object batch(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    if (!body.keySet().stream().allMatch(Set.of("calls", "stop_on_error")::contains))
      throw GatewayException.invalid("批量请求包含未知字段");
    return executor.batch(
        authentication.system(request),
        JsonUtils.list(body.get("calls")).stream().map(JsonUtils::map).toList(),
        JsonUtils.bool(body.get("stop_on_error")));
  }

  @PostMapping("/admin/api/simulate")
  public Object simulate(@RequestBody Map<String, Object> body) {
    return executor.execute(
        JsonUtils.str(body, "system"),
        JsonUtils.str(body, "operation"),
        JsonUtils.map(body.get("params")),
        JsonUtils.str(body, "idempotency_key"),
        null);
  }
}

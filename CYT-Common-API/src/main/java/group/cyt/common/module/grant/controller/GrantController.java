package group.cyt.common.module.grant.controller;

import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.auth.service.AuthenticationService;
import group.cyt.common.module.grant.service.AuthorizationService;
import group.cyt.common.module.grant.service.GrantBatchService;
import group.cyt.common.module.registry.service.OperationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api")
public class GrantController {
  private final AuthorizationService service;
  private final GatewayStore store;
  private final OperationRegistry registry;
  private final GrantBatchService batches;

  public GrantController(
      AuthorizationService service,
      GatewayStore store,
      OperationRegistry registry,
      GrantBatchService batches) {
    this.service = service;
    this.store = store;
    this.registry = registry;
    this.batches = batches;
  }

  @PostMapping("/grants/batch/preview")
  public Object previewBatch(@RequestBody Map<String, Object> body) {
    return batches.preview(body);
  }

  @PostMapping("/grants/batch")
  public Object applyBatch(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    return batches.apply(body, AuthenticationService.actor(req));
  }

  @GetMapping("/grants")
  public Object list() {
    return JsonUtils.obj(
        "systems",
        store.rows("SELECT `key`,display_name,enabled,description FROM gateway_system ORDER BY id"),
        "operations",
        registry.operations(),
        "grants",
        store.rows("SELECT * FROM gateway_grant ORDER BY `system`,operation_pattern"));
  }

  @PostMapping("/grants")
  public Object upsert(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    return service.upsert(body, AuthenticationService.actor(req));
  }

  @PostMapping("/grants/toggle")
  public Object toggle(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    return service.toggle(
        JsonUtils.str(body, "system"),
        JsonUtils.str(body, "operation"),
        JsonUtils.bool(body.getOrDefault("allowed", body.get("enabled"))),
        JsonUtils.bool(body.get("confirm_sensitive")),
        AuthenticationService.actor(req));
  }

  @DeleteMapping("/grants/{id}")
  public Object delete(@PathVariable long id, HttpServletRequest req) {
    service.delete(id, AuthenticationService.actor(req));
    return JsonUtils.obj("ok", true);
  }

  @GetMapping("/effective/{system}")
  public Object effective(@PathVariable String system) {
    return service.effective(system);
  }

  @GetMapping("/access-matrix")
  public Object matrix() {
    return service.matrix();
  }

  @GetMapping("/grants/audit")
  public Object audit() {
    return JsonUtils.obj(
        "items", store.rows("SELECT * FROM gateway_grant_audit ORDER BY id DESC LIMIT 200"));
  }

  @PostMapping("/grants/bulk")
  public Object bulk(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    String system = JsonUtils.str(body, "system"), platform = JsonUtils.str(body, "platform");
    boolean enabled = JsonUtils.bool(body.getOrDefault("allowed", body.get("enabled")));
    List<String> changed = new ArrayList<>(), skipped = new ArrayList<>();
    return store.atomic(
        () -> {
          for (var op : registry.operations())
            if (op.platform().equals(platform)) {
              if (op.sensitive()) {
                skipped.add(op.key());
                continue;
              }
              service.toggle(system, op.key(), enabled, false, AuthenticationService.actor(req));
              changed.add(op.key());
            }
          return JsonUtils.obj("ok", true, "changed", changed.size(), "skipped_sensitive", skipped);
        });
  }
}

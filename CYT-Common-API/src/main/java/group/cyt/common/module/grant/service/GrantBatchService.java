package group.cyt.common.module.grant.service;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.registry.model.OperationDefinition;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit selections only: a batch never grants future operations through a wildcard. */
@Service
public class GrantBatchService {
  private final GatewayStore store;
  private final OperationRegistry registry;
  private final AuthorizationService authorization;

  public GrantBatchService(
      GatewayStore store, OperationRegistry registry, AuthorizationService authorization) {
    this.store = store;
    this.registry = registry;
    this.authorization = authorization;
  }

  @Transactional
  public Map<String, Object> preview(Map<String, Object> body) {
    String system = JsonUtils.str(body, "system");
    if (store.one("SELECT id FROM gateway_system WHERE `key`=? FOR UPDATE", system) == null)
      throw GatewayException.invalid("系统不存在");
    String effect = JsonUtils.str(body, "effect");
    if (!Set.of("allow", "deny").contains(effect))
      throw GatewayException.invalid("批量策略必须为 allow 或 deny");
    var requested = JsonUtils.list(body.get("operations"));
    if (requested.isEmpty() || requested.size() > 500)
      throw GatewayException.invalid("每批请选择 1–500 个操作");
    var selected = new TreeMap<String, OperationDefinition>();
    for (Object name : requested) {
      if (!(name instanceof String key)) throw GatewayException.invalid("操作必须为字符串标识");
      var op = registry.get(key);
      selected.put(op.key(), op);
    }
    var grants = store.rows("SELECT * FROM gateway_grant WHERE `system`=? ORDER BY id", system);
    var items = new ArrayList<Map<String, Object>>();
    boolean target = effect.equals("allow");
    for (var op : selected.values()) {
      var before = authorization.decision(op, grants);
      var direct =
          grants.stream().filter(g -> op.key().equals(g.get("operation_pattern"))).findFirst();
      var replacement = direct.map(LinkedHashMap::new).orElseGet(LinkedHashMap::new);
      replacement.putAll(JsonUtils.obj("operation_pattern", op.key(), "effect", effect));
      var simulated =
          new ArrayList<>(
              grants.stream().filter(g -> !op.key().equals(g.get("operation_pattern"))).toList());
      simulated.add(replacement);
      var after = authorization.decision(op, simulated);
      boolean noop = JsonUtils.bool(before.get("allowed")) == target;
      boolean blocked = !noop && JsonUtils.bool(after.get("allowed")) != target;
      var blockers =
          blocked
              ? JsonUtils.list(after.get("rules")).stream()
                  .filter(r -> JsonUtils.map(r).get("id") != null)
                  .filter(r -> !target || "deny".equals(JsonUtils.map(r).get("effect")))
                  .toList()
              : List.of();
      String reason =
          noop
              ? "当前已是目标状态，无需修改"
              : blocked ? "保留现有期限和其他规则后仍无法生效；请先处理冲突或过期规则" : "将保存精确规则，保留原配额、期限和备注";
      if (blocked && replacement.get("expires_at") != null && blockers.isEmpty())
        blockers =
            List.of(
                JsonUtils.obj(
                    "id", replacement.get("id"), "operation_pattern", op.key(), "effect", effect));
      items.add(
          JsonUtils.obj(
              "operation",
              op.key(),
              "summary",
              op.summary(),
              "sensitive",
              op.sensitive(),
              "before",
              before.get("allowed"),
              "after",
              noop ? before.get("allowed") : after.get("allowed"),
              "noop",
              noop,
              "blocked",
              blocked,
              "reason",
              reason,
              "blocking_rules",
              blockers,
              "quota",
              replacement.get("quota"),
              "expires_at",
              replacement.get("expires_at")));
    }
    long changed =
        items.stream()
            .filter(i -> !JsonUtils.bool(i.get("noop")) && !JsonUtils.bool(i.get("blocked")))
            .count();
    long sensitive =
        target
            ? items.stream()
                .filter(
                    i ->
                        !JsonUtils.bool(i.get("noop"))
                            && !JsonUtils.bool(i.get("blocked"))
                            && JsonUtils.bool(i.get("sensitive")))
                .count()
            : 0;
    String revision =
        JsonUtils.sha(
            JsonUtils.write(
                JsonUtils.sorted(
                    JsonUtils.obj(
                        "system", system, "effect", effect, "grants", grants, "items", items))));
    return JsonUtils.obj(
        "system",
        system,
        "effect",
        effect,
        "revision",
        revision,
        "items",
        items,
        "changed",
        changed,
        "sensitive_count",
        sensitive,
        "blocked_count",
        items.stream().filter(i -> JsonUtils.bool(i.get("blocked"))).count(),
        "noop_count",
        items.stream().filter(i -> JsonUtils.bool(i.get("noop"))).count());
  }

  @Transactional
  public Map<String, Object> apply(Map<String, Object> body, String actor) {
    // The system row lock is shared by all grant mutations. Recompute under this same transaction.
    var plan = preview(body);
    if (!Objects.equals(plan.get("revision"), body.get("revision")))
      throw new GatewayException(409, "conflict", "授权规则已变化，请重新预览后保存", false);
    if (((Number) plan.get("blocked_count")).longValue() > 0
        && !JsonUtils.bool(body.get("skip_blocked")))
      throw GatewayException.invalid("存在冲突，须先处理或明确选择跳过冲突项");
    if (((Number) plan.get("sensitive_count")).longValue() > 0
        && !JsonUtils.bool(body.get("confirm_sensitive")))
      throw GatewayException.invalid("批量授权包含敏感操作，需要统一确认");
    for (Object item : JsonUtils.list(plan.get("items"))) {
      var row = JsonUtils.map(item);
      if (JsonUtils.bool(row.get("noop")) || JsonUtils.bool(row.get("blocked"))) continue;
      authorization.toggle(
          JsonUtils.str(plan, "system"),
          JsonUtils.str(row, "operation"),
          JsonUtils.str(plan, "effect").equals("allow"),
          true,
          actor);
    }
    store.audit(
        actor,
        "grant_batch",
        JsonUtils.str(plan, "system"),
        JsonUtils.obj(
            "effect",
            plan.get("effect"),
            "changed",
            plan.get("changed"),
            "skipped",
            plan.get("blocked_count"),
            "revision",
            plan.get("revision")));
    return JsonUtils.obj(
        "ok", true, "changed", plan.get("changed"), "skipped", plan.get("blocked_count"));
  }
}

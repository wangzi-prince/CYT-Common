package group.cyt.common.module.grant.service;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.registry.model.OperationDefinition;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationService {
  private final GatewayStore store;
  private final OperationRegistry registry;

  public AuthorizationService(GatewayStore store, OperationRegistry registry) {
    this.store = store;
    this.registry = registry;
  }

  public String check(String system, OperationDefinition operation) {
    List<Map<String, Object>> grants;
    try {
      grants = store.rows("SELECT * FROM gateway_grant WHERE `system`=?", system);
    } catch (org.springframework.dao.DataAccessException e) {
      throw GatewayException.forbidden("授权策略暂不可用，已拒绝调用");
    }
    var decision = decision(operation, grants);
    if (!JsonUtils.bool(decision.get("allowed")))
      throw GatewayException.forbidden(JsonUtils.str(decision, "reason"));
    return JsonUtils.str(decision, "quota");
  }

  /** Same policy for runtime calls, the permission screen, and batch previews. */
  public Map<String, Object> decision(
      OperationDefinition operation, List<Map<String, Object>> grants) {
    Set<String> names = registry.acceptedNames(operation.key());
    var matched =
        grants.stream()
            .filter(
                g ->
                    g.get("expires_at") == null
                        || Instant.parse(g.get("expires_at").toString()).isAfter(Instant.now()))
            .filter(
                g ->
                    names.stream()
                        .anyMatch(
                            n ->
                                OperationRegistry.matches(
                                    JsonUtils.str(g, "operation_pattern"), n)))
            .toList();
    var denied = matched.stream().filter(g -> JsonUtils.str(g, "effect").equals("deny")).toList();
    var allowed =
        matched.stream()
            .filter(g -> JsonUtils.str(g, "effect").equals("allow"))
            .filter(
                g ->
                    !operation.sensitive() || names.contains(JsonUtils.str(g, "operation_pattern")))
            .max(
                Comparator.comparingInt(
                    g -> JsonUtils.str(g, "operation_pattern").replace("*", "").length()));
    boolean enabled = denied.isEmpty() && allowed.isPresent();
    String quota = allowed.map(g -> JsonUtils.str(g, "quota")).orElse("");
    return JsonUtils.obj(
        "operation",
        operation.key(),
        "allowed",
        enabled,
        "reason",
        !denied.isEmpty()
            ? "被拒绝规则阻止：" + denied.stream().map(g -> JsonUtils.str(g, "operation_pattern")).toList()
            : enabled ? "已授权" : operation.sensitive() ? "敏感操作需要精确授权" : "尚未授权或授权已过期",
        "quota",
        quota.isBlank() ? operation.quota() : quota,
        "rules",
        matched.stream()
            .map(
                g ->
                    JsonUtils.obj(
                        "id",
                        g.get("id"),
                        "operation_pattern",
                        g.get("operation_pattern"),
                        "effect",
                        g.get("effect")))
            .toList());
  }

  public boolean allowed(String system, OperationDefinition operation) {
    try {
      check(system, operation);
      return true;
    } catch (GatewayException e) {
      return false;
    }
  }

  public Object effective(String system) {
    var grants = store.rows("SELECT * FROM gateway_grant WHERE `system`=?", system);
    return JsonUtils.obj(
        "system",
        system,
        "operations",
        registry.operations().stream().map(o -> decision(o, grants)).toList());
  }

  public Object matrix() {
    var ops = registry.operations();
    var grants = store.rows("SELECT * FROM gateway_grant");
    var bySystem =
        grants.stream()
            .collect(java.util.stream.Collectors.groupingBy(g -> JsonUtils.str(g, "system")));
    return JsonUtils.obj(
        "operation_count",
        ops.size(),
        "systems",
        store
            .rows("SELECT `key`,display_name FROM gateway_system WHERE enabled=true ORDER BY id")
            .stream()
            .map(
                s ->
                    JsonUtils.obj(
                        "key",
                        s.get("key"),
                        "display_name",
                        s.get("display_name"),
                        "allowed",
                        ops.stream()
                            .filter(
                                o ->
                                    JsonUtils.bool(
                                        decision(
                                                o,
                                                bySystem.getOrDefault(
                                                    JsonUtils.str(s, "key"), List.of()))
                                            .get("allowed")))
                            .map(OperationDefinition::key)
                            .toList()))
            .toList());
  }

  @Transactional
  public Object upsert(Map<String, Object> body, String actor) {
    String system = JsonUtils.str(body, "system"),
        pattern = JsonUtils.str(body, "operation_pattern"),
        effect = JsonUtils.str(body, "effect", "allow");
    if (store.one("SELECT id FROM gateway_system WHERE `key`=? FOR UPDATE", system) == null)
      throw GatewayException.invalid("系统不存在");
    if (!Set.of("allow", "deny").contains(effect)
        || !pattern.matches("\\*|[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*(?:\\.\\*)?"))
      throw GatewayException.invalid("授权规则格式无效");
    if (!pattern.contains("*")) {
      var op = registry.get(pattern);
      if (op.sensitive()
          && effect.equals("allow")
          && !JsonUtils.bool(body.get("confirm_sensitive")))
        throw GatewayException.invalid("敏感操作需要 confirm_sensitive=true");
    }
    String quota = JsonUtils.str(body, "quota");
    if (!quota.isBlank()) QuotaService.parse(quota);
    var data =
        JsonUtils.obj(
            "system",
            system,
            "operation_pattern",
            pattern,
            "effect",
            effect,
            "quota",
            quota.isBlank() ? null : quota,
            "expires_at",
            GatewayStore.timestamp(body.get("expires_at")),
            "note",
            JsonUtils.str(body, "note"),
            "granted_by",
            actor);
    if (JsonUtils.str(body, "note").length() > 255 || pattern.length() > 128)
      throw GatewayException.invalid("授权字段过长");
    var old =
        store.one(
            "SELECT id FROM gateway_grant WHERE `system`=? AND operation_pattern=?",
            system,
            pattern);
    long id;
    if (old == null) id = store.insert("gateway_grant", data);
    else {
      id = ((Number) old.get("id")).longValue();
      store.edit("gateway_grant", id, data);
    }
    store.insert(
        "gateway_grant_audit",
        JsonUtils.obj(
            "actor",
            actor,
            "action",
            old == null ? "create" : "update",
            "system",
            system,
            "operation_pattern",
            pattern,
            "detail",
            JsonUtils.write(data)));
    store.audit(actor, "grant_upsert", system + ":" + pattern, JsonUtils.obj("effect", effect));
    return JsonUtils.obj("ok", true, "id", id, "action", old == null ? "create" : "update");
  }

  @Transactional
  public Object toggle(
      String system, String operation, boolean enabled, boolean confirm, String actor) {
    String key = registry.canonical(operation);
    if (store.one("SELECT id FROM gateway_system WHERE `key`=? FOR UPDATE", system) == null)
      throw GatewayException.invalid("系统不存在");
    var old =
        store.one(
            "SELECT * FROM gateway_grant WHERE `system`=? AND operation_pattern=?", system, key);
    var body = old == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(old);
    body.putAll(
        JsonUtils.obj(
            "system",
            system,
            "operation_pattern",
            key,
            "effect",
            enabled ? "allow" : "deny",
            "confirm_sensitive",
            confirm));
    return upsert(body, actor);
  }

  @Transactional
  public void delete(long id, String actor) {
    var row = store.one("SELECT * FROM gateway_grant WHERE id=?", id);
    if (row == null) throw GatewayException.missing("授权不存在");
    store.one("SELECT id FROM gateway_system WHERE `key`=? FOR UPDATE", row.get("system"));
    row = store.one("SELECT * FROM gateway_grant WHERE id=?", id);
    if (row == null) throw GatewayException.missing("授权不存在");
    store.update("DELETE FROM gateway_grant WHERE id=?", id);
    store.insert(
        "gateway_grant_audit",
        JsonUtils.obj(
            "actor",
            actor,
            "action",
            "delete",
            "system",
            row.get("system"),
            "operation_pattern",
            row.get("operation_pattern"),
            "detail",
            ""));
    store.audit(actor, "grant_delete", Long.toString(id), Map.of());
  }
}

package group.cyt.common.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.system.mapper.GatewaySystemMapper;
import group.cyt.common.module.system.model.GatewaySystem;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemService {
  private final GatewaySystemMapper mapper;
  private final GatewayStore store;

  public SystemService(GatewaySystemMapper mapper, GatewayStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public List<Map<String, Object>> list() {
    return mapper
        .selectList(new LambdaQueryWrapper<GatewaySystem>().orderByAsc(GatewaySystem::getId))
        .stream()
        .map(
            s -> {
              var keys =
                  store.one(
                      "SELECT COUNT(*) AS n, MAX(last_used_at) AS last_used FROM gateway_system_apikey WHERE `system`=? AND revoked=false",
                      s.getKey());
              return JsonUtils.obj(
                  "id",
                  s.getId(),
                  "key",
                  s.getKey(),
                  "display_name",
                  s.getDisplayName(),
                  "description",
                  s.getDescription(),
                  "enabled",
                  s.getEnabled(),
                  "created_at",
                  s.getCreatedAt().toInstant(ZoneOffset.UTC).toString(),
                  "grant_count",
                  store.count("SELECT COUNT(*) FROM gateway_grant WHERE `system`=?", s.getKey()),
                  "apikey_count",
                  keys.get("n"),
                  "last_key_used_at",
                  keys.get("last_used"));
            })
        .toList();
  }

  @Transactional
  public long create(String key, String displayName, String description, String actor) {
    var row = new GatewaySystem();
    row.setKey(key);
    row.setDisplayName(displayName);
    row.setDescription(description);
    row.setEnabled(true);
    row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
    mapper.insert(row);
    store.audit(actor, "system_create", key, Map.of());
    return row.getId();
  }

  @Transactional
  public boolean toggle(String key, String actor) {
    var row =
        mapper.selectOne(new LambdaQueryWrapper<GatewaySystem>().eq(GatewaySystem::getKey, key));
    if (row == null) throw GatewayException.missing("系统不存在");
    row.setEnabled(!row.getEnabled());
    mapper.updateById(row);
    store.audit(actor, "system_toggle", key, JsonUtils.obj("enabled", row.getEnabled()));
    return row.getEnabled();
  }

  public List<Map<String, Object>> keys(String system) {
    return store.rows(
        "SELECT id,`system`,name,prefix,revoked,created_at,last_used_at FROM gateway_system_apikey WHERE `system`=? ORDER BY id DESC",
        system);
  }

  @Transactional
  public Object createKey(String system, String name, String actor) {
    if (store.one("SELECT id FROM gateway_system WHERE `key`=?", system) == null)
      throw GatewayException.missing("系统不存在");
    String
        prefix =
            HexFormat.of()
                .formatHex(Base64.getUrlDecoder().decode(JsonUtils.random(6)))
                .substring(0, 8),
        secret = JsonUtils.random(32);
    long id =
        store.insert(
            "gateway_system_apikey",
            JsonUtils.obj(
                "system",
                system,
                "name",
                name,
                "prefix",
                prefix,
                "secret_hash",
                JsonUtils.sha(secret)));
    store.audit(actor, "apikey_create", system, JsonUtils.obj("id", id, "prefix", prefix));
    return JsonUtils.obj(
        "ok",
        true,
        "id",
        id,
        "key",
        "cyt_" + prefix + "_" + secret,
        "prefix",
        prefix,
        "api_key",
        "cyt_" + prefix + "_" + secret);
  }

  @Transactional
  public void revokeKey(long id, String actor) {
    if (store.update("UPDATE gateway_system_apikey SET revoked=true WHERE id=?", id) == 0)
      throw GatewayException.missing("API Key 不存在");
    store.audit(actor, "apikey_revoke", Long.toString(id), Map.of());
  }
}

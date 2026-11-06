package group.cyt.common.module.adapter.wecom.approval;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.callback.service.CallbackService;
import group.cyt.common.module.credential.service.CredentialService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

/** Durable, encrypted inbox. Incoming ACKs do not wait for a caller's webhook. */
@Service
@EnableScheduling
public class WecomApprovalEvents {
  private final GatewayStore store;
  private final WecomApprovalClient client;
  private final CredentialService credentials;
  private final GatewaySettings settings;
  private final CallbackService callbacks;

  public WecomApprovalEvents(
      GatewayStore store,
      WecomApprovalClient client,
      CredentialService credentials,
      GatewaySettings settings,
      CallbackService callbacks) {
    this.store = store;
    this.client = client;
    this.credentials = credentials;
    this.settings = settings;
    this.callbacks = callbacks;
  }

  public String decrypt(
      String system, String signature, String timestamp, String nonce, String encrypted) {
    var registered = store.one("SELECT enabled FROM gateway_system WHERE `key`=?", system);
    if (registered == null || !JsonUtils.bool(registered.get("enabled")))
      throw GatewayException.forbidden("回调接收系统不存在或已停用");
    return WecomEventCrypto.decrypt(
        client.credential("CALLBACK_TOKEN", system),
        client.credential("CALLBACK_AES_KEY", system),
        client.corp(system),
        signature,
        timestamp,
        nonce,
        encrypted);
  }

  public void receive(
      String sourceSystem, String signature, String timestamp, String nonce, byte[] envelope) {
    if (!credentials.writable()) throw GatewayException.config("接收审批回调前需要配置凭据加密主密钥");
    String encrypted = JsonUtils.str(WecomEventCrypto.xml(envelope), "Encrypt");
    String plain = decrypt(sourceSystem, signature, timestamp, nonce, encrypted);
    var xml = WecomEventCrypto.xml(plain.getBytes(StandardCharsets.UTF_8));
    String type = JsonUtils.str(xml, "Event");
    if (!Set.of("sys_approval_change", "open_approval_change").contains(type)) return;
    var info = JsonUtils.map(xml.get("ApprovalInfo"));
    boolean engine = type.equals("open_approval_change");
    String no =
        engine
            ? JsonUtils.str(info, "ThirdNo")
            : JsonUtils.str(info, "SpNoStr", JsonUtils.str(info, "SpNo"));
    if (no.isBlank()) throw GatewayException.invalid("审批回调缺少单号");
    int code = JsonUtils.integer(info.get(engine ? "OpenSpStatus" : "SpStatus"), 0);
    String eventId = JsonUtils.sha(client.profile(sourceSystem) + ":" + plain);
    var status = WecomApprovalService.status(code);
    var event =
        JsonUtils.obj(
            "event_id",
            eventId,
            "event_type",
            type,
            "approval_no",
            engine ? null : no,
            "third_no",
            engine ? no : null,
            "template_id",
            info.get(engine ? "OpenTemplateId" : "TemplateId"),
            "status_code",
            code,
            "status",
            status.get("status"),
            "status_label",
            status.get("label"),
            "occurred_at",
            xml.get("CreateTime"),
            "change_type",
            info.get("StatuChangeEvent"),
            "official",
            info);
    var owners =
        engine
            ? List.<Map<String, Object>>of()
            : store.rows(
                "SELECT DISTINCT `system` FROM gateway_wecom_submission WHERE env=? AND runtime='live' AND profile=? AND approval_no=?",
                settings.current(),
                client.profile(sourceSystem),
                no);
    Set<String> systems = new LinkedHashSet<>();
    owners.forEach(v -> systems.add(JsonUtils.str(v, "system")));
    if (systems.isEmpty()) systems.add(sourceSystem);
    store.atomic(
        () -> {
          for (String system : systems) {
            try {
              store.insert(
                  "gateway_wecom_event",
                  JsonUtils.obj(
                      "env",
                      settings.current(),
                      "system",
                      system,
                      "event_id",
                      eventId,
                      "approval_no",
                      no,
                      "payload_ciphertext",
                      credentials.encrypt(JsonUtils.write(event))));
            } catch (DuplicateKeyException ignored) {
              /* WeCom retries must not fan out twice. */
            }
          }
          return null;
        });
  }

  @Scheduled(fixedDelay = 5000, initialDelayString = "${WECOM_EVENT_INITIAL_DELAY_MS:5000}")
  public void deliverPending() {
    var rows =
        store.rows(
            "SELECT id,`system`,event_id,payload_ciphertext,attempts FROM gateway_wecom_event WHERE env=? AND delivery_state='pending' AND next_attempt_at<=UTC_TIMESTAMP(6) ORDER BY id LIMIT 20",
            settings.current());
    for (var row : rows) {
      long id = ((Number) row.get("id")).longValue();
      int attempts = JsonUtils.integer(row.get("attempts"), 0);
      // A lease recovers after a process exits during delivery; duplicates are identified by
      // event_id.
      int claimed =
          store.update(
              "UPDATE gateway_wecom_event SET next_attempt_at=?,attempts=attempts+1 WHERE id=? AND delivery_state='pending' AND next_attempt_at<=UTC_TIMESTAMP(6)",
              Timestamp.from(Instant.now().plusSeconds(120)),
              id);
      if (claimed == 0) continue;
      try {
        var result =
            JsonUtils.map(
                callbacks.deliver(
                    JsonUtils.str(row, "system"),
                    "wecom",
                    JsonUtils.read(credentials.decrypt(JsonUtils.str(row, "payload_ciphertext"))),
                    JsonUtils.str(row, "event_id").substring(0, 32)));
        if (JsonUtils.bool(result.get("delivered")))
          store.update(
              "UPDATE gateway_wecom_event SET delivery_state='delivered',last_error='' WHERE id=?",
              id);
        else {
          String reason = JsonUtils.str(result, "reason");
          boolean stop = reason.equals("no_config") || attempts >= 9;
          store.update(
              "UPDATE gateway_wecom_event SET delivery_state=?,last_error=?,next_attempt_at=? WHERE id=?",
              stop ? (reason.equals("no_config") ? "poll_only" : "failed") : "pending",
              reason,
              Timestamp.from(
                  Instant.now().plusSeconds(Math.min(3600, 10L << Math.min(attempts, 8)))),
              id);
        }
      } catch (Exception error) {
        store.update(
            "UPDATE gateway_wecom_event SET delivery_state=?,last_error='delivery_error',next_attempt_at=? WHERE id=?",
            attempts >= 9 ? "failed" : "pending",
            Timestamp.from(Instant.now().plusSeconds(60)),
            id);
      }
    }
  }
}

package group.cyt.common.module.callback.service;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.gateway.service.CallLogService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CallbackService {
  private final GatewayStore store;
  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final CallLogService logs;

  public CallbackService(
      GatewayStore store, CredentialService credentials, UpstreamHttp http, CallLogService logs) {
    this.store = store;
    this.credentials = credentials;
    this.http = http;
    this.logs = logs;
  }

  public Object deliver(String system, String platform, Object event, String trace) {
    if (system == null || system.isBlank())
      return JsonUtils.obj("delivered", false, "reason", "no_system", "http_status", null);
    var config =
        store.one(
            "SELECT * FROM gateway_callback WHERE `system`=? AND (platform=? OR platform='*') AND enabled=true ORDER BY CASE WHEN platform=? THEN 0 ELSE 1 END LIMIT 1",
            system,
            platform,
            platform);
    if (config == null)
      return JsonUtils.obj("delivered", false, "reason", "no_config", "http_status", null);
    return deliverConfig(config, platform, event, trace);
  }

  public Object test(long id, String actor) {
    var config = store.one("SELECT * FROM gateway_callback WHERE id=?", id);
    if (config == null) throw GatewayException.missing("回调配置不存在");
    if (!JsonUtils.bool(config.get("enabled"))) throw GatewayException.invalid("请先启用该回调配置");
    store.audit(actor, "callback_test", Long.toString(id), Map.of());
    return deliverConfig(
        config,
        JsonUtils.str(config, "platform"),
        JsonUtils.obj("event_type", "gateway.test", "message", "CYT Common 回调连通性测试"),
        UUID.randomUUID().toString().replace("-", ""));
  }

  private Object deliverConfig(
      Map<String, Object> config, String platform, Object event, String trace) {
    String system = JsonUtils.str(config, "system");
    byte[] body =
        JsonUtils.write(
                JsonUtils.obj(
                    "source",
                    "cyt-common-api",
                    "platform",
                    platform,
                    "system",
                    system,
                    "trace_id",
                    trace,
                    "event",
                    event))
            .getBytes(StandardCharsets.UTF_8);
    Map<String, String> headers =
        new LinkedHashMap<>(
            Map.of(
                "Content-Type",
                "application/json",
                "X-Cyt-Platform",
                platform,
                "X-Cyt-Trace-Id",
                trace));
    String ref = JsonUtils.str(config, "secret_ref");
    if (!ref.isBlank()) {
      String key = credentials.required(ref, system);
      headers.put(
          "X-Cyt-Signature",
          HexFormat.of().formatHex(JsonUtils.hmac(key.getBytes(StandardCharsets.UTF_8), body)));
      headers.put("X-Cyt-Signature-Alg", "hmac-sha256");
    }
    long start = System.nanoTime();
    try {
      var response = http.exchange("POST", JsonUtils.str(config, "url"), headers, body, 10, null);
      logs.record(
          trace,
          system,
          platform + ".webhook.deliver",
          "callback",
          (System.nanoTime() - start) / 1000000,
          JsonUtils.obj("platform", platform),
          JsonUtils.obj("http_status", response.status()),
          false,
          null,
          null);
      return JsonUtils.obj("delivered", true, "reason", "ok", "http_status", response.status());
    } catch (GatewayException e) {
      logs.record(
          trace,
          system,
          platform + ".webhook.deliver",
          "callback",
          (System.nanoTime() - start) / 1000000,
          JsonUtils.obj("platform", platform),
          null,
          false,
          null,
          e);
      return JsonUtils.obj(
          "delivered", false, "reason", "deliver_failed", "http_status", null, "error", e.type);
    }
  }
}

package group.cyt.common.module.callback.controller;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.auth.service.AuthenticationService;
import group.cyt.common.module.callback.service.CallbackService;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.custom.service.ConfigurationService;
import group.cyt.common.module.custom.service.ConfigurationService.Kind;
import group.cyt.common.module.gateway.service.CallLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class CallbackController {
  private final ConfigurationService config;
  private final CallbackService service;
  private final CredentialService credentials;
  private final GatewaySettings settings;
  private final CallLogService logs;

  public CallbackController(
      ConfigurationService config,
      CallbackService service,
      CredentialService credentials,
      GatewaySettings settings,
      CallLogService logs) {
    this.config = config;
    this.service = service;
    this.credentials = credentials;
    this.settings = settings;
    this.logs = logs;
  }

  @GetMapping("/admin/api/callbacks")
  public Object list() {
    var items = config.list(Kind.CALLBACK);
    items.forEach(
        c ->
            c.put(
                "inbound_url",
                settings.get("PUBLIC_BASE_URL")
                    + "/integrations/"
                    + c.get("platform")
                    + "/webhooks/"
                    + c.get("system")
                    + "/events"));
    return JsonUtils.obj("items", items);
  }

  @PostMapping("/admin/api/callbacks")
  public Object create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    return JsonUtils.obj(
        "ok", true, "id", config.save(Kind.CALLBACK, null, body, AuthenticationService.actor(req)));
  }

  @PutMapping("/admin/api/callbacks/{id}")
  public Object edit(
      @PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
    return JsonUtils.obj(
        "ok", true, "id", config.save(Kind.CALLBACK, id, body, AuthenticationService.actor(req)));
  }

  @DeleteMapping("/admin/api/callbacks/{id}")
  public Object delete(@PathVariable long id, HttpServletRequest req) {
    config.delete(Kind.CALLBACK, id, AuthenticationService.actor(req));
    return JsonUtils.obj("ok", true);
  }

  @PostMapping("/admin/api/callbacks/{id}/test")
  public Object test(@PathVariable long id, HttpServletRequest req) {
    return service.test(id, AuthenticationService.actor(req));
  }

  @PostMapping({
    "/integrations/docusign/webhooks/events",
    "/integrations/docusign/webhooks/{system}/events"
  })
  public Object webhook(
      @PathVariable(required = false) String system,
      @RequestBody byte[] raw,
      @RequestHeader(value = "X-DocuSign-Signature-1", required = false) String signature) {
    if (raw.length > 2 * 1024 * 1024) throw GatewayException.invalid("回调内容过大");
    String trace = UUID.randomUUID().toString().replace("-", "");
    String key = credentials.resolve("DOCUSIGN_HMAC_KEY", system == null ? "" : system);
    boolean verified = false;
    try {
      if (!key.isBlank()) {
        String expected =
            Base64.getEncoder()
                .encodeToString(JsonUtils.hmac(key.getBytes(StandardCharsets.UTF_8), raw));
        if (signature == null || !JsonUtils.equal(expected, signature.trim()))
          throw GatewayException.unauth("DocuSign 回调验签失败");
        verified = true;
      } else if (settings.production() || !settings.mock())
        throw GatewayException.unauth("缺少 DocuSign 回调验签密钥");
      var payload = JsonUtils.read(new String(raw, StandardCharsets.UTF_8));
      var data = JsonUtils.map(payload.get("data"));
      var envelope = JsonUtils.map(data.get("envelopeSummary"));
      if (envelope.isEmpty()) envelope = JsonUtils.map(payload.get("envelopeSummary"));
      var event =
          JsonUtils.obj(
              "event_type",
              payload.getOrDefault("event", payload.getOrDefault("eventType", "unknown")),
              "envelope_id",
              envelope.getOrDefault("envelopeId", data.get("envelopeId")),
              "status",
              envelope.getOrDefault("status", data.get("status")),
              "status_changed_at",
              envelope.get("statusChangedDateTime"),
              "subject",
              envelope.get("emailSubject"),
              "recipients",
              JsonUtils.map(envelope.get("recipients")).getOrDefault("signers", List.of()));
      logs.record(
          trace,
          system == null ? "external" : system,
          "docusign.webhooks.receive",
          "docusign",
          0,
          JsonUtils.obj("event_type", event.get("event_type")),
          null,
          !verified,
          null,
          null);
      Object forward =
          verified
              ? service.deliver(system, "docusign", event, trace)
              : JsonUtils.obj("delivered", false, "reason", "unverified_mock", "http_status", null);
      return JsonUtils.obj("ok", true, "data", event, "forward", forward);
    } catch (GatewayException e) {
      e.trace = trace;
      logs.record(
          trace,
          system == null ? "external" : system,
          "docusign.webhooks.receive",
          "docusign",
          0,
          null,
          null,
          false,
          null,
          e);
      throw e;
    }
  }
}

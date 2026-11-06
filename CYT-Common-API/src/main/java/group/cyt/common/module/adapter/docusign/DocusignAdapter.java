package group.cyt.common.module.adapter.docusign;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.adapter.support.*;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DocusignAdapter implements PlatformAdapter {
  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final TokenCache cache;

  public DocusignAdapter(CredentialService credentials, UpstreamHttp http, TokenCache cache) {
    this.credentials = credentials;
    this.http = http;
    this.cache = cache;
  }

  public boolean supports(String platform) {
    return platform.equals("docusign");
  }

  public Object invoke(OperationDefinition op, Map<String, Object> p, String system) {
    if (op.key().equals("docusign.testing.advance"))
      throw GatewayException.forbidden("testing.advance 仅用于模拟模式");
    String environment = credentials.resolve("DOCUSIGN_ENV", system),
        auth =
            environment.equals("production") || environment.equals("prod")
                ? "account.docusign.com"
                : "account-d.docusign.com";
    String integration = credentials.required("DOCUSIGN_INTEGRATION_KEY", system),
        user = credentials.required("DOCUSIGN_USER_ID", system),
        privateKey = credentials.required("DOCUSIGN_PRIVATE_KEY", system),
        account = credentials.required("DOCUSIGN_ACCOUNT_ID", system);
    String token =
        cache.get(
            "docusign:" + system + ":" + integration + ":" + user + ":" + privateKey,
            false,
            () -> {
              try {
                Instant now = Instant.now();
                var claims =
                    new JWTClaimsSet.Builder()
                        .issuer(integration)
                        .subject(user)
                        .audience(auth)
                        .claim("scope", "signature impersonation")
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(3600)))
                        .build();
                var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
                jwt.sign(new RSASSASigner(SignatureSupport.privateKey(privateKey)));
                return JsonUtils.map(
                    http.form(
                        "https://" + auth + "/oauth/token",
                        Map.of(
                            "grant_type",
                            "urn:ietf:params:oauth:grant-type:jwt-bearer",
                            "assertion",
                            jwt.serialize()),
                        Map.of()));
              } catch (JOSEException e) {
                throw GatewayException.config("DocuSign JWT 签名失败");
              }
            });
    String rest = credentials.resolve("DOCUSIGN_BASE_URI", system);
    if (rest.isBlank())
      rest =
          auth.equals("account-d.docusign.com")
              ? "https://demo.docusign.net"
              : "https://www.docusign.net";
    String base =
        rest.replaceAll("/$", "") + "/restapi/v2.1/accounts/" + UpstreamHttp.segment(account);
    Map<String, String> headers = Map.of("Authorization", "Bearer " + token);
    return switch (op.key()) {
      case "docusign.envelopes.create" -> {
        Map<String, Object> body =
            JsonUtils.obj(
                "emailSubject",
                p.get("email_subject"),
                "emailBlurb",
                p.get("email_blurb"),
                "status",
                p.get("status"));
        if (!JsonUtils.str(p, "template_id").isBlank()) {
          body.put("templateId", p.get("template_id"));
          body.put(
              "templateRoles",
              JsonUtils.list(p.get("template_roles")).stream()
                  .map(
                      v -> {
                        var r = JsonUtils.map(v);
                        var role =
                            JsonUtils.obj(
                                "roleName",
                                r.get("role_name"),
                                "email",
                                r.get("email"),
                                "name",
                                r.get("name"),
                                "routingOrder",
                                JsonUtils.str(r, "routing_order", "1"));
                        if (r.containsKey("tabs")) role.put("tabs", r.get("tabs"));
                        return role;
                      })
                  .toList());
        } else {
          List<Object> documents = new ArrayList<>();
          int i = 0;
          for (Object d : JsonUtils.list(p.get("documents"))) {
            var doc = JsonUtils.map(d);
            documents.add(
                JsonUtils.obj(
                    "name",
                    doc.get("name"),
                    "fileExtension",
                    doc.get("file_extension"),
                    "documentBase64",
                    doc.get("document_base64"),
                    "documentId",
                    Integer.toString(++i)));
          }
          body.put("documents", documents);
          body.put(
              "recipients",
              JsonUtils.obj(
                  "signers",
                  JsonUtils.list(p.get("recipients")).stream()
                      .map(
                          v -> {
                            var r = JsonUtils.map(v);
                            return JsonUtils.obj(
                                "email",
                                r.get("email"),
                                "name",
                                r.get("name"),
                                "recipientId",
                                r.get("recipient_id"),
                                "routingOrder",
                                JsonUtils.str(r, "routing_order", "1"));
                          })
                      .toList()));
        }
        var r = http.object("POST", base + "/envelopes", headers, body);
        yield JsonUtils.obj(
            "envelope_id",
            r.get("envelopeId"),
            "status",
            r.get("status"),
            "status_time",
            r.get("statusDateTime"),
            "mock",
            false);
      }
      case "docusign.envelopes.get" -> {
        String url = base + "/envelopes/" + UpstreamHttp.segment(p.get("envelope_id"));
        var r = http.object("GET", url, headers, null);
        var recipients = http.object("GET", url + "/recipients", headers, null);
        var result =
            JsonUtils.obj(
                "envelope_id",
                r.get("envelopeId"),
                "status",
                r.get("status"),
                "status_changed_at",
                r.get("statusChangedDateTime"),
                "email_subject",
                r.get("emailSubject"),
                "sent_at",
                r.get("sentDateTime"),
                "completed_at",
                r.get("completedDateTime"),
                "voided_reason",
                r.get("voidedReason"),
                "signers",
                JsonUtils.list(recipients.get("signers")).stream()
                    .map(
                        v -> {
                          var signer = JsonUtils.map(v);
                          return JsonUtils.obj(
                              "name",
                              signer.get("name"),
                              "email",
                              signer.get("email"),
                              "status",
                              signer.get("status"),
                              "recipient_id",
                              signer.get("recipientId"),
                              "routing_order",
                              signer.get("routingOrder"),
                              "signed_at",
                              signer.get("signedDateTime"));
                        })
                    .toList(),
                "mock",
                false);
        String status = JsonUtils.str(r, "status");
        boolean terminal = Set.of("completed", "declined", "voided", "deleted").contains(status);
        result.put("terminal", terminal);
        var pending =
            JsonUtils.list(result.get("signers")).stream()
                .map(JsonUtils::map)
                .filter(
                    s ->
                        !Set.of("completed", "signed", "declined")
                            .contains(JsonUtils.str(s, "status")))
                .toList();
        int nextOrder =
            pending.stream()
                .mapToInt(s -> JsonUtils.integer(s.get("routing_order"), 1))
                .min()
                .orElse(0);
        result.put(
            "next_signers",
            terminal || status.equals("created")
                ? List.of()
                : pending.stream()
                    .filter(s -> JsonUtils.integer(s.get("routing_order"), 1) == nextOrder)
                    .toList());
        result.put(
            "next_action",
            terminal ? "none" : status.equals("created") ? "send_draft" : "await_signature");
        result.put("expires_at", r.get("expireDateTime"));
        yield result;
      }
      case "docusign.envelopes.list" -> {
        String from = JsonUtils.str(p, "from_date");
        if (from.isBlank()) from = LocalDate.now(ZoneOffset.UTC).minusDays(30).toString();
        var r =
            http.object(
                "GET",
                UpstreamHttp.query(
                    base + "/envelopes",
                    JsonUtils.obj(
                        "from_date",
                        from,
                        "to_date",
                        p.get("to_date"),
                        "status",
                        p.get("status"),
                        "count",
                        p.get("page_size"),
                        "start_position",
                        p.get("start_position"))),
                headers,
                null);
        var result =
            JsonUtils.obj(
                "total",
                JsonUtils.integer(r.get("resultSetSize"), 0),
                "items",
                JsonUtils.list(r.get("envelopes")).stream()
                    .map(
                        v -> {
                          var e = JsonUtils.map(v);
                          return JsonUtils.obj(
                              "envelope_id",
                              e.get("envelopeId"),
                              "status",
                              e.get("status"),
                              "subject",
                              e.get("emailSubject"),
                              "sent_at",
                              e.get("sentDateTime"),
                              "completed_at",
                              e.get("completedDateTime"),
                              "status_changed_at",
                              e.get("statusChangedDateTime"));
                        })
                    .toList(),
                "mock",
                false);
        result.putAll(page(r, p, JsonUtils.list(result.get("items")).size()));
        yield result;
      }
      case "docusign.envelopes.void" -> {
        var before =
            http.object(
                "GET",
                base + "/envelopes/" + UpstreamHttp.segment(p.get("envelope_id")),
                headers,
                null);
        String status = JsonUtils.str(before, "status");
        if (status.equals("voided"))
          yield JsonUtils.obj(
              "envelope_id",
              p.get("envelope_id"),
              "status",
              status,
              "already_voided",
              true,
              "mock",
              false);
        if (Set.of("completed", "declined", "deleted").contains(status))
          throw new GatewayException(409, "state_conflict", "此信封当前状态不可作废：" + status, false);
        http.object(
            "PUT",
            base + "/envelopes/" + UpstreamHttp.segment(p.get("envelope_id")),
            headers,
            JsonUtils.obj("status", "voided", "voidedReason", p.get("reason")));
        yield JsonUtils.obj(
            "envelope_id",
            p.get("envelope_id"),
            "status",
            "voided",
            "reason",
            p.get("reason"),
            "mock",
            false);
      }
      case "docusign.templates.list" -> {
        var r =
            http.object(
                "GET",
                UpstreamHttp.query(
                    base + "/templates",
                    JsonUtils.obj(
                        "count", p.get("page_size"), "start_position", p.get("start_position"))),
                headers,
                null);
        var result =
            JsonUtils.obj(
                "total",
                JsonUtils.integer(r.get("resultSetSize"), 0),
                "items",
                JsonUtils.list(r.get("envelopeTemplates")).stream()
                    .map(
                        v -> {
                          var t = JsonUtils.map(v);
                          return JsonUtils.obj(
                              "template_id",
                              t.get("templateId"),
                              "name",
                              t.get("name"),
                              "description",
                              t.get("description"));
                        })
                    .toList(),
                "mock",
                false);
        result.putAll(page(r, p, JsonUtils.list(result.get("items")).size()));
        yield result;
      }
      case "docusign.templates.get" -> {
        String url = base + "/templates/" + UpstreamHttp.segment(p.get("template_id"));
        var template = http.object("GET", url, headers, null);
        var recipients = http.object("GET", url + "/recipients?include_tabs=true", headers, null);
        yield JsonUtils.obj(
            "template_id",
            p.get("template_id"),
            "name",
            template.get("name"),
            "email_subject",
            template.get("emailSubject"),
            "email_blurb",
            template.get("emailBlurb"),
            "recipients",
            recipients,
            "official",
            template);
      }
      default -> throw GatewayException.config("未知 DocuSign 操作");
    };
  }

  private static Map<String, Object> page(
      Map<String, Object> response, Map<String, Object> params, int count) {
    int start = JsonUtils.integer(params.get("start_position"), 0);
    int total = JsonUtils.integer(response.get("totalSetSize"), start + count);
    boolean more = !JsonUtils.str(response, "nextUri").isBlank() || start + count < total;
    Integer next = null;
    if (more) {
      String uri = JsonUtils.str(response, "nextUri");
      if (!uri.isBlank()) {
        try {
          String query = java.net.URI.create(uri).getRawQuery();
          if (query != null)
            for (String pair : query.split("&")) {
              String[] parts = pair.split("=", 2);
              if (parts.length == 2 && parts[0].equals("start_position"))
                next =
                    Integer.valueOf(
                        java.net.URLDecoder.decode(
                            parts[1], java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
          throw new GatewayException(502, "upstream_error", "DocuSign 返回无效分页位置", false);
        }
      }
      if (next == null && count > 0) next = start + count;
      if (next == null || next <= start)
        throw new GatewayException(502, "upstream_error", "DocuSign 分页没有推进", false);
    }
    return JsonUtils.obj("total_available", total, "has_more", more, "next_start_position", next);
  }
}

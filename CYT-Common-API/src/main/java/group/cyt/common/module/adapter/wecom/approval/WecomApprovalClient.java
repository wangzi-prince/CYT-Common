package group.cyt.common.module.adapter.wecom.approval;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.support.TokenCache;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.grant.service.QuotaService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Application selection follows the authenticated caller, never a client-supplied secret/profile.
 */
@Component
public class WecomApprovalClient {
  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final TokenCache tokens;
  private final QuotaService quota;

  public WecomApprovalClient(
      CredentialService credentials, UpstreamHttp http, TokenCache tokens, QuotaService quota) {
    this.credentials = credentials;
    this.http = http;
    this.tokens = tokens;
    this.quota = quota;
  }

  public String credential(String name, String system) {
    String scoped = "WEWORK_" + system.toUpperCase(Locale.ROOT).replace('-', '_') + "_" + name;
    String value = credentials.resolve(scoped, system);
    return value.isBlank() ? credentials.resolve("WEWORK_" + name, system) : value;
  }

  public String corp(String system) {
    String value = credential("CORP_ID", system);
    if (value.isBlank()) throw GatewayException.config("缺少 WEWORK_CORP_ID");
    return value;
  }

  private String secret(String system) {
    String value = credential("APPROVAL_SECRET", system);
    if (value.isBlank())
      value =
          credentials.resolve(
              "WEWORK_APP_" + system.toUpperCase(Locale.ROOT).replace('-', '_') + "_SECRET",
              system);
    if (value.isBlank()) value = credentials.resolve("WEWORK_SECRET", system);
    if (value.isBlank()) throw GatewayException.config("请配置企微审批应用或具有审批权限的自建应用凭据");
    return value;
  }

  public String profile(String system) {
    return JsonUtils.sha(corp(system) + ":" + secret(system));
  }

  private String base(String system) {
    if (credentials.resolve("WEWORK_PROXY_TOKEN", system).isBlank())
      return "https://qyapi.weixin.qq.com/cgi-bin";
    String base = credentials.resolve("WEWORK_PROXY_BASE", system);
    if (base.isBlank()) base = "https://erp-proxy.cyt.group/wxwork";
    return base.replaceAll("/+$", "") + "/cgi-bin";
  }

  private Map<String, String> headers(String system) {
    String proxy = credentials.resolve("WEWORK_PROXY_TOKEN", system);
    return proxy.isBlank() ? Map.of() : Map.of("X-Proxy-Token", proxy);
  }

  private String token(String system, boolean refresh) {
    return tokens.get(
        "wecom:" + corp(system) + ":" + secret(system),
        refresh,
        () ->
            check(
                http.object(
                    "GET",
                    UpstreamHttp.query(
                        base(system) + "/gettoken",
                        JsonUtils.obj("corpid", corp(system), "corpsecret", secret(system))),
                    headers(system),
                    null)));
  }

  public Map<String, Object> post(String path, Map<String, Object> body, String system) {
    for (int attempt = 0; attempt < 2; attempt++) {
      budget(system);
      var response =
          http.object(
              "POST",
              UpstreamHttp.query(
                  base(system) + path, JsonUtils.obj("access_token", token(system, attempt > 0))),
              headers(system),
              body);
      if (attempt == 0 && Set.of("40014", "42001").contains(JsonUtils.str(response, "errcode")))
        continue;
      return check(response);
    }
    throw GatewayException.config("企微审批令牌刷新失败");
  }

  public Map<String, Object> upload(String filename, byte[] bytes, String system) {
    validateUpload(filename, bytes);
    String boundary = "cyt" + UUID.randomUUID().toString().replace("-", "");
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"media\"; filename=\""
                + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[head.length + bytes.length + tail.length];
    System.arraycopy(head, 0, body, 0, head.length);
    System.arraycopy(bytes, 0, body, head.length, bytes.length);
    System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);
    var headers = new LinkedHashMap<>(headers(system));
    headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
    budget(system);
    return check(
        JsonUtils.read(
            new String(
                http.raw(
                    "POST",
                    UpstreamHttp.query(
                        base(system) + "/media/upload",
                        JsonUtils.obj("access_token", token(system, false), "type", "file")),
                    headers,
                    body,
                    30,
                    null),
                StandardCharsets.UTF_8)));
  }

  public static void validateUpload(String filename, byte[] bytes) {
    if (bytes.length == 0
        || bytes.length > 10 * 1024 * 1024
        || filename.isBlank()
        || filename.length() > 128
        || filename.contains("\"")
        || filename.contains("\\")
        || filename.contains("/")
        || filename.contains("\r")
        || filename.contains("\n"))
      throw GatewayException.invalid("附件须为非空且不超过 10 MB，文件名不能包含路径、引号或换行");
  }

  public Map<String, Object> download(String mediaId, String system) {
    budget(system);
    var response =
        http.exchange(
            "GET",
            UpstreamHttp.query(
                base(system) + "/media/get",
                JsonUtils.obj("access_token", token(system, false), "media_id", mediaId)),
            headers(system),
            null,
            30,
            null);
    String contentType =
        response.headers().entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse("application/octet-stream");
    if (contentType.contains("json")) {
      Object body = JsonUtils.parse(new String(response.body(), StandardCharsets.UTF_8));
      if (body instanceof Map<?, ?> map && map.containsKey("errcode")) check(JsonUtils.map(map));
    }
    return JsonUtils.obj(
        "file_id",
        mediaId,
        "content_type",
        contentType,
        "size",
        response.body().length,
        "content_base64",
        Base64.getEncoder().encodeToString(response.body()));
  }

  private void budget(String system) {
    if (Thread.currentThread().isInterrupted())
      throw new GatewayException(503, "interrupted", "请求已中断", false);
    quota.consume("wecom-approval:" + profile(system), "600/min");
  }

  private Map<String, Object> check(Map<String, Object> response) {
    String code = JsonUtils.str(response, "errcode");
    if (!code.isBlank() && !code.equals("0")) {
      String hint =
          switch (code) {
            case "301055", "301088", "48002" -> "请在企微管理后台将应用加入审批可调用应用，并授予审批数据权限";
            case "301025", "301086" -> "请刷新模板后检查字段、审批流程和参数";
            case "301112" -> "企微要求缩小查询时间范围，请缩短单次窗口";
            case "45009" -> "企微调用频率受限，请稍后重试";
            default -> "企微审批调用失败，请凭错误码核对应用权限及官方文档";
          };
      throw new GatewayException(
              code.equals("45009") ? 429 : 502, "upstream_error", hint, code.equals("45009"))
          .code("WECOM_" + code);
    }
    return response;
  }
}

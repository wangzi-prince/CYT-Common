package group.cyt.common.module.adapter.google;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Component;

@Component
public class GoogleAdapter implements PlatformAdapter {
  private final GoogleTokenService tokens;
  private final UpstreamHttp http;
  private final CredentialService credentials;
  private final GatewaySettings settings;

  public GoogleAdapter(
      GoogleTokenService tokens,
      UpstreamHttp http,
      CredentialService credentials,
      GatewaySettings settings) {
    this.tokens = tokens;
    this.http = http;
    this.credentials = credentials;
    this.settings = settings;
  }

  public boolean supports(String platform) {
    return Set.of("gmail", "gdrive", "gcs").contains(platform);
  }

  public Object invoke(OperationDefinition op, Map<String, Object> p, String system) {
    return switch (op.platform()) {
      case "gmail" -> gmail(op, p, system);
      case "gdrive" -> drive(op, p, system);
      default -> gcs(op, p, system);
    };
  }

  private Object gmail(OperationDefinition op, Map<String, Object> p, String system) {
    String base = "https://gmail.googleapis.com/gmail/v1/users/me";
    Map<String, String> headers = Map.of("Authorization", "Bearer " + tokens.user(system));
    String account = JsonUtils.str(p, "account");
    boolean write =
        op.key().equals("gmail.messages.send") || op.key().endsWith("restore_from_spam");
    var profile = http.object("GET", base + "/profile", headers, null);
    String actual = JsonUtils.str(profile, "emailAddress");
    if (actual.isBlank() || !account.isBlank() && !account.equalsIgnoreCase(actual))
      throw GatewayException.forbidden("指定邮箱与实际授权邮箱不一致");
    if (op.key().equals("gmail.profile.get")) return profile;
    if (write) checkTestTarget("gmail", account);
    return switch (op.key()) {
      case "gmail.labels.list" -> http.object("GET", base + "/labels", headers, null);
      case "gmail.messages.list" -> {
        var url =
            HttpUrl.get(base + "/messages")
                .newBuilder()
                .addQueryParameter("maxResults", p.get("max_results").toString())
                .addQueryParameter("includeSpamTrash", p.get("include_spam_trash").toString());
        if (!JsonUtils.str(p, "query").isBlank())
          url.addQueryParameter("q", JsonUtils.str(p, "query"));
        if (!JsonUtils.str(p, "page_token").isBlank())
          url.addQueryParameter("pageToken", JsonUtils.str(p, "page_token"));
        for (Object label : JsonUtils.list(p.get("label_ids")))
          url.addQueryParameter("labelIds", label.toString());
        yield http.object("GET", url.build().toString(), headers, null);
      }
      case "gmail.messages.get" -> {
        var url =
            HttpUrl.get(base + "/messages/" + UpstreamHttp.segment(p.get("message_id")))
                .newBuilder()
                .addQueryParameter("format", JsonUtils.str(p, "format", "metadata"));
        if (JsonUtils.str(p, "format").equals("metadata"))
          for (Object header : JsonUtils.list(p.get("metadata_headers")))
            url.addQueryParameter("metadataHeaders", header.toString());
        yield http.object("GET", url.build().toString(), headers, null);
      }
      case "gmail.messages.restore_from_spam" -> {
        String path = base + "/messages/" + UpstreamHttp.segment(p.get("message_id"));
        var message = http.object("GET", path + "?format=minimal", headers, null);
        var labels = JsonUtils.list(message.get("labelIds"));
        if (!labels.contains("SPAM") || labels.contains("TRASH"))
          throw GatewayException.forbidden("邮件必须位于 SPAM 且不能位于 TRASH");
        yield http.object(
            "POST",
            path + "/modify",
            headers,
            JsonUtils.obj("removeLabelIds", List.of("SPAM"), "addLabelIds", List.of("INBOX")));
      }
      case "gmail.messages.send" -> {
        List<String> recipients = new ArrayList<>();
        for (String field : List.of("to", "cc", "bcc"))
          for (Object v : JsonUtils.list(p.get(field))) {
            String address = v.toString().trim();
            if (!address.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+")
                || !settings.csv("GATEWAY_TEST_EMAIL_RECIPIENTS", "").stream()
                    .anyMatch(address::equalsIgnoreCase))
              throw GatewayException.forbidden("所有收件人、抄送和密送必须属于测试白名单");
            recipients.add(address);
          }
        if (recipients.isEmpty()) throw GatewayException.invalid("收件人不能为空");
        StringBuilder mime = new StringBuilder("MIME-Version: 1.0\r\n");
        for (String field : List.of("to", "cc", "bcc")) {
          var list = JsonUtils.list(p.get(field));
          if (!list.isEmpty())
            mime.append(field)
                .append(": ")
                .append(String.join(", ", list.stream().map(Object::toString).toList()))
                .append("\r\n");
        }
        String subject = JsonUtils.str(p, "subject");
        if (subject.contains("\r") || subject.contains("\n"))
          throw GatewayException.invalid("邮件主题不能包含换行");
        boolean html = p.get("body_html") != null && !JsonUtils.str(p, "body_html").isBlank();
        mime.append("Subject: =?UTF-8?B?")
            .append(Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)))
            .append("?=\r\nContent-Type: text/")
            .append(html ? "html" : "plain")
            .append("; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            .append(
                Base64.getMimeEncoder()
                    .encodeToString(
                        JsonUtils.str(p, html ? "body_html" : "body_text")
                            .getBytes(StandardCharsets.UTF_8)));
        var body =
            JsonUtils.obj(
                "raw",
                Base64.getUrlEncoder()
                    .encodeToString(mime.toString().getBytes(StandardCharsets.UTF_8)));
        if (!JsonUtils.str(p, "thread_id").isBlank()) body.put("threadId", p.get("thread_id"));
        yield http.object("POST", base + "/messages/send", headers, body);
      }
      default -> throw GatewayException.config("未知 Gmail 操作");
    };
  }

  public void checkTestTarget(String platform, String target) {
    String name =
        platform.equals("gmail") ? "GATEWAY_TEST_GMAIL_ACCOUNTS" : "GATEWAY_TEST_SHOPIFY_STORES";
    if (!settings.flag("GATEWAY_ENABLE_TEST_WRITES", false)
        || target.isBlank()
        || settings.csv(name, "").stream().noneMatch(target::equalsIgnoreCase))
      throw GatewayException.forbidden("正式业务数据只读：写入只允许显式配置的测试账号");
  }

  private Object drive(OperationDefinition op, Map<String, Object> p, String system) {
    String base = "https://www.googleapis.com/drive/v3/files";
    Map<String, String> headers =
        new HashMap<>(Map.of("Authorization", "Bearer " + tokens.user(system)));
    Map<String, Object> q =
        JsonUtils.obj("fields", p.get("fields"), "supportsAllDrives", p.get("supports_all_drives"));
    if (op.key().equals("gdrive.files.list")) {
      for (var e :
          Map.of(
                  "query",
                  "q",
                  "page_size",
                  "pageSize",
                  "page_token",
                  "pageToken",
                  "order_by",
                  "orderBy",
                  "spaces",
                  "spaces",
                  "corpora",
                  "corpora",
                  "drive_id",
                  "driveId",
                  "include_items_from_all_drives",
                  "includeItemsFromAllDrives")
              .entrySet()) q.put(e.getValue(), p.get(e.getKey()));
    }
    for (String field : List.of("include_permissions_for_view", "include_labels"))
      if (p.get(field) != null) q.put(camel(field), p.get(field));
    if (op.key().equals("gdrive.files.list"))
      return http.object("GET", UpstreamHttp.query(base, q), headers, null);
    if (op.key().equals("gdrive.files.get"))
      return http.object(
          "GET",
          UpstreamHttp.query(base + "/" + UpstreamHttp.segment(p.get("file_id")), q),
          headers,
          null);
    Map<String, Object> metadata = JsonUtils.obj("name", p.get("name"));
    if (!JsonUtils.str(p, "parent_id").isBlank())
      metadata.put("parents", List.of(p.get("parent_id")));
    if (op.key().equals("gdrive.folders.create")) {
      metadata.put("mimeType", "application/vnd.google-apps.folder");
      return http.object("POST", UpstreamHttp.query(base, q), headers, metadata);
    }
    byte[] content = content(p);
    String boundary = "cyt-" + JsonUtils.random(18),
        mime = JsonUtils.str(p, "mime_type", "application/octet-stream");
    if (p.get("content_text") != null && mime.equals("application/pdf"))
      mime = "text/plain; charset=utf-8";
    if (mime.contains("\r") || mime.contains("\n")) throw GatewayException.invalid("MIME 类型无效");
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(
          ("--"
                  + boundary
                  + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                  + JsonUtils.write(metadata)
                  + "\r\n--"
                  + boundary
                  + "\r\nContent-Type: "
                  + mime
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.write(content);
      out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      headers.put("Content-Type", "multipart/related; boundary=" + boundary);
      q.put("uploadType", "multipart");
      for (String f :
          List.of(
              "ignore_default_visibility",
              "use_content_as_indexable_text",
              "keep_revision_forever")) q.put(camel(f), p.get(f));
      return JsonUtils.parse(
          new String(
              http.raw(
                  "POST",
                  UpstreamHttp.query("https://www.googleapis.com/upload/drive/v3/files", q),
                  headers,
                  out.toByteArray(),
                  60,
                  null),
              StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Object gcs(OperationDefinition op, Map<String, Object> p, String system) {
    String bucket = JsonUtils.str(p, "bucket");
    if (bucket.isBlank()) bucket = credentials.required("GCS_BUCKET", system);
    String base =
        "https://storage.googleapis.com/storage/v1/b/" + UpstreamHttp.segment(bucket) + "/o";
    Map<String, String> headers =
        new HashMap<>(Map.of("Authorization", "Bearer " + tokens.service(system)));
    if (op.key().equals("gcs.objects.list")) {
      Map<String, Object> q = new LinkedHashMap<>();
      for (String f :
          List.of(
              "prefix",
              "page_token",
              "delimiter",
              "include_trailing_delimiter",
              "include_folders_as_prefixes",
              "match_glob",
              "start_offset",
              "end_offset",
              "projection",
              "versions",
              "soft_deleted")) q.put(camel(f), p.get(f));
      q.put("maxResults", p.get("page_size"));
      return http.object("GET", UpstreamHttp.query(base, q), headers, null);
    }
    if (op.key().equals("gcs.objects.upload")) {
      String mime = JsonUtils.str(p, "content_type", "application/octet-stream");
      if (p.get("content_text") != null && mime.equals("application/octet-stream"))
        mime = "text/plain; charset=utf-8";
      headers.put("Content-Type", mime);
      String url =
          UpstreamHttp.query(
              "https://storage.googleapis.com/upload/storage/v1/b/"
                  + UpstreamHttp.segment(bucket)
                  + "/o",
              JsonUtils.obj("uploadType", "media", "name", p.get("path")));
      return JsonUtils.parse(
          new String(
              http.raw("POST", url, headers, content(p), 120, null), StandardCharsets.UTF_8));
    }
    String url = base + "/" + UpstreamHttp.segment(p.get("path"));
    if (op.key().equals("gcs.objects.delete")) {
      http.raw("DELETE", url, headers, null, 30, null);
      return JsonUtils.obj("deleted", true, "path", p.get("path"));
    }
    return http.object("GET", url, headers, null);
  }

  public static byte[] content(Map<String, Object> p) {
    if ((p.get("content_text") == null) == (p.get("content_base64") == null))
      throw GatewayException.invalid("content_text 与 content_base64 必须且只能提供一个");
    try {
      byte[] bytes =
          p.get("content_text") != null
              ? JsonUtils.str(p, "content_text").getBytes(StandardCharsets.UTF_8)
              : Base64.getDecoder().decode(JsonUtils.str(p, "content_base64"));
      if (bytes.length > 20 * 1024 * 1024) throw GatewayException.invalid("文件不能超过 20 MB");
      return bytes;
    } catch (IllegalArgumentException e) {
      throw GatewayException.invalid("content_base64 不是合法 Base64");
    }
  }

  private static String camel(String snake) {
    StringBuilder s = new StringBuilder();
    boolean upper = false;
    for (char c : snake.toCharArray()) {
      if (c == '_') upper = true;
      else {
        s.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return s.toString();
  }
}

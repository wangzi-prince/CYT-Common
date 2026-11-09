package group.cyt.common.module.adapter;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Explicit local simulation. No credentials and no upstream network calls. */
@Component
public class MockAdapter {
  private final Map<String, Map<String, Object>> envelopes = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> calendars = new ConcurrentHashMap<>(),
      schedules = new ConcurrentHashMap<>();

  /** 与旧 Python 网关 MockGmail.list_messages 逐字段一致，替换网关时调用方拿到的模拟数据不变。 */
  private Object gmailMessagesList(Map<String, Object> p) {
    int count = Math.max(0, Math.min(JsonUtils.integer(p.get("max_results"), 5), 5));
    List<Object> messages = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String suffix = String.format("%04d", i);
      messages.add(JsonUtils.obj("id", "mock-msg-" + suffix, "threadId", "t" + suffix));
    }
    return JsonUtils.obj(
        "resultSizeEstimate",
        count,
        "messages",
        messages,
        "nextPageToken",
        "mock-next",
        "_mock",
        true);
  }

  /** 与旧 Python 网关 MockGmail.get_message 逐字段一致。旧库近 30 天有 60 次成功调用，缺 payload/_mock 会让调用方拿到不同形状。 */
  private Object gmailMessagesGet(Map<String, Object> p) {
    return JsonUtils.obj(
        "id",
        p.get("message_id"),
        "threadId",
        "t0001",
        "labelIds",
        List.of("INBOX", "UNREAD"),
        "snippet",
        "模拟邮件摘要：关于订单 #10231 的物流查询……",
        "payload",
        JsonUtils.obj(
            "headers",
            List.of(
                JsonUtils.obj("name", "From", "value", "customer@example.com"),
                JsonUtils.obj("name", "To", "value", "support@example.com"),
                JsonUtils.obj("name", "Subject", "value", "Where is my order?"),
                JsonUtils.obj("name", "Date", "value", "2026-10-18T07:12:00Z"))),
        "_mock",
        true);
  }

  public Object invoke(OperationDefinition op, Map<String, Object> p, String system) {
    String key = op.key(), action = key.substring(key.lastIndexOf('.') + 1);
    String now = Instant.now().toString();
    if (op.platform().equals("docusign")) {
      if (key.equals("docusign.templates.get"))
        return JsonUtils.obj(
            "template_id",
            p.get("template_id"),
            "name",
            "演示合同",
            "recipients",
            JsonUtils.obj(
                "signers",
                List.of(
                    JsonUtils.obj(
                        "roleName",
                        "Signer",
                        "recipientId",
                        "1",
                        "tabs",
                        JsonUtils.obj(
                            "textTabs",
                            List.of(
                                JsonUtils.obj("tabLabel", "Reference", "required", "true")))))));
      if (action.equals("create")) {
        String id = UUID.randomUUID().toString();
        var row =
            JsonUtils.obj(
                "envelope_id",
                id,
                "status",
                JsonUtils.str(p, "status", "sent"),
                "email_subject",
                p.get("email_subject"),
                "status_time",
                now,
                "signers",
                p.getOrDefault("recipients", List.of()),
                "mock",
                true);
        envelopes.put(system + ":" + id, row);
        return row;
      }
      if (key.equals("docusign.envelopes.list"))
        return JsonUtils.obj(
            "items",
            envelopes.entrySet().stream()
                .filter(e -> e.getKey().startsWith(system + ":"))
                .map(Map.Entry::getValue)
                .toList(),
            "total",
            envelopes.size(),
            "mock",
            true);
      if (key.equals("docusign.templates.list"))
        return JsonUtils.obj(
            "items",
            List.of(
                JsonUtils.obj(
                    "template_id", "demo-template", "name", "演示合同", "description", "本地模拟模板")),
            "total",
            1,
            "mock",
            true);
      var row = envelopes.get(system + ":" + JsonUtils.str(p, "envelope_id"));
      if (row == null) throw GatewayException.missing("模拟信封不存在");
      if (action.equals("void")) {
        row.put("status", "voided");
        row.put("reason", p.get("reason"));
      }
      if (action.equals("advance")) row.put("status", p.get("status"));
      return new LinkedHashMap<>(row);
    }
    if (op.platform().equals("wecom")) {
      if (key.contains("messages"))
        return JsonUtils.obj(
            "msgid",
            "mock-" + JsonUtils.random(8),
            "invaliduser",
            "",
            "invalidparty",
            "",
            "invalidtag",
            "",
            "mock",
            true);
      if (key.equals("wecom.auth.access_token"))
        return JsonUtils.obj("access_token", "mock-access-token", "expires_in", 7200, "mock", true);
      if (key.contains("calendars") || key.contains("schedules")) {
        boolean cal = key.contains("calendars");
        var records = cal ? calendars : schedules;
        String idField = cal ? "cal_id" : "schedule_id";
        String id = JsonUtils.str(p, idField);
        if (action.equals("create")) {
          id = "mock-" + JsonUtils.random(8);
          Map<String, Object> row = new LinkedHashMap<>(p);
          row.put(idField, id);
          records.put(system + ":" + id, row);
          return JsonUtils.obj(idField, id, "mock", true);
        }
        if (action.equals("get_many"))
          return JsonUtils.obj(
              cal ? "calendars" : "schedules",
              JsonUtils.list(p.get(cal ? "cal_id_list" : "schedule_id_list")).stream()
                  .map(x -> records.getOrDefault(system + ":" + x, JsonUtils.obj(idField, x)))
                  .toList(),
              "mock",
              true);
        if (action.startsWith("list"))
          return JsonUtils.obj(
              "items",
              records.values().stream().toList(),
              "schedules",
              records.values().stream().toList(),
              "total",
              records.size(),
              "mock",
              true);
        if (action.equals("delete")) records.remove(system + ":" + id);
        if (action.equals("update"))
          records.computeIfPresent(
              system + ":" + id,
              (k, v) -> {
                v.putAll(p);
                return v;
              });
        return JsonUtils.obj("ok", true, "mock", true);
      }
      if (key.equals("wecom.users.get"))
        return JsonUtils.obj(
            "userid", p.get("userid"), "name", "演示员工", "department", List.of(1), "status", 1);
      if (key.equals("wecom.users.resolve_code"))
        return JsonUtils.obj("userid", "demo-user", "mock", true);
      if (key.equals("wecom.departments.list"))
        return JsonUtils.obj(
            "departments",
            List.of(JsonUtils.obj("id", 1, "name", "畅韵科技", "parentid", 0)),
            "total",
            1);
      if (key.equals("wecom.directory.list"))
        return JsonUtils.obj(
            "departments",
            List.of(JsonUtils.obj("id", 1, "name", "畅韵科技", "parentid", 0)),
            "users",
            List.of(JsonUtils.obj("userid", "demo-user", "name", "演示员工", "department", List.of(1))),
            "total_users",
            1);
      if (key.equals("wecom.users.list"))
        return JsonUtils.obj(
            "users",
            List.of(JsonUtils.obj("userid", "demo-user", "name", "演示员工", "department", List.of(1))),
            "total",
            1);
      return JsonUtils.obj("ok", true, "mock", true);
    }
    if (key.equals("gmail.profile.get"))
      return JsonUtils.obj(
          "emailAddress",
          JsonUtils.str(p, "account", "mock@example.invalid"),
          "messagesTotal",
          0,
          "threadsTotal",
          0);
    if (op.platform().equals("gmail"))
      return switch (action) {
        case "list" ->
            key.contains("labels")
                ? JsonUtils.obj("labels", List.of(JsonUtils.obj("id", "INBOX", "name", "INBOX")))
                : gmailMessagesList(p);
        case "get" -> gmailMessagesGet(p);
        case "send" ->
            JsonUtils.obj(
                "id",
                "mock-" + JsonUtils.random(8),
                "threadId",
                "mock-thread",
                "labelIds",
                List.of("SENT"));
        case "restore_from_spam" ->
            JsonUtils.obj("id", p.get("message_id"), "labelIds", List.of("INBOX"));
        default ->
            JsonUtils.obj(
                "email",
                JsonUtils.str(p, "account", "mock@example.invalid"),
                "emailAddress",
                JsonUtils.str(p, "account", "mock@example.invalid"),
                "messagesTotal",
                0);
      };
    if (op.platform().equals("gdrive"))
      return switch (action) {
        case "list" -> JsonUtils.obj("files", List.of(), "nextPageToken", null);
        case "get" ->
            JsonUtils.obj("id", p.get("file_id"), "name", "模拟文件.txt", "mimeType", "text/plain");
        default ->
            JsonUtils.obj(
                "id",
                "mock-" + JsonUtils.random(8),
                "name",
                p.get("name"),
                "webViewLink",
                "https://example.invalid/mock");
      };
    if (op.platform().equals("gcs"))
      return action.equals("list")
          ? JsonUtils.obj("items", List.of(), "nextPageToken", null)
          : action.equals("delete")
              ? JsonUtils.obj("deleted", true)
              : JsonUtils.obj(
                  "name",
                  p.get("name"),
                  "bucket",
                  p.get("bucket"),
                  "size",
                  0,
                  "contentType",
                  "application/octet-stream");
    if (op.platform().equals("nas")) {
      if (action.equals("presign_get"))
        return JsonUtils.obj(
            "items",
            JsonUtils.list(p.get("keys")).stream()
                .map(k -> JsonUtils.obj("key", k, "url", "https://example.invalid/mock/" + k))
                .toList(),
            "expires_in",
            p.get("expires"));
      if (action.equals("presign_put"))
        return JsonUtils.obj(
            "key",
            p.get("key"),
            "upload_url",
            "https://example.invalid/mock/upload",
            "method",
            "PUT",
            "headers",
            JsonUtils.obj("Content-Type", p.get("content_type")),
            "expires_in",
            p.get("expires"));
      if (key.contains("buckets") && action.equals("list"))
        return JsonUtils.obj(
            "buckets", List.of(JsonUtils.obj("name", "mock-bucket", "creation_date", now)));
      if (action.equals("list"))
        return JsonUtils.obj(
            "items",
            List.of(),
            "prefixes",
            List.of(),
            "is_truncated",
            false,
            "next_continuation_token",
            null);
      if (action.equals("stat"))
        return JsonUtils.obj(
            "key",
            p.get("key"),
            "size",
            128,
            "content_type",
            "text/plain",
            "etag",
            "mock-etag",
            "last_modified",
            now);
      if (action.equals("read_head"))
        return JsonUtils.obj(
            "key",
            p.get("key"),
            "content_base64",
            Base64.getEncoder()
                .encodeToString("mock file".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "content_type",
            "text/plain",
            "bytes_read",
            9);
      return JsonUtils.obj("ok", true, "deleted", action.startsWith("delete"), "mock", true);
    }
    if (op.platform().equals("shopify")) {
      if (action.equals("get"))
        return JsonUtils.obj(
            key.contains("orders") ? "order" : "product",
            JsonUtils.obj("id", p.getOrDefault("order_id", p.get("product_id")), "name", "模拟记录"));
      if (action.equals("list"))
        return JsonUtils.obj(
            key.contains("orders")
                ? "orders"
                : key.contains("refunds")
                    ? "refunds"
                    : key.contains("returns") ? "returns" : "products",
            List.of(),
            "has_next_page",
            false,
            "end_cursor",
            null);
      return JsonUtils.obj("ok", true, "mock", true);
    }
    if (op.platform().equals("winit")) {
      if (key.equals("winit.warehouses.list")) return JsonUtils.obj("warehouseList", List.of());
      if (key.equals("winit.inbound.tracking")) return JsonUtils.obj("trackingList", List.of());
      if (key.equals("winit.outbound.tracking")) return JsonUtils.obj("raw", List.of());
      return JsonUtils.obj(
          "list", List.of(), "pageParams", JsonUtils.obj("totalCount", 0), "total", 0);
    }
    if (op.platform().equals("quickcep")) {
      if (key.equals("quickcep.sessions.list"))
        return JsonUtils.obj("ids", List.of(), "valid_ids", List.of(), "page_no", p.get("page_no"));
      if (action.equals("get")) return JsonUtils.obj("session", Map.of());
      if (key.contains("messages.list"))
        return JsonUtils.obj(
            "messages", List.of(), "total", 0, "page", p.get("page"), "total_page", 1);
      if (key.contains("staff")) return JsonUtils.obj("staff", List.of());
      if (key.contains("statistics")) return JsonUtils.obj("items", List.of());
      return JsonUtils.obj("result", Map.of(), "records", List.of(), "total", 0);
    }
    if (op.platform().equals("lingxing"))
      return JsonUtils.obj("items", List.of(), "total", 0, "data", List.of());
    // Mabang and the read-only CS sources never invent business rows: mock mode answers with the
    // same empty page shape the real adapter returns when the upstream genuinely has nothing.
    if (op.platform().equals("mabang"))
      return op.key().equals("mabang.orders.track")
          ? JsonUtils.obj("track_infos", List.of(), "count", 0)
          : JsonUtils.obj("orders", List.of(), "total", 0, "has_next", false, "next_cursor", null);
    if (Set.of("judgeme", "trustpilot", "paypal").contains(op.platform()))
      return JsonUtils.obj(
          "items", List.of(), "has_more", false, "cursor", JsonUtils.obj("page", 1));
    if (op.platform().equals("stripe"))
      return JsonUtils.obj(
          "items", List.of(), "has_more", false, "cursor", JsonUtils.obj("starting_after", ""));
    if (op.platform().equals("rd"))
      return JsonUtils.obj("items", List.of(), "has_more", false, "cursor", Map.of());
    throw GatewayException.config("没有模拟适配器: " + op.platform());
  }
}

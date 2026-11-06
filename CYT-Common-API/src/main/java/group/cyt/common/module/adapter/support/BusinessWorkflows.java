package group.cyt.common.module.adapter.support;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.*;
import group.cyt.common.module.adapter.wecom.approval.ApprovalFormCodec;
import group.cyt.common.module.grant.service.QuotaService;
import group.cyt.common.module.registry.model.OperationDefinition;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Curated read-only workflows. A caller can never choose an arbitrary underlying operation. */
@Service
public class BusinessWorkflows {
  public record Spec(String source, String kind, String size, String cursor, String items) {}

  public static final Map<String, Spec> SPECS =
      Map.ofEntries(
          Map.entry(
              "docusign.envelopes.collect",
              new Spec(
                  "docusign.envelopes.list", "offset", "page_size", "start_position", "items")),
          Map.entry(
              "docusign.templates.collect",
              new Spec(
                  "docusign.templates.list", "offset", "page_size", "start_position", "items")),
          Map.entry(
              "quickcep.messages.collect",
              new Spec("quickcep.messages.list", "page", "page_size", "page", "messages")),
          Map.entry(
              "lingxing.orders.collect",
              new Spec("lingxing.orders.list", "total_offset", "length", "offset", "items")),
          Map.entry(
              "lingxing.product.collect",
              new Spec("lingxing.product.list", "total_offset", "length", "offset", "items")),
          Map.entry(
              "gmail.messages.search",
              new Spec("gmail.messages.list", "gmail", "max_results", "page_token", "messages")),
          Map.entry(
              "gdrive.files.search",
              new Spec("gdrive.files.list", "google", "page_size", "page_token", "files")),
          Map.entry(
              "gcs.objects.collect",
              new Spec("gcs.objects.list", "google", "page_size", "page_token", "items")),
          Map.entry(
              "nas.objects.collect",
              new Spec("nas.objects.list", "s3", "max_keys", "continuation_token", "items")),
          Map.entry(
              "shopify.orders.collect",
              new Spec("shopify.orders.list", "graphql", "limit", "after", "items")),
          Map.entry(
              "shopify.products.collect",
              new Spec("shopify.products.list", "graphql", "limit", "after", "items")),
          Map.entry(
              "mabang.orders.collect",
              new Spec("mabang.orders.list", "mabang", "maxRows", "cursor", "orders")),
          Map.entry(
              "judgeme.reviews.collect",
              new Spec("judgeme.reviews.list", "cursor", "limit", "cursor", "items")),
          Map.entry(
              "trustpilot.reviews.collect",
              new Spec("trustpilot.reviews.list", "cursor", "limit", "cursor", "items")),
          Map.entry(
              "stripe.disputes.collect",
              new Spec("stripe.disputes.list", "cursor", "limit", "cursor", "items")),
          Map.entry(
              "paypal.disputes.collect",
              new Spec("paypal.disputes.list", "cursor", "limit", "cursor", "items")),
          Map.entry(
              "rd.products.collect",
              new Spec("rd.products.list", "cursor", "limit", "cursor", "items")));
  private final List<PlatformAdapter> adapters;
  private final MockAdapter mocks;
  private final OperationRegistry registry;
  private final CursorCodec cursors;
  private final QuotaService quota;
  private final GatewaySettings settings;

  public BusinessWorkflows(
      List<PlatformAdapter> adapters,
      MockAdapter mocks,
      OperationRegistry registry,
      CursorCodec cursors,
      QuotaService quota,
      GatewaySettings settings) {
    this.adapters = adapters;
    this.mocks = mocks;
    this.registry = registry;
    this.cursors = cursors;
    this.quota = quota;
    this.settings = settings;
  }

  public boolean supports(String key) {
    return SPECS.containsKey(key);
  }

  public Object invoke(
      OperationDefinition operation, Map<String, Object> p, String system, boolean mock) {
    var spec = SPECS.get(operation.key());
    if (spec == null) throw GatewayException.invalid("未登记的业务工作流");
    var source = registry.get(spec.source());
    if (!source.readOnly()) throw GatewayException.config("采集工作流只允许读取型源操作");
    var filter = new LinkedHashMap<>(p);
    for (String field :
        List.of(
            "page_size",
            "max_items",
            "max_pages",
            "resume_cursor",
            "sender",
            "subject",
            "since",
            "before",
            "unread",
            "has_attachment",
            "include_details",
            "include_body",
            "name_contains",
            "parent_id",
            "mime_type",
            "modified_since")) filter.remove(field);
    filter.remove(spec.size());
    filter.remove(spec.cursor());
    if (operation.key().equals("gmail.messages.search")) filter.put("query", gmailQuery(p));
    if (operation.key().equals("gdrive.files.search")) {
      filter.put("query", driveQuery(p));
      String fields = JsonUtils.str(p, "fields");
      if (!fields.isBlank() && !fields.equals("*") && !fields.contains("nextPageToken"))
        filter.put("fields", "nextPageToken,incompleteSearch," + fields);
    }
    String scope = settings.current() + ":" + mock + ":" + system + ":" + operation.key();
    var state = cursors.decode(scope, JsonUtils.str(p, "resume_cursor"));
    if (source.key().equals("docusign.envelopes.list")
        && JsonUtils.str(filter, "from_date").isBlank())
      filter.put(
          "from_date",
          state.getOrDefault(
              "from_date",
              java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(30).toString()));
    if (source.key().equals("docusign.envelopes.list")
        && JsonUtils.str(filter, "to_date").isBlank())
      filter.put("to_date", state.getOrDefault("to_date", Instant.now().toString()));
    String fingerprint = JsonUtils.sha(JsonUtils.write(JsonUtils.sorted(filter)));
    if (!state.isEmpty() && !fingerprint.equals(state.get("query")))
      throw GatewayException.invalid("续传游标与当前筛选条件不匹配");
    Object upstream = state.get("upstream");
    int size = JsonUtils.integer(p.get("page_size"), 50),
        max = JsonUtils.integer(p.get("max_items"), 100),
        maxPages = JsonUtils.integer(p.get("max_pages"), 10);
    int nativeLimit = maxSchema(source.schema().path("properties").path(spec.size()), 1000);
    size = Math.min(size, nativeLimit);
    if (state.containsKey("page_size") && JsonUtils.integer(state.get("page_size"), size) != size)
      throw GatewayException.invalid("续传时请保持 page_size 不变");
    int offset = JsonUtils.integer(state.get("offset"), 0);
    String pageHash = JsonUtils.str(state, "page_hash");
    var items = new ArrayList<Object>();
    var folders = new LinkedHashSet<Object>();
    var errors = new ArrayList<Object>();
    Set<String> seenItems = new HashSet<>(), visited = new HashSet<>();
    int pages = 0;
    boolean more = true, incomplete = false;
    while (more && pages < maxPages && items.size() + folders.size() < max) {
      String marker = JsonUtils.write(upstream);
      if (!visited.add(marker))
        throw new GatewayException(502, "upstream_error", "上游分页游标重复，已停止采集", false);
      var params = new LinkedHashMap<>(filter);
      // Numbered providers compute offsets from page_size. Shrinking the last page skips/repeats
      // rows.
      int requested = size;
      params.put(spec.size(), requested);
      if (upstream != null) params.put(spec.cursor(), upstream);
      var page = JsonUtils.map(call(source, params, system, mock));
      List<Object> rows = JsonUtils.list(page.get(spec.items()));
      if (mock && spec.kind().equals("graphql") && !page.containsKey("items"))
        rows = JsonUtils.list(page.get(source.key().contains("orders") ? "orders" : "products"));
      List<Object> prefixes = JsonUtils.list(page.get("prefixes"));
      if (rows.size() + prefixes.size() > requested)
        throw new GatewayException(
            502, "upstream_error", "上游响应超出固定 page_size，已停止采集；没有静默丢弃记录", false);
      pages++;
      incomplete |= JsonUtils.bool(page.get("incompleteSearch"));
      errors.addAll(JsonUtils.list(page.get("errors")));
      var entries = new ArrayList<>(rows);
      entries.addAll(prefixes);
      String currentHash =
          JsonUtils.sha(
              JsonUtils.write(entries.stream().map(BusinessWorkflows::identity).toList()));
      if (offset > entries.size() || offset > 0 && !currentHash.equals(pageHash))
        throw new GatewayException(409, "cursor_stale", "续传页的记录已变化，请从头采集并按记录标识去重", false);
      int consumed = offset;
      while (consumed < entries.size() && items.size() + folders.size() < max) {
        Object row = entries.get(consumed);
        if (consumed < rows.size()) {
          if (seenItems.add(identity(row))) items.add(row);
        } else folders.add(row);
        consumed++;
      }
      if (consumed < entries.size()) {
        // Resume this exact page, with a hash to detect upstream reorder. No record content in
        // cursor.
        offset = consumed;
        pageHash = currentHash;
        more = true;
        break;
      }
      offset = 0;
      pageHash = "";
      switch (spec.kind()) {
        case "offset" -> {
          more = JsonUtils.bool(page.get("has_more"));
          upstream = page.get("next_start_position");
        }
        case "page" -> {
          int currentPage =
              JsonUtils.integer(page.get("page"), JsonUtils.integer(params.get(spec.cursor()), 1));
          more = currentPage < JsonUtils.integer(page.get("total_page"), currentPage);
          upstream = more ? currentPage + 1 : null;
        }
        case "total_offset" -> {
          int nextOffset = JsonUtils.integer(params.get(spec.cursor()), 0) + rows.size();
          more = nextOffset < JsonUtils.integer(page.get("total"), nextOffset);
          if (more && rows.isEmpty())
            throw new GatewayException(502, "upstream_error", "上游总量显示仍有记录但返回空页，无法安全推进偏移", false);
          upstream = more ? nextOffset : null;
        }
        case "gmail", "google" -> {
          upstream = page.get("nextPageToken");
          more = upstream != null && !upstream.toString().isBlank();
        }
        case "graphql" -> {
          var info = JsonUtils.map(page.get("page_info"));
          if (!mock && info.isEmpty())
            throw new GatewayException(502, "upstream_error", "Shopify 响应缺少 page_info", false);
          more = JsonUtils.bool(info.getOrDefault("hasNextPage", page.get("has_next_page")));
          upstream = info.getOrDefault("endCursor", page.get("end_cursor"));
        }
        case "s3" -> {
          more = JsonUtils.bool(page.getOrDefault("truncated", page.get("is_truncated")));
          upstream = page.getOrDefault("next_token", page.get("next_continuation_token"));
        }
        case "mabang" -> {
          more = JsonUtils.bool(page.get("has_next"));
          upstream = page.get("next_cursor");
        }
        default -> {
          more = JsonUtils.bool(page.get("has_more"));
          upstream = page.get("cursor");
        }
      }
      if (more
          && (upstream == null
              || upstream.toString().isBlank()
              || upstream instanceof Map<?, ?> m && m.isEmpty()))
        throw new GatewayException(502, "upstream_error", "上游表示仍有数据但未返回有效游标", false);
    }
    if (operation.key().equals("gmail.messages.search")
        && (JsonUtils.bool(p.getOrDefault("include_details", true))
            || JsonUtils.bool(p.get("include_body")))) {
      var enriched = new ArrayList<Object>();
      for (Object item : items) {
        var stub = JsonUtils.map(item);
        String id = JsonUtils.str(stub, "id");
        try {
          var raw =
              JsonUtils.map(
                  call(
                      registry.get("gmail.messages.get"),
                      JsonUtils.obj(
                          "account",
                          p.getOrDefault("account", ""),
                          "message_id",
                          id,
                          "format",
                          JsonUtils.bool(p.get("include_body")) ? "full" : "metadata",
                          "metadata_headers",
                          List.of("From", "To", "Subject", "Date")),
                      system,
                      mock));
          enriched.add(mail(raw, JsonUtils.bool(p.get("include_body"))));
        } catch (GatewayException e) {
          var failed = new LinkedHashMap<>(stub);
          failed.put("detail_error", e.code == null ? e.type : e.code);
          enriched.add(failed);
          errors.add(JsonUtils.obj("id", id, "code", e.code == null ? e.type : e.code));
        }
      }
      items = enriched;
    }
    String next =
        more
            ? cursors.encode(
                scope,
                JsonUtils.obj(
                    "query",
                    fingerprint,
                    "upstream",
                    upstream,
                    "page_size",
                    size,
                    "offset",
                    offset,
                    "page_hash",
                    pageHash,
                    "from_date",
                    filter.get("from_date"),
                    "to_date",
                    filter.get("to_date")))
            : null;
    return JsonUtils.obj(
        "items",
        items,
        "folders",
        new ArrayList<>(folders),
        "errors",
        errors,
        "page",
        JsonUtils.obj("has_more", more, "next_cursor", next, "count", items.size(), "pages", pages),
        "partial",
        more || incomplete || !errors.isEmpty(),
        "incomplete_search",
        incomplete,
        "coverage",
        "root_records",
        "source_operation",
        source.key());
  }

  private Object call(
      OperationDefinition source, Map<String, Object> p, String system, boolean mock) {
    if (Thread.currentThread().isInterrupted())
      throw new GatewayException(503, "interrupted", "请求已中断", false);
    quota.consume("workflow:" + system + ":" + source.key(), source.quota());
    var validated = registry.validate(source, p);
    if (mock) return mocks.invoke(source, validated, system);
    return adapters.stream()
        .filter(a -> a.supports(source.platform()))
        .findFirst()
        .orElseThrow(() -> GatewayException.config("原生适配器未加载"))
        .invoke(source, validated, system);
  }

  private static int maxSchema(com.fasterxml.jackson.databind.JsonNode schema, int fallback) {
    if (schema.has("maximum")) return schema.path("maximum").asInt(fallback);
    for (var branch : schema.path("anyOf"))
      if (branch.has("maximum")) return branch.path("maximum").asInt(fallback);
    return fallback;
  }

  private static String identity(Object row) {
    if (row instanceof Map<?, ?> m)
      for (String key : List.of("id", "source_id", "key", "name", "order_id", "platformOrderId"))
        if (m.get(key) != null)
          return key + ":" + m.get(key) + ":" + Objects.toString(m.get("generation"), "");
    return JsonUtils.sha(JsonUtils.write(JsonUtils.sorted(row)));
  }

  public static String gmailQuery(Map<String, Object> p) {
    var parts = new ArrayList<String>();
    String advanced = JsonUtils.str(p, "query");
    if (!advanced.isBlank()) parts.add("(" + advanced + ")");
    for (String key : List.of("sender", "subject"))
      if (!JsonUtils.str(p, key).isBlank())
        parts.add(
            (key.equals("sender") ? "from" : "subject") + ":" + gmailQuoted(JsonUtils.str(p, key)));
    if (p.get("since") != null) parts.add("after:" + ApprovalFormCodec.epoch(p.get("since")));
    if (p.get("before") != null) parts.add("before:" + ApprovalFormCodec.epoch(p.get("before")));
    if (JsonUtils.bool(p.get("unread"))) parts.add("is:unread");
    if (JsonUtils.bool(p.get("has_attachment"))) parts.add("has:attachment");
    return String.join(" ", parts);
  }

  private static String gmailQuoted(String value) {
    if (value.contains("\r") || value.contains("\n") || value.contains("\""))
      throw GatewayException.invalid("邮件筛选文本不能包含引号或换行，请使用高级 query 表达特殊搜索");
    return "\"" + value + "\"";
  }

  public static String driveQuery(Map<String, Object> p) {
    var parts = new ArrayList<String>();
    String advanced = JsonUtils.str(p, "query");
    if (!advanced.isBlank()) parts.add("(" + advanced + ")");
    else parts.add("trashed = false");
    for (String key : List.of("name_contains", "mime_type", "modified_since"))
      if (!JsonUtils.str(p, key).isBlank()) {
        String value = JsonUtils.str(p, key);
        if (key.equals("modified_since"))
          value = Instant.ofEpochSecond(ApprovalFormCodec.epoch(value)).toString();
        String expression =
            switch (key) {
              case "name_contains" -> "name contains";
              case "mime_type" -> "mimeType =";
              default -> "modifiedTime >";
            };
        parts.add(expression + " '" + driveEscaped(value) + "'");
      }
    if (!JsonUtils.str(p, "parent_id").isBlank())
      parts.add("'" + driveEscaped(JsonUtils.str(p, "parent_id")) + "' in parents");
    return String.join(" and ", parts);
  }

  private static String driveEscaped(String value) {
    return value.replace("\\", "\\\\").replace("'", "\\'");
  }

  private static Map<String, Object> mail(Map<String, Object> raw, boolean includeBody) {
    var payload = JsonUtils.map(raw.get("payload"));
    Map<String, Object> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (Object item : JsonUtils.list(payload.get("headers"))) {
      var h = JsonUtils.map(item);
      headers.put(JsonUtils.str(h, "name"), h.get("value"));
    }
    var result =
        JsonUtils.obj(
            "id",
            raw.get("id"),
            "thread_id",
            raw.get("threadId"),
            "subject",
            headers.get("Subject"),
            "from",
            headers.get("From"),
            "to",
            headers.get("To"),
            "date",
            headers.get("Date"),
            "snippet",
            raw.get("snippet"),
            "labels",
            raw.get("labelIds"));
    if (includeBody) {
      List<String> texts = new ArrayList<>(), html = new ArrayList<>();
      List<Object> attachments = new ArrayList<>();
      mime(payload, texts, html, attachments, 0);
      result.put("body_text", String.join("\n", texts));
      result.put("body_html", String.join("\n", html));
      result.put("attachments", attachments);
    }
    return result;
  }

  private static void mime(
      Map<String, Object> part,
      List<String> text,
      List<String> html,
      List<Object> attachments,
      int depth) {
    if (depth > 20) throw new GatewayException(502, "upstream_error", "邮件 MIME 嵌套层数过多", false);
    var body = JsonUtils.map(part.get("body"));
    String filename = JsonUtils.str(part, "filename"), type = JsonUtils.str(part, "mimeType");
    if (!filename.isBlank() || body.get("attachmentId") != null)
      attachments.add(
          JsonUtils.obj(
              "filename",
              filename,
              "mime_type",
              type,
              "attachment_id",
              body.get("attachmentId"),
              "size",
              body.get("size")));
    else if (body.get("data") != null && (type.equals("text/plain") || type.equals("text/html"))) {
      try {
        String charset = "UTF-8";
        for (Object header : JsonUtils.list(part.get("headers"))) {
          var h = JsonUtils.map(header);
          if (JsonUtils.str(h, "name").equalsIgnoreCase("Content-Type")) {
            var matcher =
                java.util.regex.Pattern.compile("(?i)charset=[\\\"']?([^;\\s\\\"']+)")
                    .matcher(JsonUtils.str(h, "value"));
            if (matcher.find()) charset = matcher.group(1);
          }
        }
        String value =
            new String(
                Base64.getUrlDecoder().decode(body.get("data").toString()),
                java.nio.charset.Charset.forName(charset));
        (type.equals("text/html") ? html : text).add(value);
      } catch (IllegalArgumentException e) {
        throw new GatewayException(502, "upstream_error", "邮件正文编码无效", false);
      }
    }
    for (Object child : JsonUtils.list(part.get("parts")))
      mime(JsonUtils.map(child), text, html, attachments, depth + 1);
  }
}

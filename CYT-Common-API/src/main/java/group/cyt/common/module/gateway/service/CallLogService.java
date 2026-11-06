package group.cyt.common.module.gateway.service;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class CallLogService {
  private static final Logger log = LoggerFactory.getLogger(CallLogService.class);
  private static final Pattern SECRET =
      Pattern.compile(
          "(?i).*(password|secret|token|authorization|api.?key|private.?key|signature|sign|content_base64|body_base64|raw|ciphertext).*");
  private static final Set<String> CONTENT =
      Set.of(
          "body",
          "body_text",
          "body_html",
          "text",
          "html",
          "content",
          "snippet",
          "email",
          "to",
          "cc",
          "bcc",
          "recipients",
          "signers",
          "documents",
          "fields",
          "apply_data",
          "payload",
          "summary_list",
          "comments",
          "timeline",
          "steps",
          "notes",
          "business_forms",
          "pending_approvers",
          "values");
  private final GatewayStore store;
  private final ThreadPoolExecutor writer =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2048),
          Thread.ofPlatform().daemon(true).name("gateway-call-log-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  public CallLogService(GatewayStore store) {
    this.store = store;
  }

  public Object redact(Object value) {
    return redact(value, 0);
  }

  private Object redact(Object value, int depth) {
    if (depth > 8) return "[truncated]";
    if (value instanceof Map<?, ?> m) {
      var out = new LinkedHashMap<String, Object>();
      m.forEach(
          (k, v) ->
              out.put(
                  k.toString(),
                  SECRET.matcher(k.toString()).matches()
                          || CONTENT.contains(k.toString().toLowerCase(Locale.ROOT))
                      ? "[redacted]"
                      : redact(v, depth + 1)));
      return out;
    }
    if (value instanceof List<?> list)
      return list.stream().limit(30).map(x -> redact(x, depth + 1)).toList();
    if (value instanceof String s) {
      if (s.length() > 1000) return s.substring(0, 1000) + "…";
      if (s.startsWith("http://") || s.startsWith("https://")) return s.split("\\?", 2)[0];
      return s;
    }
    return value;
  }

  public void record(
      String trace,
      String system,
      String operation,
      String platform,
      long latency,
      Object request,
      Object response,
      boolean mock,
      String idempotency,
      GatewayException error) {
    try {
      var fields =
          JsonUtils.obj(
              "trace_id",
              trace,
              "system",
              system,
              "operation",
              operation,
              "platform",
              platform,
              "status",
              error == null ? "ok" : "error",
              "http_status",
              error == null ? 200 : error.status,
              "error_type",
              error == null ? null : error.type,
              "error_message",
              error == null ? null : error.type,
              "latency_ms",
              (int) Math.min(Integer.MAX_VALUE, latency),
              "request_summary",
              summary(request),
              "response_summary",
              summary(response),
              "mock",
              mock,
              "idempotency_key",
              idempotency,
              "source",
              "live");
      writer.execute(() -> persist(trace, fields));
    } catch (Exception e) {
      log.warn("Call log persistence failed trace={} type={}", trace, e.getClass().getSimpleName());
    }
  }

  private void persist(String trace, Map<String, Object> fields) {
    try {
      store.insert("gateway_call_log", fields);
    } catch (Exception e) {
      log.warn("Call log persistence failed trace={} type={}", trace, e.getClass().getSimpleName());
    }
  }

  public void flush() {
    try {
      writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("Call logs did not drain", e);
    }
  }

  @PreDestroy
  public void close() {
    writer.shutdown();
    try {
      if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writer.shutdownNow();
    }
  }

  private String summary(Object value) {
    String s = JsonUtils.write(redact(value));
    return s.length() > 16000 ? s.substring(0, 16000) : s;
  }
}

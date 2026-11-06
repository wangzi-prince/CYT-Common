package group.cyt.common.module.gateway.service;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.AdapterDispatcher;
import group.cyt.common.module.grant.service.*;
import group.cyt.common.module.registry.model.OperationDefinition;
import group.cyt.common.module.registry.service.OperationRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** All public, batch and admin-simulation routes use this pipeline. */
@Service
public class GatewayExecutor {
  private final ExecutorService upstreamExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final OperationRegistry registry;
  private final AuthorizationService authorization;
  private final QuotaService quota;
  private final AdapterDispatcher adapters;
  private final GatewayStore store;
  private final CallLogService logs;

  public GatewayExecutor(
      OperationRegistry registry,
      AuthorizationService authorization,
      QuotaService quota,
      AdapterDispatcher adapters,
      GatewayStore store,
      CallLogService logs) {
    this.registry = registry;
    this.authorization = authorization;
    this.quota = quota;
    this.adapters = adapters;
    this.store = store;
    this.logs = logs;
  }

  public Map<String, Object> execute(
      String system,
      String key,
      Map<String, Object> params,
      String idempotencyKey,
      String traceId) {
    String trace =
        traceId == null || traceId.isBlank()
            ? UUID.randomUUID().toString().replace("-", "")
            : traceId;
    if (trace.length() > 40 || !trace.matches("[A-Za-z0-9_.-]+"))
      throw GatewayException.invalid("X-Trace-Id 无效");
    if (idempotencyKey != null && idempotencyKey.length() > 128)
      throw GatewayException.invalid("幂等键过长");
    long start = System.nanoTime();
    String platform = "unknown";
    boolean mock = false;
    Long claim = null;
    Object data = null;
    try {
      var op = registry.get(key);
      key = op.key();
      platform = op.platform();
      mock = adapters.mock(op);
      if (op.tags() != null && op.tags().contains("idempotency_required")) {
        String requestId = JsonUtils.str(params == null ? Map.of() : params, "request_id");
        if (idempotencyKey == null || idempotencyKey.isBlank()) idempotencyKey = requestId;
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128)
          throw GatewayException.invalid("此写操作需要 request_id 或 Idempotency-Key，重试必须使用相同值");
        if (!requestId.isBlank() && !requestId.equals(idempotencyKey))
          throw GatewayException.invalid("request_id 与 Idempotency-Key 必须一致");
      }
      var enabled = store.one("SELECT enabled FROM gateway_system WHERE `key`=?", system);
      if (enabled == null || !JsonUtils.bool(enabled.get("enabled")))
        throw GatewayException.forbidden("系统未注册或已停用");
      String effectiveQuota = authorization.check(system, op);
      quota.consume(system + ":" + key, effectiveQuota);
      for (var p : registry.contract().path("platforms"))
        if (p.path("platform").asText().equals(platform) && !p.path("platform_quota").isNull())
          quota.consume("platform:" + platform, p.path("platform_quota").asText());
      var validated = registry.validate(op, params);
      String hash = JsonUtils.sha(JsonUtils.write(JsonUtils.sorted(validated)));
      if (op.tags() != null && op.tags().contains("business"))
        hash = JsonUtils.sha(hash + ":" + mock + ":" + adapters.environment());
      if (op.idempotent() && idempotencyKey != null && !idempotencyKey.isBlank()) {
        try {
          claim =
              store.insert(
                  "gateway_idempotency",
                  JsonUtils.obj(
                      "system",
                      system,
                      "key",
                      idempotencyKey,
                      "operation",
                      key,
                      "response_json",
                      "{}",
                      "params_hash",
                      hash,
                      "state",
                      "pending"));
        } catch (DuplicateKeyException e) {
          var old =
              store.one(
                  "SELECT * FROM gateway_idempotency WHERE `system`=? AND `key`=?",
                  system,
                  idempotencyKey);
          if (old == null) throw e;
          if (!key.equals(old.get("operation")) || !hash.equals(old.get("params_hash")))
            throw new GatewayException(409, "idempotency_conflict", "同一幂等键对应了不同操作或参数", false);
          if (!JsonUtils.str(old, "state").equals("complete"))
            throw new GatewayException(
                409, "idempotency_in_progress", "幂等请求正在执行或结果待确认，请先查询原调用结果", false);
          data = JsonUtils.parse(JsonUtils.str(old, "response_json"));
          logs.record(
              trace,
              system,
              key,
              platform,
              elapsed(start),
              validated,
              JsonUtils.obj("replayed", true),
              mock,
              idempotencyKey,
              null);
          return JsonUtils.obj(
              "ok",
              true,
              "data",
              data,
              "meta",
              JsonUtils.obj(
                  "trace_id",
                  trace,
                  "latency_ms",
                  elapsed(start),
                  "platform",
                  platform,
                  "mock",
                  mock,
                  "idempotent_replay",
                  true));
        }
      }
      int attempts = Math.max(1, Math.min(5, op.retryMax()));
      // Retrying a write after a timeout can duplicate upstream effects. Only read operations retry
      // automatically.
      if (!op.readOnly()) attempts = 1;
      for (int attempt = 0; attempt < attempts; attempt++) {
        try {
          data = invokeWithTimeout(op, validated, system);
          break;
        } catch (GatewayException e) {
          if (!e.retryable || attempt == attempts - 1) throw e;
          try {
            Thread.sleep(300L * (1L << attempt));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GatewayException(503, "interrupted", "请求已中断", true);
          }
        }
      }
      if (claim != null)
        store.update(
            "UPDATE gateway_idempotency SET response_json=?,state='complete' WHERE id=?",
            JsonUtils.write(data),
            claim);
      logs.record(
          trace,
          system,
          key,
          platform,
          elapsed(start),
          validated,
          data,
          mock,
          idempotencyKey,
          null);
      return JsonUtils.obj(
          "ok",
          true,
          "data",
          data,
          "meta",
          JsonUtils.obj(
              "trace_id",
              trace,
              "latency_ms",
              elapsed(start),
              "platform",
              platform,
              "quota",
              effectiveQuota,
              "mock",
              mock,
              "model",
              null,
              "cost_usd",
              null));
    } catch (GatewayException error) {
      error.trace = trace;
      if (claim != null) markFailed(claim);
      logs.record(
          trace, system, key, platform, elapsed(start), params, null, mock, idempotencyKey, error);
      throw error;
    } catch (RuntimeException error) {
      if (claim != null) markFailed(claim);
      var ex = new GatewayException(500, "internal_error", "网关执行失败", false);
      ex.trace = trace;
      logs.record(
          trace, system, key, platform, elapsed(start), params, null, mock, idempotencyKey, ex);
      throw ex;
    }
  }

  private Object invokeWithTimeout(
      OperationDefinition op, Map<String, Object> params, String system) {
    Future<Object> future = upstreamExecutor.submit(() -> adapters.invoke(op, params, system));
    try {
      return future.get(Math.max(1, (long) (op.timeoutSeconds() * 1000)), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new GatewayException(504, "upstream_timeout", "操作执行超时", true);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new GatewayException(503, "interrupted", "请求已中断", true);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof GatewayException gateway) throw gateway;
      if (e.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new GatewayException(502, "upstream_error", "上游执行失败", false);
    }
  }

  @PreDestroy
  public void close() {
    upstreamExecutor.shutdownNow();
  }

  private void markFailed(long id) {
    try {
      store.update(
          "UPDATE gateway_idempotency SET state='uncertain' WHERE id=? AND state='pending'", id);
    } catch (Exception ignored) {
      /* pending also blocks a duplicate side effect */
    }
  }

  public Object batch(String system, List<Map<String, Object>> calls, boolean stopOnError) {
    if (calls == null || calls.isEmpty() || calls.size() > 100)
      throw GatewayException.invalid("批量调用必须包含 1–100 项");
    String batchId = JsonUtils.random(24);
    List<Map<String, Object>> results = new ArrayList<>();
    int succeeded = 0;
    for (var item : calls) {
      String key = JsonUtils.str(item, "operation");
      try {
        var r =
            execute(
                system,
                key,
                JsonUtils.map(item.get("params")),
                JsonUtils.str(item, "idempotency_key"),
                null);
        r.put("operation", key);
        results.add(r);
        succeeded++;
      } catch (GatewayException e) {
        var r = e.payload();
        r.put("operation", key);
        results.add(r);
        if (stopOnError) break;
      }
    }
    return JsonUtils.obj(
        "ok",
        true,
        "data",
        JsonUtils.obj(
            "batch_id",
            batchId,
            "total",
            calls.size(),
            "succeeded",
            succeeded,
            "failed",
            results.size() - succeeded,
            "results",
            results),
        "meta",
        JsonUtils.obj("trace_id", batchId, "batch", true));
  }

  private static long elapsed(long start) {
    return Duration.ofNanos(System.nanoTime() - start).toMillis();
  }
}

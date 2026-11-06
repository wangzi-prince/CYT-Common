package group.cyt.common.module.monitor.service;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MonitoringService {
  private final GatewayStore store;

  public MonitoringService(GatewayStore store) {
    this.store = store;
  }

  public record Filter(String sql, List<Object> args) {}

  public Filter callFilter(Map<String, String> query) {
    StringBuilder sql = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    for (String name : List.of("system", "platform", "operation", "status"))
      if (!query.getOrDefault(name, "").isBlank()) {
        sql.append(" AND `").append(name).append("`=?");
        args.add(query.get(name));
      }
    if (!query.getOrDefault("q", "").isBlank()) {
      sql.append(" AND (operation LIKE ? OR trace_id LIKE ? OR system LIKE ?)");
      for (int i = 0; i < 3; i++) args.add("%" + query.get("q") + "%");
    }
    Instant end =
        query.getOrDefault("end", "").isBlank()
            ? Instant.now()
            : GatewayStore.timestamp(query.get("end")).toInstant();
    Instant start =
        query.getOrDefault("start", "").isBlank()
            ? end.minus(bounded(query.get("hours"), 168, 1, 2160), ChronoUnit.HOURS)
            : GatewayStore.timestamp(query.get("start")).toInstant();
    if (end.isBefore(start) || Duration.between(start, end).toDays() > 90)
      throw GatewayException.invalid("查询时间范围无效，最多 90 天");
    sql.append(" AND called_at>=? AND called_at<=?");
    args.add(Timestamp.from(start));
    args.add(Timestamp.from(end));
    return new Filter(sql.toString(), args);
  }

  public Object calls(Map<String, String> query) {
    Filter f = callFilter(query);
    int page = bounded(query.get("page"), 1, 1, 1000000),
        size = bounded(query.get("page_size"), 20, 1, 200);
    List<Object> a = new ArrayList<>(f.args());
    a.add(size);
    a.add((page - 1L) * size);
    return JsonUtils.obj(
        "total",
        store.count("SELECT COUNT(*) FROM gateway_call_log" + f.sql(), f.args().toArray()),
        "page",
        page,
        "page_size",
        size,
        "items",
        store.rows(
            "SELECT * FROM gateway_call_log"
                + f.sql()
                + " ORDER BY called_at DESC,id DESC LIMIT ? OFFSET ?",
            a.toArray()));
  }

  public Object detail(String trace) {
    return JsonUtils.obj(
        "trace_id",
        trace,
        "items",
        store.rows("SELECT * FROM gateway_call_log WHERE trace_id=? ORDER BY id", trace));
  }

  public Object overview(int hours) {
    hours = bounded(Integer.toString(hours), 24, 1, 2160);
    Instant now = Instant.now(), start = now.minus(hours, ChronoUnit.HOURS);
    Timestamp since = Timestamp.from(start);
    var summary =
        store.one(
            "SELECT COUNT(*) AS total_calls,COALESCE(SUM(CASE WHEN status<>'ok' THEN 1 ELSE 0 END),0) AS error_count,COALESCE(SUM(cost_usd),0) AS cost_usd FROM gateway_call_log WHERE called_at>=?",
            since);
    long total = ((Number) summary.get("total_calls")).longValue(),
        errors = ((Number) summary.get("error_count")).longValue();
    summary.put("success_rate", total == 0 ? 1.0 : (double) (total - errors) / total);
    summary.put("p95_latency_ms", percentile(since, total, .95));
    summary.put("p50_latency_ms", percentile(since, total, .50));
    summary.put(
        "seed_calls",
        store.count(
            "SELECT COUNT(*) FROM gateway_call_log WHERE called_at>=? AND source='seed'", since));
    boolean daily = hours > 48;
    String fmt = daily ? "%Y-%m-%d" : "%Y-%m-%d %H:00";
    var counts =
        store.rows(
            "SELECT DATE_FORMAT(called_at,?) AS ts,COUNT(*) AS calls,SUM(CASE WHEN status<>'ok' THEN 1 ELSE 0 END) AS errors FROM gateway_call_log WHERE called_at>=? GROUP BY ts ORDER BY ts",
            fmt,
            since);
    Map<String, Map<String, Object>> series = new LinkedHashMap<>();
    DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern(daily ? "yyyy-MM-dd" : "yyyy-MM-dd HH:00")
            .withZone(ZoneOffset.UTC);
    Instant cursor = start.truncatedTo(daily ? ChronoUnit.DAYS : ChronoUnit.HOURS);
    while (!cursor.isAfter(now)) {
      String time = formatter.format(cursor);
      series.put(time, JsonUtils.obj("ts", time, "calls", 0, "errors", 0));
      cursor = cursor.plus(1, daily ? ChronoUnit.DAYS : ChronoUnit.HOURS);
    }
    for (var row : counts) series.put(JsonUtils.str(row, "ts"), row);
    return JsonUtils.obj(
        "range_hours",
        hours,
        "generated_at",
        now.toString(),
        "overview",
        summary,
        "series",
        new ArrayList<>(series.values()),
        "by_platform",
        group("platform", since),
        "by_operation",
        group("operation", since),
        "by_system",
        group("system", since),
        "error_types",
        store.rows(
            "SELECT error_type,COUNT(*) AS count FROM gateway_call_log WHERE called_at>=? AND error_type IS NOT NULL GROUP BY error_type",
            since),
        "recent",
        store.rows(
            "SELECT * FROM gateway_call_log WHERE called_at>=? ORDER BY called_at DESC,id DESC LIMIT 8",
            since));
  }

  private int percentile(Timestamp since, long count, double p) {
    if (count == 0) return 0;
    var row =
        store.one(
            "SELECT latency_ms FROM gateway_call_log WHERE called_at>=? ORDER BY latency_ms LIMIT 1 OFFSET ?",
            since,
            Math.min(count - 1, Math.max(0, Math.round(p * (count - 1)))));
    return JsonUtils.integer(row.get("latency_ms"), 0);
  }

  private Object group(String field, Timestamp since) {
    return store.rows(
        "SELECT `"
            + field
            + "`,COUNT(*) AS calls,SUM(CASE WHEN status<>'ok' THEN 1 ELSE 0 END) AS errors,AVG(latency_ms) AS avg_latency_ms,AVG(CASE WHEN status='ok' THEN 1.0 ELSE 0 END) AS success_rate,COALESCE(SUM(cost_usd),0) AS cost_usd FROM gateway_call_log WHERE called_at>=? GROUP BY `"
            + field
            + "` ORDER BY calls DESC LIMIT 100",
        since);
  }

  public Object audit(Map<String, String> query) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> a = new ArrayList<>();
    if (!query.getOrDefault("actor", "").isBlank()) {
      where.append(" AND actor=?");
      a.add(query.get("actor"));
    }
    if (!query.getOrDefault("q", "").isBlank()) {
      where.append(" AND (action LIKE ? OR target LIKE ?)");
      a.add("%" + query.get("q") + "%");
      a.add("%" + query.get("q") + "%");
    }
    if (!query.getOrDefault("start", "").isBlank()) {
      where.append(" AND created_at>=?");
      a.add(GatewayStore.timestamp(query.get("start")));
    }
    if (!query.getOrDefault("end", "").isBlank()) {
      where.append(" AND created_at<=?");
      a.add(GatewayStore.timestamp(query.get("end")));
    }
    long total = store.count("SELECT COUNT(*) FROM gateway_admin_audit" + where, a.toArray());
    int page = bounded(query.get("page"), 1, 1, 1000000),
        size = bounded(query.get("page_size"), 20, 1, 200);
    a.add(size);
    a.add((page - 1L) * size);
    return JsonUtils.obj(
        "total",
        total,
        "page",
        page,
        "page_size",
        size,
        "items",
        store.rows(
            "SELECT * FROM gateway_admin_audit"
                + where
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            a.toArray()));
  }

  public Object facets(Map<String, String> query) {
    var f = callFilter(query);
    Map<String, Object> result = new LinkedHashMap<>();
    for (String field : List.of("system", "platform", "operation"))
      result.put(
          field + "s",
          store.rows(
              "SELECT `"
                  + field
                  + "` AS `key`,COUNT(*) AS calls,SUM(CASE WHEN status<>'ok' THEN 1 ELSE 0 END) AS errors FROM gateway_call_log"
                  + f.sql()
                  + " GROUP BY `"
                  + field
                  + "` ORDER BY calls DESC LIMIT 1000",
              f.args().toArray()));
    return result;
  }

  public String csv(Map<String, String> query) {
    var f = callFilter(query);
    long count = store.count("SELECT COUNT(*) FROM gateway_call_log" + f.sql(), f.args().toArray());
    if (count > 10000) throw GatewayException.invalid("导出最多 10000 条，请缩小查询范围");
    List<String> fields =
        List.of(
            "trace_id",
            "called_at",
            "system",
            "platform",
            "operation",
            "status",
            "http_status",
            "latency_ms",
            "mock");
    StringBuilder csv = new StringBuilder("\uFEFF").append(String.join(",", fields)).append("\r\n");
    for (var row :
        store.rows(
            "SELECT * FROM gateway_call_log"
                + f.sql()
                + " ORDER BY called_at DESC,id DESC LIMIT 10000",
            f.args().toArray())) {
      csv.append(String.join(",", fields.stream().map(k -> cell(row.get(k))).toList()))
          .append("\r\n");
    }
    return csv.toString();
  }

  public static String cell(Object value) {
    String s = Objects.toString(value, "");
    if (s.matches("^[\\s]*[=+@-].*") || s.startsWith("\t") || s.startsWith("\r")) s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  public static int bounded(String value, int fallback, int min, int max) {
    int n = fallback;
    if (value != null && !value.isBlank())
      try {
        n = Integer.parseInt(value);
      } catch (Exception e) {
        throw GatewayException.invalid("分页或时间范围参数必须是整数");
      }
    if (n < min || n > max) throw GatewayException.invalid("参数超出允许范围: " + min + "–" + max);
    return n;
  }
}

package group.cyt.common.core.persistence;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class GatewayStore {
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;

  public GatewayStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    tx = new TransactionTemplate(manager);
  }

  public <T> T atomic(Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          var md = rs.getMetaData();
          for (int i = 1; i <= md.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof Timestamp)
              v =
                  rs.getTimestamp(i, Calendar.getInstance(TimeZone.getTimeZone("UTC")))
                      .toInstant()
                      .toString();
            else if (v instanceof LocalDateTime time) v = time.toInstant(ZoneOffset.UTC).toString();
            m.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), v);
          }
          return m;
        },
        args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  public long insert(String table, Map<String, Object> fields) {
    identifier(table);
    fields.keySet().forEach(GatewayStore::identifier);
    String sql =
        "INSERT INTO `"
            + table
            + "` ("
            + fields.keySet().stream()
                .map(k -> "`" + k + "`")
                .reduce((a, b) -> a + "," + b)
                .orElseThrow()
            + ") VALUES ("
            + String.join(",", Collections.nCopies(fields.size(), "?"))
            + ")";
    KeyHolder kh = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          int i = 1;
          for (Object v : fields.values()) ps.setObject(i++, v);
          return ps;
        },
        kh);
    Number key = kh.getKey();
    return key == null ? 0 : key.longValue();
  }

  public void edit(String table, long id, Map<String, Object> fields) {
    identifier(table);
    fields.keySet().forEach(GatewayStore::identifier);
    if (fields.isEmpty()) return;
    List<Object> a = new ArrayList<>(fields.values());
    a.add(id);
    update(
        "UPDATE `"
            + table
            + "` SET "
            + fields.keySet().stream()
                .map(k -> "`" + k + "`=?")
                .reduce((x, y) -> x + "," + y)
                .orElseThrow()
            + " WHERE id=?",
        a.toArray());
  }

  public void audit(String actor, String action, String target, Object detail) {
    insert(
        "gateway_admin_audit",
        JsonUtils.obj(
            "actor", actor, "action", action, "target", target, "detail", JsonUtils.write(detail)));
  }

  public static Timestamp now() {
    return Timestamp.from(Instant.now());
  }

  public static Timestamp timestamp(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    try {
      return Timestamp.from(Instant.parse(value.toString()));
    } catch (Exception e) {
      throw GatewayException.invalid("时间必须为带时区的 ISO 8601 格式");
    }
  }

  private static void identifier(String s) {
    if (!s.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid SQL identifier");
  }
}

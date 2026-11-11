package group.cyt.common.module.adapter.discord;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Commit the inbox and resume position in one transaction while holding the listener lease. */
@Service
public class DiscordEvents {
  static final Set<String> TYPES =
      Set.of(
          "MESSAGE_CREATE",
          "MESSAGE_UPDATE",
          "MESSAGE_DELETE",
          "MESSAGE_DELETE_BULK",
          "THREAD_CREATE",
          "THREAD_UPDATE",
          "THREAD_DELETE");
  private final GatewayStore store;
  private final GatewaySettings settings;

  public DiscordEvents(GatewayStore store, GatewaySettings settings) {
    this.store = store;
    this.settings = settings;
  }

  public boolean claim(String bot, String owner) {
    try {
      store.update(
          "INSERT INTO gateway_discord_session(env,bot_id,updated_at) VALUES(?,?,?)",
          settings.current(),
          bot,
          GatewayStore.now());
    } catch (DuplicateKeyException ignored) {
    }
    return renew(bot, owner);
  }

  public boolean renew(String bot, String owner) {
    return store.update(
            "UPDATE gateway_discord_session SET lease_owner=?,lease_until=?,updated_at=? WHERE env=? AND bot_id=? AND (lease_until IS NULL OR lease_until<? OR lease_owner=?)",
            owner,
            Timestamp.from(Instant.now().plusSeconds(45)),
            GatewayStore.now(),
            settings.current(),
            bot,
            GatewayStore.now(),
            owner)
        == 1;
  }

  public void release(String bot, String owner) {
    store.update(
        "UPDATE gateway_discord_session SET lease_owner='',lease_until=NULL WHERE env=? AND bot_id=? AND lease_owner=?",
        settings.current(),
        bot,
        owner);
  }

  public Map<String, Object> session(String bot) {
    var row =
        store.one(
            "SELECT session_id,sequence_no,resume_url FROM gateway_discord_session WHERE env=? AND bot_id=?",
            settings.current(),
            bot);
    return row == null ? Map.of() : row;
  }

  public void clear(String bot, String owner) {
    store.update(
        "UPDATE gateway_discord_session SET session_id='',sequence_no=NULL,resume_url='',updated_at=? WHERE env=? AND bot_id=? AND lease_owner=?",
        GatewayStore.now(),
        settings.current(),
        bot,
        owner);
  }

  public long checkpoint(
      String bot,
      String owner,
      String session,
      long sequence,
      String resume,
      String type,
      Object payload) {
    return store.atomic(
        () -> {
          var row =
              store.one(
                  "SELECT session_id,sequence_no FROM gateway_discord_session WHERE env=? AND bot_id=? AND lease_owner=? AND lease_until>=? FOR UPDATE",
                  settings.current(),
                  bot,
                  owner,
                  GatewayStore.now());
          if (row == null) throw new IllegalStateException("Discord listener lease lost");
          if (session.equals(row.get("session_id"))
              && row.get("sequence_no") instanceof Number n
              && n.longValue() >= sequence) return n.longValue();
          if (TYPES.contains(type)) {
            String key = JsonUtils.sha(session + ":" + sequence);
            if (store.one(
                    "SELECT id FROM gateway_discord_event WHERE env=? AND bot_id=? AND event_key=?",
                    settings.current(),
                    bot,
                    key)
                == null) {
              store.update(
                  "INSERT INTO gateway_discord_event(env,bot_id,event_key,event_type,payload,received_at) VALUES(?,?,?,?,?,?)",
                  settings.current(),
                  bot,
                  key,
                  type,
                  JsonUtils.write(payload),
                  GatewayStore.now());
            }
          }
          store.update(
              "UPDATE gateway_discord_session SET session_id=?,sequence_no=?,resume_url=?,updated_at=? WHERE env=? AND bot_id=? AND lease_owner=?",
              session,
              sequence,
              resume,
              GatewayStore.now(),
              settings.current(),
              bot,
              owner);
          return sequence;
        });
  }

  public Map<String, Object> poll(String bot, long after, int limit, Map<String, Object> state) {
    if (after < 0 || limit < 1 || limit > 500)
      throw new IllegalArgumentException("Invalid Discord cursor");
    var rows =
        store.rows(
            "SELECT id,event_type,payload FROM gateway_discord_event WHERE env=? AND bot_id=? AND id>? ORDER BY id LIMIT ?",
            settings.current(),
            bot,
            after,
            limit);
    var items =
        rows.stream()
            .map(
                r ->
                    JsonUtils.obj(
                        "id",
                        r.get("id"),
                        "event_type",
                        r.get("event_type"),
                        "data",
                        JsonUtils.parse(JsonUtils.str(r, "payload"))))
            .toList();
    Object cursor = rows.isEmpty() ? after : rows.getLast().get("id");
    return JsonUtils.obj(
        "items", items, "cursor", cursor, "has_more", rows.size() == limit, "listener", state);
  }
}

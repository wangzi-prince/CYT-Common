package group.cyt.common.module.adapter.discord;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class DiscordAdapter implements PlatformAdapter {
  private final DiscordClient client;
  private final DiscordEvents events;
  private final DiscordGatewayListener listener;

  public DiscordAdapter(
      DiscordClient client, DiscordEvents events, DiscordGatewayListener listener) {
    this.client = client;
    this.events = events;
    this.listener = listener;
  }

  @Override
  public boolean supports(String platform) {
    return platform.equals("discord");
  }

  private static String id(Map<String, Object> p, String key) {
    String value = JsonUtils.str(p, key);
    if (!value.matches("[0-9]{15,22}")) throw GatewayException.invalid("无效的 Discord " + key);
    return value;
  }

  private Object get(
      Map<String, Object> p, String system, String path, Map<String, Object> query, int ttl) {
    return client.request(
        JsonUtils.str(p, "account"),
        system,
        "GET",
        path,
        query,
        null,
        ttl,
        JsonUtils.bool(p.get("refresh")));
  }

  private static List<Object> rows(Object value) {
    if (!(value instanceof List<?>))
      throw new GatewayException(502, "upstream_error", "Discord 未返回列表", false);
    return JsonUtils.list(value);
  }

  @Override
  public Object invoke(OperationDefinition op, Map<String, Object> p, String system) {
    return switch (op.key()) {
      case "discord.identity.get" ->
          client.identity(JsonUtils.str(p, "account"), system, JsonUtils.bool(p.get("refresh")));
      case "discord.accounts.list" -> accounts(p, system);
      case "discord.guilds.list" -> guilds(p, system);
      case "discord.channels.list" ->
          JsonUtils.obj(
              "items",
              rows(get(p, system, "/guilds/" + id(p, "guild_id") + "/channels", Map.of(), 30)));
      case "discord.bots.list" -> bots(p, system);
      case "discord.channels.get" ->
          get(p, system, "/channels/" + id(p, "channel_id"), Map.of(), 30);
      case "discord.messages.list" -> messages(p, system);
      case "discord.messages.get" ->
          get(
              p,
              system,
              "/channels/" + id(p, "channel_id") + "/messages/" + id(p, "message_id"),
              Map.of(),
              0);
      case "discord.threads.list" -> threads(p, system);
      case "discord.messages.send" -> send(p, system);
      case "discord.events.list" -> {
        var bot = client.identity(JsonUtils.str(p, "account"), system, false);
        long after = ((Number) p.getOrDefault("after", 0)).longValue();
        yield events.poll(
            JsonUtils.str(bot, "id"),
            after,
            JsonUtils.integer(p.get("limit"), 200),
            listener.status(JsonUtils.str(p, "account")));
      }
      default -> throw GatewayException.config("未知 Discord 操作");
    };
  }

  private Object accounts(Map<String, Object> p, String system) {
    var names = client.accounts();
    var limit = new Semaphore(4);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks =
          names.stream()
              .map(
                  account ->
                      workers.submit(
                          () -> {
                            boolean acquired = false;
                            try {
                              limit.acquire();
                              acquired = true;
                              var bot =
                                  client.identity(
                                      account, system, JsonUtils.bool(p.get("refresh")));
                              var item = JsonUtils.obj("account", account, "configured", true);
                              item.putAll(bot);
                              return item;
                            } catch (Exception e) {
                              if (e instanceof InterruptedException)
                                Thread.currentThread().interrupt();
                              return JsonUtils.obj(
                                  "account",
                                  account,
                                  "configured",
                                  false,
                                  "error_type",
                                  e instanceof GatewayException g
                                      ? g.type
                                      : e.getClass().getSimpleName());
                            } finally {
                              if (acquired) limit.release();
                            }
                          }))
              .toList();
      var items = new ArrayList<>();
      for (var task : tasks)
        try {
          items.add(task.get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw GatewayException.config("Discord 发现已中断");
        } catch (ExecutionException e) {
          throw GatewayException.config("Discord 发现失败");
        }
      return JsonUtils.obj("items", items);
    }
  }

  private Object guilds(Map<String, Object> p, String system) {
    var items = new ArrayList<Object>();
    String after = "";
    for (int page = 0; page < 50; page++) {
      var query = JsonUtils.obj("limit", 200);
      if (!after.isEmpty()) query.put("after", after);
      var batch = rows(get(p, system, "/users/@me/guilds", query, 30));
      items.addAll(batch);
      if (batch.size() < 200) return JsonUtils.obj("items", items);
      String next =
          batch.stream()
              .map(JsonUtils::map)
              .map(r -> JsonUtils.str(r, "id"))
              .max(Comparator.comparing(BigInteger::new))
              .orElse("");
      if (next.equals(after)) throw GatewayException.invalid("Discord 服务器分页未前进");
      after = next;
    }
    throw GatewayException.invalid("Discord 服务器数量超出单次发现上限");
  }

  private Object bots(Map<String, Object> p, String system) {
    var roles = rows(get(p, system, "/guilds/" + id(p, "guild_id") + "/roles", Map.of(), 30));
    var items = new ArrayList<>();
    for (var raw : roles) {
      var role = JsonUtils.map(raw);
      String bot = JsonUtils.str(JsonUtils.map(role.get("tags")), "bot_id");
      if (!bot.isBlank())
        items.add(JsonUtils.obj("id", bot, "name", role.get("name"), "role_id", role.get("id")));
    }
    return JsonUtils.obj("items", items, "source", "managed_bot_roles");
  }

  private Object messages(Map<String, Object> p, String system) {
    int limit = JsonUtils.integer(p.get("limit"), 100);
    var query = JsonUtils.obj("limit", limit);
    if (!JsonUtils.str(p, "before").isBlank()) query.put("before", p.get("before"));
    var items =
        rows(
            get(
                p,
                system,
                "/channels/" + id(p, "channel_id") + "/messages",
                query,
                JsonUtils.bool(p.get("preview")) ? 5 : 0));
    String before =
        items.stream()
            .map(JsonUtils::map)
            .map(r -> JsonUtils.str(r, "id"))
            .min(Comparator.comparing(BigInteger::new))
            .orElse("");
    return JsonUtils.obj("items", items, "before", before, "has_more", items.size() == limit);
  }

  private Object threads(Map<String, Object> p, String system) {
    boolean archived = JsonUtils.bool(p.get("archived"));
    String channel = id(p, "channel_id");
    var query =
        archived
            ? JsonUtils.obj("limit", JsonUtils.integer(p.get("limit"), 100))
            : new LinkedHashMap<String, Object>();
    if (archived && !JsonUtils.str(p, "before").isBlank()) query.put("before", p.get("before"));
    var data =
        JsonUtils.map(
            get(
                p,
                system,
                archived
                    ? "/channels/" + channel + "/threads/archived/public"
                    : "/guilds/" + id(p, "guild_id") + "/threads/active",
                query,
                archived ? 0 : 10));
    var items =
        JsonUtils.list(data.get("threads")).stream()
            .map(JsonUtils::map)
            .filter(t -> channel.equals(JsonUtils.str(t, "parent_id")))
            .toList();
    String before =
        items.isEmpty()
            ? ""
            : JsonUtils.str(
                JsonUtils.map(items.getLast().get("thread_metadata")), "archive_timestamp");
    return JsonUtils.obj(
        "items", items, "has_more", JsonUtils.bool(data.get("has_more")), "before", before);
  }

  private Object send(Map<String, Object> p, String system) {
    if (!Boolean.TRUE.equals(p.get("confirm")))
      throw GatewayException.invalid("发送 Discord 消息需要 confirm=true");
    String channel = id(p, "channel_id"), guild = JsonUtils.str(p, "guild_id");
    var actual =
        JsonUtils.map(
            client.request(
                JsonUtils.str(p, "account"),
                system,
                "GET",
                "/channels/" + channel,
                Map.of(),
                null,
                0,
                true));
    if (!guild.equals(JsonUtils.str(actual, "guild_id")))
      throw GatewayException.forbidden("Discord 实际服务器与发送目标不一致");
    if (!Set.of(0, 1, 5, 10, 11, 12).contains(JsonUtils.integer(actual.get("type"), -1)))
      throw GatewayException.invalid("请选择文字频道或具体论坛帖子");
    var body =
        JsonUtils.obj(
            "content",
            p.get("content"),
            "nonce",
            p.get("nonce"),
            "enforce_nonce",
            true,
            "allowed_mentions",
            JsonUtils.obj("parse", List.of(), "replied_user", false));
    if (!JsonUtils.str(p, "reply_to").isBlank())
      body.put(
          "message_reference",
          JsonUtils.obj("message_id", p.get("reply_to"), "fail_if_not_exists", false));
    return client.request(
        JsonUtils.str(p, "account"),
        system,
        "POST",
        "/channels/" + channel + "/messages",
        Map.of(),
        body,
        0,
        false);
  }
}

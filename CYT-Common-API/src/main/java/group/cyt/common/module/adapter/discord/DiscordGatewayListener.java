package group.cyt.common.module.adapter.discord;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.util.JsonUtils;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/** Opt-in Gateway v10 listener. No token is placed in URLs, status objects or logs. */
@Service
public class DiscordGatewayListener implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(DiscordGatewayListener.class);
  private final DiscordClient client;
  private final DiscordEvents events;
  private final GatewaySettings settings;
  private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> states = new ConcurrentHashMap<>();
  private final Set<String> wanted = ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().factory());
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final OkHttpClient sockets;
  private volatile boolean running;

  @org.springframework.beans.factory.annotation.Autowired
  public DiscordGatewayListener(
      DiscordClient client, DiscordEvents events, GatewaySettings settings) {
    this(
        client,
        events,
        settings,
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ZERO)
            .callTimeout(Duration.ZERO)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build());
  }

  public DiscordGatewayListener(
      DiscordClient client, DiscordEvents events, GatewaySettings settings, OkHttpClient sockets) {
    this.client = client;
    this.events = events;
    this.settings = settings;
    this.sockets = sockets;
  }

  public Map<String, Object> status(String account) {
    return new LinkedHashMap<>(
        states.getOrDefault(account, Map.of("connected", false, "state", "not_started")));
  }

  @Override
  public synchronized void start() {
    if (running) return;
    running = true;
    if (!settings.flag("DISCORD_GATEWAY_ENABLED", false)) return;
    scheduler.scheduleWithFixedDelay(this::supervise, 0, 15, TimeUnit.SECONDS);
  }

  private void supervise() {
    try {
      var names = new HashSet<>(client.accounts());
      wanted.retainAll(names);
      wanted.addAll(names);
      tasks.forEach(
          (account, task) -> {
            if (!names.contains(account)) task.cancel(true);
          });
      var systems = JsonUtils.read(settings.get("DISCORD_LISTENER_SYSTEMS_JSON", "{}"));
      for (String account : names)
        tasks.compute(
            account,
            (key, current) ->
                current == null || current.isDone()
                    ? workers.submit(() -> listen(account, JsonUtils.str(systems, account, "cs")))
                    : current);
    } catch (RuntimeException e) {
      LOG.warn("Discord listener supervision: {}", e.getClass().getSimpleName());
    }
  }

  public static String gatewayUrl(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Invalid Discord gateway URL");
    }
    String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
    if (!"wss".equals(uri.getScheme())
        || !host.startsWith("gateway")
        || !host.endsWith(".discord.gg")
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getFragment() != null
        || uri.getPort() != -1 && uri.getPort() != 443)
      throw new IllegalStateException("Unexpected Discord gateway URL");
    return value.replaceAll("/+$", "") + "/?v=10&encoding=json";
  }

  private void listen(String account, String system) {
    String owner = UUID.randomUUID().toString();
    var state = states.computeIfAbsent(account, key -> new ConcurrentHashMap<>());
    while (running && wanted.contains(account) && !Thread.currentThread().isInterrupted()) {
      String bot = "";
      Session session = null;
      long delay = 5;
      try {
        state.put("connected", false);
        state.put("state", "connecting");
        var identity = client.identity(account, system, false);
        if (!Boolean.TRUE.equals(identity.get("bot")))
          throw new IllegalStateException("Expected Discord bot identity");
        bot = JsonUtils.str(identity, "id");
        if (!events.claim(bot, owner)) {
          state.put("state", "another_listener_active");
          pause(5);
          continue;
        }
        var saved = events.session(bot);
        String url = JsonUtils.str(saved, "resume_url");
        if (url.isBlank()) {
          var gateway =
              JsonUtils.map(
                  client.request(
                      account, system, "GET", "/gateway/bot", Map.of(), null, 60, false));
          if (JsonUtils.integer(gateway.get("shards"), 1) > 1)
            throw new IllegalStateException("Discord sharding configuration required");
          url = JsonUtils.str(gateway, "url");
        }
        String credential = client.token(account, system);
        session = new Session(bot, owner, credential, saved, state);
        session.socket =
            sockets.newWebSocket(new Request.Builder().url(gatewayUrl(url)).build(), session);
        while (running && wanted.contains(account) && !session.done.await(10, TimeUnit.SECONDS)) {
          if (!events.renew(bot, owner))
            throw new IllegalStateException("Discord listener lease lost");
          if (!credential.equals(client.token(account, system))) break;
          if (!session.hello && System.nanoTime() - session.opened > TimeUnit.SECONDS.toNanos(25))
            throw new IllegalStateException("Discord gateway hello timed out");
        }
        if (Set.of(4007, 4009).contains(session.closeCode)) events.clear(bot, owner);
        if (Set.of(4004, 4013, 4014).contains(session.closeCode)) {
          state.put("state", "credentials_or_intents_required");
          delay = 60;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException e) {
        state.put("state", "error");
        state.put("error_type", e.getClass().getSimpleName());
        LOG.warn("Discord listener {}: {}", account, e.getClass().getSimpleName());
      } finally {
        if (session != null) session.cancel();
        state.put("connected", false);
        if (!bot.isBlank())
          try {
            events.release(bot, owner);
          } catch (RuntimeException e) {
            LOG.warn("Discord lease release: {}", e.getClass().getSimpleName());
          }
      }
      pause(delay);
    }
  }

  private void pause(long seconds) {
    try {
      TimeUnit.SECONDS.sleep(seconds);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private final class Session extends WebSocketListener {
    private final String bot, owner, token;
    private final Map<String, Object> state;
    private final CountDownLatch done = new CountDownLatch(1);
    private final long opened = System.nanoTime();
    private volatile WebSocket socket;
    private volatile Long sequence;
    private volatile String sessionId, resumeUrl;
    private volatile boolean acked = true, hello;
    private volatile int closeCode;
    private ScheduledFuture<?> heartbeat;

    Session(
        String bot,
        String owner,
        String token,
        Map<String, Object> saved,
        Map<String, Object> state) {
      this.bot = bot;
      this.owner = owner;
      this.token = token;
      this.state = state;
      sessionId = JsonUtils.str(saved, "session_id");
      resumeUrl = JsonUtils.str(saved, "resume_url");
      sequence = saved.get("sequence_no") instanceof Number n ? n.longValue() : null;
    }

    @Override
    public void onOpen(WebSocket socket, Response response) {
      this.socket = socket;
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
      try {
        if (done.getCount() == 0) return;
        if (text.length() > 8 * 1024 * 1024)
          throw new IllegalStateException("Discord packet too large");
        var packet = JsonUtils.read(text);
        int op = JsonUtils.integer(packet.get("op"), -1);
        var data = JsonUtils.map(packet.get("d"));
        if (op == 10) {
          if (hello) throw new IllegalStateException("Duplicate Discord hello");
          long interval = JsonUtils.integer(data.get("heartbeat_interval"), 0);
          if (interval < 1000 || interval > 120000)
            throw new IllegalStateException("Invalid heartbeat interval");
          hello = true;
          heartbeat =
              scheduler.scheduleAtFixedRate(
                  () -> heartbeat(socket),
                  ThreadLocalRandom.current().nextLong(interval),
                  interval,
                  TimeUnit.MILLISECONDS);
          if (!sessionId.isBlank() && sequence != null)
            send(
                socket,
                JsonUtils.obj(
                    "op",
                    6,
                    "d",
                    JsonUtils.obj("token", token, "session_id", sessionId, "seq", sequence)));
          else
            send(
                socket,
                JsonUtils.obj(
                    "op",
                    2,
                    "d",
                    JsonUtils.obj(
                        "token",
                        token,
                        "intents",
                        1 | 512 | 4096 | 32768,
                        "properties",
                        JsonUtils.obj(
                            "os", "java", "browser", "CYT-Common", "device", "CYT-Common"))));
        } else if (op == 11) acked = true;
        else if (op == 1) {
          acked = false;
          send(socket, JsonUtils.obj("op", 1, "d", sequence));
        } else if (op == 7) cancel();
        else if (op == 9) {
          if (!JsonUtils.bool(packet.get("d"))) events.clear(bot, owner);
          cancel();
        } else if (op == 0) {
          String kind = JsonUtils.str(packet, "t");
          if (kind.equals("READY")) {
            sessionId = JsonUtils.str(data, "session_id");
            resumeUrl = JsonUtils.str(data, "resume_gateway_url");
            if (sessionId.isBlank() || sessionId.length() > 160)
              throw new IllegalStateException("Invalid Discord session");
            gatewayUrl(resumeUrl);
          }
          if (!(packet.get("s") instanceof Number seq))
            throw new IllegalStateException("Missing Discord sequence");
          // Commit event and checkpoint together, then expose the new heartbeat/resume position.
          sequence =
              events.checkpoint(
                  bot, owner, sessionId, seq.longValue(), resumeUrl, kind, packet.get("d"));
          if (kind.equals("READY") || kind.equals("RESUMED")) {
            state.put("connected", true);
            state.put("state", kind.equals("READY") ? "ready" : "resumed");
            state.put("bot_id", bot);
            state.remove("error_type");
            state.remove("close_code");
          } else if (DiscordEvents.TYPES.contains(kind))
            state.put("last_event_at", Instant.now().toString());
        }
      } catch (RuntimeException e) {
        fail(e);
      }
    }

    private void send(WebSocket socket, Object packet) {
      String encoded = JsonUtils.write(packet);
      if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096
          || !socket.send(encoded)) throw new IllegalStateException("Discord gateway send failed");
    }

    private void heartbeat(WebSocket socket) {
      try {
        if (!acked) throw new IllegalStateException("Discord heartbeat not acknowledged");
        acked = false;
        send(socket, JsonUtils.obj("op", 1, "d", sequence));
      } catch (RuntimeException e) {
        fail(e);
      }
    }

    @Override
    public void onClosing(WebSocket socket, int code, String reason) {
      closeCode = code;
      cancel();
    }

    @Override
    public void onClosed(WebSocket socket, int code, String reason) {
      closeCode = code;
      cancel();
    }

    @Override
    public void onFailure(WebSocket socket, Throwable error, Response response) {
      fail(error);
    }

    private void fail(Throwable error) {
      state.put("connected", false);
      state.put("state", "disconnected");
      state.put("error_type", error.getClass().getSimpleName());
      cancel();
    }

    private void cancel() {
      if (heartbeat != null) heartbeat.cancel(false);
      done.countDown();
      if (socket != null) socket.cancel();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public synchronized void stop() {
    running = false;
    wanted.clear();
    tasks.values().forEach(t -> t.cancel(true));
    workers.shutdownNow();
    scheduler.shutdownNow();
    sockets.dispatcher().executorService().shutdown();
    sockets.connectionPool().evictAll();
  }
}

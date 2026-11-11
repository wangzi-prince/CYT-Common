package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.AdapterDispatcher;
import group.cyt.common.module.adapter.MockAdapter;
import group.cyt.common.module.adapter.discord.*;
import group.cyt.common.module.adapter.support.BusinessWorkflows;
import group.cyt.common.module.adapter.wecom.approval.WecomApprovalService;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

class DiscordAdapterTest {
  private static final String G = "234567890123456", C = "123456789012345", B = "345678901234567";
  private final OperationRegistry registry;
  private final CredentialService credentials = mock(CredentialService.class);
  private final UpstreamHttp http = mock(UpstreamHttp.class);
  private final GatewaySettings settings = new GatewaySettings(new MockEnvironment());
  private final DiscordClient client = new DiscordClient(credentials, http, settings);
  private final DiscordAdapter adapter =
      new DiscordAdapter(client, mock(DiscordEvents.class), mock(DiscordGatewayListener.class));

  DiscordAdapterTest() throws Exception {
    registry = new OperationRegistry(mock(GatewayStore.class));
    when(credentials.resolve(anyString(), anyString())).thenReturn("fixture-token");
    when(credentials.raw(anyString())).thenReturn("");
  }

  private UpstreamHttp.ResponseData response(int status, Object body) {
    return new UpstreamHttp.ResponseData(
        status, Map.of(), JsonUtils.write(body).getBytes(StandardCharsets.UTF_8));
  }

  private Object call(String key, Map<String, Object> params) {
    var op = registry.get(key);
    return adapter.invoke(op, registry.validate(op, params), "cs");
  }

  @Test
  void sendRequiresConfirmationAndTheActualGuild() {
    var p = JsonUtils.obj("channel_id", C, "guild_id", G, "content", "hello", "nonce", "123");
    assertThrows(GatewayException.class, () -> call("discord.messages.send", p));
    verifyNoInteractions(http);
    p.put("confirm", true);
    when(http.quietExchange(eq("GET"), anyString(), anyMap(), isNull(), anyDouble()))
        .thenReturn(response(200, Map.of("id", C, "guild_id", B, "type", 0)));
    assertThrows(GatewayException.class, () -> call("discord.messages.send", p));
    verify(http, never()).quietExchange(eq("POST"), anyString(), anyMap(), any(), anyDouble());
  }

  @Test
  void confirmedSendUsesNonceAndCannotPingEveryone() {
    when(http.quietExchange(eq("GET"), anyString(), anyMap(), isNull(), anyDouble()))
        .thenReturn(response(200, Map.of("id", C, "guild_id", G, "type", 0)));
    when(http.quietExchange(eq("POST"), anyString(), anyMap(), any(), anyDouble()))
        .thenAnswer(
            a -> {
              var payload =
                  JsonUtils.read(new String((byte[]) a.getArgument(3), StandardCharsets.UTF_8));
              assertEquals(true, payload.get("enforce_nonce"));
              assertEquals("123", payload.get("nonce"));
              assertEquals(List.of(), JsonUtils.map(payload.get("allowed_mentions")).get("parse"));
              return response(200, Map.of("id", B));
            });
    var result =
        JsonUtils.map(
            call(
                "discord.messages.send",
                JsonUtils.obj(
                    "channel_id",
                    C,
                    "guild_id",
                    G,
                    "content",
                    "@everyone hello",
                    "nonce",
                    "123",
                    "confirm",
                    true)));
    assertEquals(B, result.get("id"));
    var op = registry.get("discord.messages.send");
    assertEquals(0, op.retryMax());
    assertTrue(op.sensitive());
    assertTrue(op.idempotent());
  }

  @Test
  void forumParentCannotReceivePlainMessages() {
    when(http.quietExchange(eq("GET"), anyString(), anyMap(), isNull(), anyDouble()))
        .thenReturn(response(200, Map.of("id", C, "guild_id", G, "type", 15)));
    assertThrows(
        GatewayException.class,
        () ->
            call(
                "discord.messages.send",
                JsonUtils.obj(
                    "channel_id",
                    C,
                    "guild_id",
                    G,
                    "content",
                    "hello",
                    "nonce",
                    "123",
                    "confirm",
                    true)));
    verify(http, never()).quietExchange(eq("POST"), anyString(), anyMap(), any(), anyDouble());
  }

  @Test
  void messagesKeepRawBotCardsAndUseTheLowestIdAsCursor() {
    when(http.quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenReturn(
            response(
                200,
                List.of(
                    Map.of(
                        "id",
                        "300",
                        "author",
                        Map.of("bot", true),
                        "embeds",
                        List.of(Map.of("title", "card"))),
                    Map.of("id", "100"),
                    Map.of("id", "200"))));
    var page = JsonUtils.map(call("discord.messages.list", Map.of("channel_id", C, "limit", 3)));
    assertEquals("100", page.get("before"));
    assertEquals(true, page.get("has_more"));
    assertTrue(JsonUtils.map(JsonUtils.list(page.get("items")).getFirst()).containsKey("embeds"));
    call("discord.messages.list", Map.of("channel_id", C, "limit", 3));
    verify(http, times(2)).quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble());
  }

  @Test
  void managedBotRolesDoNotPretendToBeCredentials() {
    when(http.quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenReturn(
            response(
                200,
                List.of(
                    Map.of("id", C, "name", "Fixture bot", "tags", Map.of("bot_id", B)),
                    Map.of("id", G, "name", "Member"))));
    var data = JsonUtils.map(call("discord.bots.list", Map.of("guild_id", G)));
    assertEquals(1, JsonUtils.list(data.get("items")).size());
    assertFalse(JsonUtils.write(data).contains("fixture-token"));
  }

  @Test
  void metadataCacheIsolatedBySystemRotationAndForcedRefresh() {
    var token = new AtomicReference<>("first-token");
    when(credentials.resolve(anyString(), anyString())).thenAnswer(a -> token.get());
    when(http.quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenReturn(response(200, Map.of("id", B, "bot", true)));
    client.identity("fixture", "cs", false);
    client.identity("fixture", "cs", false);
    verify(http, times(1)).quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble());
    client.identity("fixture", "other", false);
    token.set("rotated-token");
    client.identity("fixture", "cs", false);
    client.identity("fixture", "cs", true);
    verify(http, times(4)).quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble());
  }

  @Test
  void missingAccessPreservesDiscordCodeAndIsNeverCached() {
    when(http.quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenReturn(
            response(403, Map.of("code", 50001, "message", "Missing Access")),
            response(200, List.of()));
    var error =
        assertThrows(
            GatewayException.class, () -> call("discord.channels.list", Map.of("guild_id", G)));
    assertEquals(50001, JsonUtils.map(error.detail).get("code"));
    assertFalse(error.retryable);
    call("discord.channels.list", Map.of("guild_id", G));
    verify(http, times(2)).quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble());
  }

  @Test
  void onlyPreviewReadsUseTheFiveSecondMessageCache() {
    when(http.quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenReturn(response(200, List.of()));
    call("discord.messages.list", Map.of("channel_id", C, "preview", true));
    call("discord.messages.list", Map.of("channel_id", C, "preview", true));
    verify(http, times(1)).quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble());
    call("discord.messages.list", Map.of("channel_id", C, "preview", true, "refresh", true));
    call("discord.messages.list", Map.of("channel_id", C));
    verify(http, times(3)).quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble());
  }

  @Test
  void discoveryReportsCredentialFailureWithoutSecrets() {
    when(credentials.accounts("discord")).thenReturn(List.of("first", "missing"));
    when(credentials.resolve(eq("DISCORD_MISSING_BOT_TOKEN"), anyString())).thenReturn("");
    when(http.quietExchange(anyString(), anyString(), anyMap(), any(), anyDouble()))
        .thenReturn(response(200, Map.of("id", B, "username", "Fixture bot", "bot", true)));
    var result = JsonUtils.map(call("discord.accounts.list", Map.of()));
    assertEquals(2, JsonUtils.list(result.get("items")).size());
    assertEquals(
        false, JsonUtils.map(JsonUtils.list(result.get("items")).getLast()).get("configured"));
    assertFalse(JsonUtils.write(result).contains("fixture-token"));
  }

  private record Database(GatewayStore store, JdbcTemplate jdbc, DiscordEvents events) {}

  private Database database() throws Exception {
    var ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:discord_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    var jdbc = new JdbcTemplate(ds);
    try (var input = getClass().getResourceAsStream("/db/migration/V6__discord_gateway.sql")) {
      String sql =
          new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)
              .replaceAll("\\) ENGINE=[^;]+;", ");");
      for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }
    var store = spy(new GatewayStore(jdbc, new DataSourceTransactionManager(ds)));
    return new Database(store, jdbc, new DiscordEvents(store, settings));
  }

  @Test
  void inboxDeduplicatesAndIsolatesBotAndEnvironment() throws Exception {
    var db = database();
    var events = db.events();
    assertTrue(events.claim(B, "owner"));
    assertFalse(events.claim(B, "other"));
    events.checkpoint(
        B, "owner", "session", 1, "wss://gateway.discord.gg", "MESSAGE_CREATE", Map.of("id", C));
    events.checkpoint(
        B, "owner", "session", 1, "wss://gateway.discord.gg", "MESSAGE_CREATE", Map.of("id", C));
    assertEquals(1, JsonUtils.list(events.poll(B, 0, 100, Map.of()).get("items")).size());
    assertTrue(JsonUtils.list(events.poll(C, 0, 100, Map.of()).get("items")).isEmpty());
    var other =
        new DiscordEvents(
            db.store(), new GatewaySettings(new MockEnvironment().withProperty("ENV", "other")));
    assertTrue(JsonUtils.list(other.poll(B, 0, 100, Map.of()).get("items")).isEmpty());
    assertEquals(1L, ((Number) events.session(B).get("sequence_no")).longValue());
  }

  @Test
  void checkpointDoesNotAdvanceIfInboxCommitFailsOrLeaseIsLost() throws Exception {
    var db = database();
    var events = db.events();
    assertTrue(events.claim(B, "owner"));
    events.checkpoint(B, "owner", "session", 10, "wss://gateway.discord.gg", "READY", Map.of());
    doThrow(new IllegalStateException("Fixture DB failure"))
        .when(db.store())
        .update(startsWith("INSERT INTO gateway_discord_event"), any(Object[].class));
    assertThrows(
        IllegalStateException.class,
        () ->
            events.checkpoint(
                B,
                "owner",
                "session",
                11,
                "wss://gateway.discord.gg",
                "MESSAGE_CREATE",
                Map.of("id", C)));
    assertEquals(10L, ((Number) events.session(B).get("sequence_no")).longValue());
    assertThrows(
        IllegalStateException.class,
        () ->
            events.checkpoint(
                B,
                "wrong-owner",
                "session",
                11,
                "wss://gateway.discord.gg",
                "MESSAGE_CREATE",
                Map.of("id", C)));
  }

  @Test
  void restartResumesFromTheDurableSequenceAndPersistsReplayedMessages() throws Exception {
    var db = database();
    var events = db.events();
    events.claim(B, "seed");
    events.checkpoint(
        B, "seed", "fixture-session", 10, "wss://gateway.discord.gg", "READY", Map.of());
    events.release(B, "seed");
    var liveClient = mock(DiscordClient.class);
    when(liveClient.accounts()).thenReturn(List.of("fixture"));
    when(liveClient.token(anyString(), anyString())).thenReturn("synthetic-token");
    when(liveClient.identity(anyString(), anyString(), anyBoolean()))
        .thenReturn(Map.of("id", B, "bot", true));
    var sockets = mock(OkHttpClient.class);
    var ws = mock(WebSocket.class);
    var sent = new CopyOnWriteArrayList<Map<String, Object>>();
    when(ws.send(anyString()))
        .thenAnswer(
            a -> {
              sent.add(JsonUtils.read(a.getArgument(0)));
              return true;
            });
    when(sockets.dispatcher()).thenReturn(new Dispatcher());
    when(sockets.connectionPool()).thenReturn(new ConnectionPool());
    when(sockets.newWebSocket(any(), any()))
        .thenAnswer(
            a -> {
              WebSocketListener l = a.getArgument(1);
              l.onOpen(ws, null);
              l.onMessage(ws, "{\"op\":10,\"d\":{\"heartbeat_interval\":45000}}");
              l.onMessage(ws, "{\"op\":0,\"t\":\"RESUMED\",\"s\":11,\"d\":{}}");
              l.onMessage(
                  ws,
                  "{\"op\":0,\"t\":\"MESSAGE_CREATE\",\"s\":12,\"d\":{\"id\":\"fixture-message\"}}");
              return ws;
            });
    var listener =
        new DiscordGatewayListener(
            liveClient,
            events,
            new GatewaySettings(
                new MockEnvironment().withProperty("DISCORD_GATEWAY_ENABLED", "true")),
            sockets);
    try {
      listener.start();
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (JsonUtils.list(events.poll(B, 0, 100, Map.of()).get("items")).isEmpty()
          && System.nanoTime() < until) Thread.sleep(10);
      assertEquals(1, JsonUtils.list(events.poll(B, 0, 100, Map.of()).get("items")).size());
      assertEquals(12L, ((Number) events.session(B).get("sequence_no")).longValue());
      var resume =
          sent.stream().filter(p -> Objects.equals(p.get("op"), 6)).findFirst().orElseThrow();
      assertEquals(10, JsonUtils.map(resume.get("d")).get("seq"));
    } finally {
      listener.stop();
    }
  }

  @Test
  void gatewayResumeCannotPointToAnotherHost() {
    assertEquals(
        "wss://gateway.discord.gg/?v=10&encoding=json",
        DiscordGatewayListener.gatewayUrl("wss://gateway.discord.gg"));
    for (String url :
        List.of(
            "ws://gateway.discord.gg",
            "wss://evil.invalid",
            "wss://gateway.discord.gg@evil.invalid",
            "wss://gateway.discord.gg:8443"))
      assertThrows(IllegalStateException.class, () -> DiscordGatewayListener.gatewayUrl(url));
  }

  @Test
  void discordNeverSilentlyFallsBackToTheGlobalMockMode() {
    var dispatcher =
        new AdapterDispatcher(
            List.of(),
            new MockAdapter(),
            new GatewaySettings(new MockEnvironment().withProperty("MOCK_ALL", "true")),
            registry,
            mock(WecomApprovalService.class),
            mock(BusinessWorkflows.class));
    assertFalse(dispatcher.mock(registry.get("discord.messages.list")));
    assertTrue(dispatcher.mock(registry.get("shopify.orders.list")));
  }
}

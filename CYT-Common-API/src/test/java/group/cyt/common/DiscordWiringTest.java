package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;

import group.cyt.common.core.config.GatewayBootstrap;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.AdapterDispatcher;
import group.cyt.common.module.adapter.discord.DiscordAdapter;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Wire the actual native beans without touching the developer's MySQL database or credentials. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:discord_wiring;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.flyway.enabled=false",
      "DISCORD_GATEWAY_ENABLED=false",
      "CREDENTIAL_MASTER_KEY=",
      "ENV=local",
      "MOCK_ALL=true"
    })
class DiscordWiringTest {
  @MockitoBean GatewayStore store;
  @MockitoBean GatewayBootstrap bootstrap;
  @Autowired DiscordAdapter adapter;
  @Autowired OperationRegistry registry;
  @Autowired AdapterDispatcher dispatcher;
  @Autowired CredentialService credentials;

  @Test
  void platformCatalogCredentialsAndNativeDispatcherAreConnected() {
    assertTrue(adapter.supports("discord"));
    assertEquals(
        11,
        registry.contract().path("operations").findValuesAsText("platform").stream()
            .filter("discord"::equals)
            .count());
    assertFalse(dispatcher.mock(registry.get("discord.messages.list")));
    assertTrue(
        credentials.inventory(List.of()).stream()
            .map(JsonUtils::map)
            .anyMatch(p -> "discord".equals(p.get("platform"))));
  }
}

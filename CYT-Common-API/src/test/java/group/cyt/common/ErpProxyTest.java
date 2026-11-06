package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.support.ErpProxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ErpProxyTest {
  private Object request(int status, String body) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/lingxing/mail",
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    try {
      return new ErpProxy()
          .request(
              "lingxing",
              "GET",
              "/mail",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "fixture-token",
              Map.of(),
              null,
              Map.of(),
              5);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void fullMailPageAndMultilineBodiesSurviveDiagnosticTruncation() throws Exception {
    var rows = new ArrayList<Object>();
    for (int i = 0; i < 100; i++)
      rows.add(
          JsonUtils.obj("webmail_uuid", "mail-" + i, "text_plain", "客户邮件\n" + "内容".repeat(200)));
    var expected = JsonUtils.obj("code", 0, "data", rows, "total", 100);
    String payload = JsonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(expected);
    assertTrue(payload.length() > 300);
    assertEquals(expected, request(200, payload));
  }

  @Test
  void malformedVendorJsonIsAnUpstreamFailureRatherThanInvalidCallerParams() {
    var error =
        assertThrows(GatewayException.class, () -> request(200, "<html>relay failure</html>"));
    assertEquals(502, error.status);
    assertEquals("upstream_error", error.type);
  }

  @Test
  void oversizedSuccessDoesNotParseAPartialResponse() {
    var error =
        assertThrows(GatewayException.class, () -> request(200, " ".repeat(16 * 1024 * 1024 + 1)));
    assertEquals(502, error.status);
    assertEquals("upstream_error", error.type);
    assertTrue(error.getMessage().contains("大小限制"));
  }

  @Test
  void relayErrorsKeepTheirStatusAndShortDiagnostic() {
    var error = assertThrows(GatewayException.class, () -> request(429, "rate limit ".repeat(100)));
    assertEquals(429, error.status);
    assertTrue(error.retryable);
    assertTrue(error.getMessage().length() < 400);
  }
}

package group.cyt.common.module.adapter.support;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import okhttp3.*;
import org.springframework.stereotype.Component;

/**
 * ERP relay used by vendors that whitelist a fixed egress IP (Lingxing, Mabang).
 *
 * <p>The relay routes on the path prefix alone: {@code {base}/{erp}/{path}} with {@code
 * X-Proxy-Token}. Vendor signing stays with the caller, so the relay never sees a secret.
 */
@Component
public class ErpProxy {
  private static final int CONNECT_ATTEMPTS = 3;
  private static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  /**
   * {@link Proxy#NO_PROXY} is deliberate: a shell-injected HTTP_PROXY would move the egress IP off
   * the one the vendor whitelisted, and the resulting failure looks exactly like a bad credential.
   */
  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .proxy(Proxy.NO_PROXY)
          .connectTimeout(Duration.ofSeconds(10))
          .retryOnConnectionFailure(false)
          .build();

  public static String url(String base, String erp, String path) {
    return base.strip().replaceAll("/+$", "") + "/" + erp + "/" + path.replaceFirst("^/+", "");
  }

  /** Half a configuration is worse than none: a missing token returns 401 and reads as "wrong key". */
  public static boolean active(String base, String token) {
    return !base.strip().isEmpty() && !token.strip().isEmpty();
  }

  public Object request(
      String erp,
      String method,
      String path,
      String base,
      String token,
      Map<String, Object> params,
      byte[] content,
      Map<String, String> headers,
      double timeout) {
    if (!active(base, token))
      throw GatewayException.config(erp + " 代理未配置：需要 ERP_PROXY_BASE 与 X-Proxy-Token");

    HttpUrl target = HttpUrl.parse(url(base, erp, path));
    if (target == null) throw GatewayException.config(erp + " 代理地址无效: " + base);
    HttpUrl.Builder query = target.newBuilder();
    if (params != null)
      for (Map.Entry<String, Object> e : params.entrySet()) {
        Object v = e.getValue();
        if (v == null) continue;
        if (v instanceof Collection<?> many) many.forEach(x -> add(query, e.getKey(), x));
        else add(query, e.getKey(), v);
      }

    Map<String, String> sendHeaders = new LinkedHashMap<>();
    sendHeaders.put("Content-Type", JSON.toString());
    sendHeaders.put("X-Proxy-Token", token.strip());
    if (headers != null) sendHeaders.putAll(headers);

    RequestBody body =
        content == null
            ? (Set.of("POST", "PUT", "PATCH").contains(method)
                ? RequestBody.create(new byte[0], JSON)
                : null)
            : RequestBody.create(content, MediaType.get(sendHeaders.get("Content-Type")));

    var call =
        client
            .newBuilder()
            .callTimeout(Duration.ofMillis((long) (Math.max(1, Math.min(120, timeout)) * 1000)))
            .build();
    IOException last = null;
    for (int attempt = 1; ; attempt++) {
      Request request =
          new Request.Builder()
              .url(query.build())
              .headers(Headers.of(sendHeaders))
              .method(method, body)
              .build();
      try (Response response = call.newCall(request).execute()) {
        return parse(response, erp);
      } catch (IOException e) {
        // Only a connect-phase failure may be replayed: once the request has been written to the
        // relay it might already have landed upstream, and a retry would duplicate the order.
        if (!isConnectFailure(e)) {
          if (e instanceof SocketTimeoutException)
            throw new GatewayException(
                504, "upstream_timeout", erp + " 代理请求超时: " + method + " " + target, true);
          throw new GatewayException(
              502,
              "upstream_error",
              erp + " 代理请求失败: " + e.getClass().getSimpleName() + ": " + e,
              false);
        }
        last = e;
        if (attempt >= CONNECT_ATTEMPTS)
          throw new GatewayException(
              502,
              "upstream_error",
              erp
                  + " 代理建连失败（已试 "
                  + attempt
                  + " 次）: "
                  + e.getClass().getSimpleName()
                  + ": "
                  + e,
              true);
        try {
          Thread.sleep(1000L * attempt); // cross-border jitter usually clears within a second
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new GatewayException(
              502,
              "upstream_error",
              erp + " 代理建连重试被中断（已试 " + attempt + " 次）: " + last,
              true);
        }
      }
    }
  }

  /** Connection refused / DNS / connect-timeout — nothing has been sent to the relay yet. */
  private static boolean isConnectFailure(IOException e) {
    return e instanceof ConnectException
        || (e instanceof SocketTimeoutException
            && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("connect"));
  }

  private static void add(HttpUrl.Builder query, String name, Object value) {
    if (value != null) query.addQueryParameter(name, Objects.toString(value, ""));
  }

  /** Separates relay faults from vendor responses; the two need opposite fixes. */
  private Object parse(Response response, String erp) throws IOException {
    int status = response.code();
    String snippet =
        new String(
                response.body() == null ? new byte[0] : response.body().byteStream()
                    .readNBytes(MAX_BYTES + 1),
                StandardCharsets.UTF_8)
            .strip()
            .replace("\n", " ");
    if (snippet.length() > 300) snippet = snippet.substring(0, 300);

    if (status == 401)
      throw GatewayException.config(erp + " 代理鉴权失败（X-Proxy-Token 错误或缺失）: " + snippet);
    if (status == 404 && snippet.contains("unknown path"))
      throw GatewayException.config(
          erp + " 代理路径前缀无效（检查 PROXY_BASE 是否指向 erp-proxy）: " + snippet);
    if (status == 429)
      throw new GatewayException(429, "rate_limited", erp + " 代理限流 HTTP 429: " + snippet, true);
    if (status == 502 || status == 504)
      throw new GatewayException(
          502, "upstream_error", erp + " 代理上游网关错误 HTTP " + status + "（跨境抖动，稍后重试）: " + snippet, true);
    if (response.isSuccessful()) return JsonUtils.parse(snippet.isEmpty() ? "null" : snippet);
    throw new GatewayException(502, "upstream_error", erp + " 代理返回 HTTP " + status + ": " + snippet, false);
  }
}

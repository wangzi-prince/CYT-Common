package group.cyt.common.core.http;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import okhttp3.*;
import org.springframework.stereotype.Component;

@Component
public class UpstreamHttp {
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final int MAX_BYTES = 16 * 1024 * 1024;

  public record ResponseData(int status, Map<String, String> headers, byte[] body) {}

  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .connectTimeout(Duration.ofSeconds(10))
          .readTimeout(Duration.ofSeconds(30))
          .callTimeout(Duration.ofSeconds(40))
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          // A cancelled exchange can leave a half-open HTTP/2 connection in the pool, and every
          // later call reusing it stalls until its own timeout. Pings make okhttp evict it.
          .pingInterval(Duration.ofSeconds(15))
          .build();

  public Object json(
      String method, String url, Map<String, String> headers, Object body, double timeout) {
    return JsonUtils.parse(
        new String(
            raw(
                method,
                url,
                headers,
                body == null
                    ? null
                    : JsonUtils.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                timeout,
                null),
            java.nio.charset.StandardCharsets.UTF_8));
  }

  public Map<String, Object> object(
      String method, String url, Map<String, String> headers, Object body) {
    return JsonUtils.map(json(method, url, headers, body, 30));
  }

  public Object form(String url, Map<String, String> values, Map<String, String> headers) {
    FormBody.Builder form = new FormBody.Builder();
    values.forEach(form::add);
    return execute(
        client,
        new Request.Builder().url(url).headers(Headers.of(headers)).post(form.build()).build(),
        true);
  }

  public byte[] raw(
      String method,
      String url,
      Map<String, String> headers,
      byte[] body,
      double timeout,
      List<InetAddress> pinned) {
    return exchange(method, url, headers, body, timeout, pinned).body();
  }

  /**
   * Same as {@link #exchange} but returns non-2xx responses instead of raising. Callers that must
   * read the error body to classify a failure — a proxy reports its own fault there — use this.
   */
  public ResponseData quietExchange(
      String method, String url, Map<String, String> headers, byte[] body, double timeout) {
    var builder =
        client
            .newBuilder()
            .callTimeout(Duration.ofMillis((long) (Math.max(1, Math.min(120, timeout)) * 1000)));
    RequestBody requestBody =
        body == null
            ? null
            : RequestBody.create(
                body, MediaType.get(headers.getOrDefault("Content-Type", JSON.toString())));
    if (Set.of("POST", "PUT", "PATCH").contains(method) && requestBody == null)
      requestBody = RequestBody.create(new byte[0], JSON);
    Request request =
        new Request.Builder()
            .url(url)
            .headers(Headers.of(headers))
            .method(method, requestBody)
            .build();
    try (Response response = builder.build().newCall(request).execute()) {
      Map<String, String> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (String key : response.headers().names()) responseHeaders.put(key, response.header(key));
      byte[] bytes =
          response.body() == null
              ? new byte[0]
              : response.body().byteStream().readNBytes(MAX_BYTES + 1);
      return new ResponseData(response.code(), responseHeaders, bytes);
    } catch (java.io.InterruptedIOException e) {
      throw new GatewayException(504, "upstream_timeout", "上游调用超时", true);
    } catch (IOException e) {
      throw new GatewayException(502, "upstream_error", "上游连接失败", true);
    }
  }

  public ResponseData exchange(
      String method,
      String url,
      Map<String, String> headers,
      byte[] body,
      double timeout,
      List<InetAddress> pinned) {
    var builder =
        client
            .newBuilder()
            .callTimeout(Duration.ofMillis((long) (Math.max(1, Math.min(120, timeout)) * 1000)));
    if (pinned != null) {
      String host = URI.create(url).getHost();
      builder.dns(
          name -> {
            if (!name.equals(host)) throw new UnknownHostException("Unexpected DNS host");
            return pinned;
          });
    }
    String contentType =
        headers.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(JSON.toString());
    RequestBody requestBody =
        body == null ? null : RequestBody.create(body, MediaType.get(contentType));
    if (Set.of("POST", "PUT", "PATCH").contains(method) && requestBody == null)
      requestBody = RequestBody.create(new byte[0], JSON);
    Request request =
        new Request.Builder()
            .url(url)
            .headers(Headers.of(headers))
            .method(method, requestBody)
            .build();
    return receive(builder.build(), request);
  }

  private Object execute(OkHttpClient http, Request request, boolean json) {
    byte[] bytes = receive(http, request).body();
    return json
        ? JsonUtils.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
        : bytes;
  }

  private ResponseData receive(OkHttpClient http, Request request) {
    try (Response response = http.newCall(request).execute()) {
      int status = response.code();
      if (status < 200 || status >= 300) {
        var ex =
            new GatewayException(
                status == 429 ? 429 : 502,
                status == 429 ? "rate_limited" : "upstream_error",
                "上游返回 HTTP " + status,
                status == 429 || status >= 500);
        ex.code = "UPSTREAM_HTTP_" + status;
        if (status == 429) ex.retryAfter = JsonUtils.integer(response.header("Retry-After"), 1);
        throw ex;
      }
      Map<String, String> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (String key : response.headers().names()) responseHeaders.put(key, response.header(key));
      if (response.body() == null) return new ResponseData(status, responseHeaders, new byte[0]);
      try (InputStream input = response.body().byteStream()) {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES)
          throw new GatewayException(502, "upstream_error", "上游响应超过大小限制", false);
        return new ResponseData(status, responseHeaders, bytes);
      }
    } catch (GatewayException e) {
      throw e;
    } catch (java.io.InterruptedIOException e) {
      throw new GatewayException(504, "upstream_timeout", "上游调用超时", true);
    } catch (IOException e) {
      throw new GatewayException(502, "upstream_error", "上游连接失败", true);
    }
  }

  public static String query(String url, Map<String, ?> params) {
    HttpUrl parsed = HttpUrl.get(url);
    var b = parsed.newBuilder();
    params.forEach(
        (k, v) -> {
          if (v != null && !v.toString().isBlank()) b.addQueryParameter(k, v.toString());
        });
    return b.build().toString();
  }

  public static String segment(Object value) {
    return URLEncoder.encode(Objects.toString(value, ""), java.nio.charset.StandardCharsets.UTF_8)
        .replace("+", "%20");
  }
}

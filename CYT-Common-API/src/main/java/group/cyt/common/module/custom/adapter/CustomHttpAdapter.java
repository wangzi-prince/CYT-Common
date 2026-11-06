package group.cyt.common.module.custom.adapter;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.*;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

@Component
public class CustomHttpAdapter implements PlatformAdapter {
  private record HttpResult(int status, Object body) {}

  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{\\s*params\\.([A-Za-z_][A-Za-z0-9_.]*)\\s*}}");
  private final GatewayStore store;
  private final CredentialService credentials;
  private final UpstreamHttp http;
  private final NetworkPolicy network;

  public CustomHttpAdapter(
      GatewayStore store, CredentialService credentials, UpstreamHttp http, NetworkPolicy network) {
    this.store = store;
    this.credentials = credentials;
    this.http = http;
    this.network = network;
  }

  public boolean supports(String platform) {
    return platform.equals("custom");
  }

  public Object invoke(OperationDefinition op, Map<String, Object> params, String system) {
    var api =
        store.one("SELECT * FROM gateway_custom_api WHERE `key`=? AND enabled=true", op.key());
    if (api == null) throw GatewayException.missing("自定义接口不存在或已停用");
    Map<String, Object> module =
        JsonUtils.str(api, "module_key").isBlank()
            ? null
            : store.one(
                "SELECT * FROM gateway_module WHERE `key`=? AND enabled=true",
                api.get("module_key"));
    if (!JsonUtils.str(api, "module_key").isBlank() && module == null)
      throw GatewayException.forbidden("模块已停用");
    Map<String, Object> config = new LinkedHashMap<>();
    if (module != null) config.putAll(module);
    boolean inherit = JsonUtils.str(api, "auth_type", "inherit").equals("inherit");
    String base = JsonUtils.str(api, "base_url");
    if (base.isBlank()) base = JsonUtils.str(config, "base_url");
    if (!inherit)
      for (String k : List.of("auth_type", "auth_name", "secret_ref")) config.put(k, api.get(k));
    Map<String, String> headers = new LinkedHashMap<>();
    if (module != null)
      JsonUtils.map(JsonUtils.parse(JsonUtils.str(module, "headers_json", "{}")))
          .forEach((k, v) -> headers.put(k, Objects.toString(template(v, params), "")));
    JsonUtils.map(JsonUtils.parse(JsonUtils.str(api, "headers_json", "{}")))
        .forEach((k, v) -> headers.put(k, Objects.toString(template(v, params), "")));
    Map<String, Object> query = new LinkedHashMap<>();
    String method = JsonUtils.str(api, "method", "GET");
    String path = replacePath(JsonUtils.str(api, "path", "/"), params);
    URI origin = URI.create(base);
    URI finalUri = URI.create(base.replaceAll("/$", "") + path);
    if (!Objects.equals(origin.getHost(), finalUri.getHost())
        || origin.getPort() != finalUri.getPort()
        || !Objects.equals(origin.getScheme(), finalUri.getScheme()))
      throw GatewayException.invalid("模板不能改变目标主机");
    if (method.equals("GET") || method.equals("DELETE")) {
      query.putAll(params);
      Matcher used = PLACEHOLDER.matcher(JsonUtils.str(api, "path"));
      while (used.find()) query.remove(used.group(1));
    }
    Object body =
        JsonUtils.str(api, "body_template").isBlank()
            ? (method.equals("GET") || method.equals("DELETE") ? null : params)
            : template(JsonUtils.parse(JsonUtils.str(api, "body_template")), params);
    applyAuth(config, headers, query, system);
    var page = JsonUtils.map(JsonUtils.parse(JsonUtils.str(api, "pagination_json", "{}")));
    String mode = JsonUtils.str(page, "mode", "none");
    if (mode.equals("none")) {
      var result = request(method, finalUri.toString(), headers, query, body, op.timeoutSeconds());
      return JsonUtils.obj(
          "status_code",
          result.status(),
          "data",
          extract(result.body(), JsonUtils.str(api, "response_path")),
          "mock",
          false);
    }
    int max = Math.max(1, Math.min(50, JsonUtils.integer(page.get("max_pages"), 10))),
        size =
            Math.max(
                1,
                Math.min(
                    1000,
                    JsonUtils.integer(
                        page.get("size"), JsonUtils.integer(page.get("page_size"), 50)))),
        startPage = JsonUtils.integer(page.get("start"), 1),
        status = 200;
    String pageParam = JsonUtils.str(page, "param", JsonUtils.str(page, "page_param", "page"));
    List<Object> items = new ArrayList<>();
    Set<String> cursors = new HashSet<>();
    Object cursor = null;
    boolean more = true;
    int pages = 0;
    for (int n = 1; n <= max; n++) {
      Map<String, Object> parameters = new LinkedHashMap<>(query);
      String cursorParam = JsonUtils.str(page, "cursor_param", "cursor");
      if (mode.equals("page")) {
        parameters.put(pageParam, startPage + n - 1);
        parameters.put(JsonUtils.str(page, "size_param", "page_size"), size);
      } else if (cursor != null) parameters.put(cursorParam, cursor);
      Object pageBody = body;
      if (body instanceof Map<?, ?>) {
        var b = new LinkedHashMap<>(JsonUtils.map(body));
        if (mode.equals("page")) {
          b.put(pageParam, startPage + n - 1);
          b.put(JsonUtils.str(page, "size_param", "page_size"), size);
        } else if (cursor != null) b.put(cursorParam, cursor);
        pageBody = b;
      }
      var result =
          request(method, finalUri.toString(), headers, parameters, pageBody, op.timeoutSeconds());
      status = result.status();
      List<Object> batch =
          JsonUtils.list(
              JsonUtils.at(result.body(), JsonUtils.str(page, "items_path", "data.items")));
      items.addAll(batch);
      pages = n;
      if (items.size() > 100000) throw GatewayException.invalid("自动翻页结果超过 100000 条");
      if (mode.equals("page")) more = batch.size() >= size;
      else {
        cursor =
            JsonUtils.at(
                result.body(),
                JsonUtils.str(
                    page, "next_path", JsonUtils.str(page, "next_cursor_path", "next_cursor")));
        more = !batch.isEmpty() && cursor != null && !cursor.toString().isBlank();
        if (more && !cursors.add(cursor.toString()))
          throw new GatewayException(502, "upstream_error", "上游重复返回相同分页游标", false);
      }
      if (!more) break;
    }
    return JsonUtils.obj(
        "status_code",
        status,
        "data",
        JsonUtils.obj(
            "items",
            items,
            "count",
            items.size(),
            "pages",
            pages,
            "truncated",
            more,
            "pagination",
            JsonUtils.obj("mode", mode, "max_pages", max)),
        "mock",
        false);
  }

  private HttpResult request(
      String method,
      String url,
      Map<String, String> headers,
      Map<String, Object> query,
      Object body,
      double timeout) {
    String finalUrl = UpstreamHttp.query(url, query);
    var addresses = network.resolve(finalUrl);
    var response =
        http.exchange(
            method,
            finalUrl,
            headers,
            body == null ? null : JsonUtils.write(body).getBytes(StandardCharsets.UTF_8),
            timeout,
            addresses);
    String text = new String(response.body(), StandardCharsets.UTF_8);
    Object payload = null;
    if (!text.isBlank())
      try {
        payload = JsonUtils.parse(text);
      } catch (GatewayException e) {
        payload = text;
      }
    return new HttpResult(response.status(), payload);
  }

  private String secret(String ref, String system) {
    if (!ref.matches("CUSTOM_[A-Z0-9_]{1,57}")) throw GatewayException.invalid("仅允许引用 CUSTOM_ 凭据");
    return credentials.required(ref, system);
  }

  private void applyAuth(
      Map<String, Object> c,
      Map<String, String> headers,
      Map<String, Object> query,
      String system) {
    String auth = JsonUtils.str(c, "auth_type", "none");
    String value =
        Set.of("bearer", "basic", "header", "query").contains(auth)
            ? secret(JsonUtils.str(c, "secret_ref"), system)
            : "";
    switch (auth) {
      case "bearer" -> headers.put("Authorization", "Bearer " + value);
      case "basic" ->
          headers.put(
              "Authorization",
              "Basic "
                  + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
      case "header" -> headers.put(JsonUtils.str(c, "auth_name"), value);
      case "query" -> query.put(JsonUtils.str(c, "auth_name"), value);
      case "oauth2_refresh" -> {
        String tokenUrl = JsonUtils.str(c, "token_url");
        var form =
            Map.of(
                "grant_type",
                "refresh_token",
                "client_id",
                secret(JsonUtils.str(c, "client_id_ref"), system),
                "client_secret",
                secret(JsonUtils.str(c, "client_secret_ref"), system),
                "refresh_token",
                secret(JsonUtils.str(c, "refresh_token_ref"), system));
        String encoded =
            form.entrySet().stream()
                .map(
                    e ->
                        UpstreamHttp.segment(e.getKey()) + "=" + UpstreamHttp.segment(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        var token =
            JsonUtils.read(
                new String(
                    http.raw(
                        "POST",
                        tokenUrl,
                        Map.of("Content-Type", "application/x-www-form-urlencoded"),
                        encoded.getBytes(StandardCharsets.UTF_8),
                        30,
                        network.resolve(tokenUrl)),
                    StandardCharsets.UTF_8));
        if (JsonUtils.str(token, "access_token").isBlank())
          throw GatewayException.config("OAuth2 未返回 access_token");
        headers.put("Authorization", "Bearer " + token.get("access_token"));
      }
      default -> {}
    }
  }

  public static Object template(Object value, Map<String, Object> params) {
    if (value instanceof Map<?, ?> m) {
      var out = new LinkedHashMap<String, Object>();
      m.forEach((k, v) -> out.put(k.toString(), template(v, params)));
      return out;
    }
    if (value instanceof List<?> l) return l.stream().map(x -> template(x, params)).toList();
    if (!(value instanceof String s)) return value;
    Matcher matcher = PLACEHOLDER.matcher(s);
    if (matcher.matches()) return JsonUtils.at(params, matcher.group(1));
    StringBuffer out = new StringBuffer();
    while (matcher.find())
      matcher.appendReplacement(
          out,
          Matcher.quoteReplacement(Objects.toString(JsonUtils.at(params, matcher.group(1)), "")));
    matcher.appendTail(out);
    return out.toString();
  }

  private String replacePath(String path, Map<String, Object> params) {
    Matcher matcher = PLACEHOLDER.matcher(path);
    StringBuffer out = new StringBuffer();
    while (matcher.find())
      matcher.appendReplacement(
          out,
          Matcher.quoteReplacement(UpstreamHttp.segment(JsonUtils.at(params, matcher.group(1)))));
    matcher.appendTail(out);
    return out.toString();
  }

  private Object extract(Object result, String path) {
    return path.isBlank() ? result : JsonUtils.at(result, path);
  }
}

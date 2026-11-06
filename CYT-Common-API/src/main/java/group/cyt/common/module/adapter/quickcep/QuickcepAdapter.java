package group.cyt.common.module.adapter.quickcep;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.PlatformAdapter;
import group.cyt.common.module.adapter.support.SignatureSupport;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.model.OperationDefinition;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class QuickcepAdapter implements PlatformAdapter {
  private final CredentialService credentials;
  private final UpstreamHttp http;

  public QuickcepAdapter(CredentialService credentials, UpstreamHttp http) {
    this.credentials = credentials;
    this.http = http;
  }

  public boolean supports(String platform) {
    return platform.equals("quickcep");
  }

  public Object invoke(OperationDefinition op, Map<String, Object> p, String system) {
    String account = JsonUtils.str(p, "account");
    if (account.isBlank()) account = credentials.resolve("QUICKCEP_ACCOUNT", system);
    if (account.isBlank()) throw GatewayException.config("请指定 QuickCEP 账号");
    String prefix = "QUICKCEP_" + CredentialService.accountKey(account);
    String access = credentials.required(prefix + "_ACCESS_ID", system),
        pub = credentials.required(prefix + "_PUBLIC_KEY", system);
    long ts = Instant.now().getEpochSecond();
    Map<String, String> headers =
        Map.of(
            "quick-access-id",
            access,
            "quick-timestamp",
            Long.toString(ts),
            "quick-token",
            SignatureSupport.quickcep(pub, access, ts));
    String key = op.key().substring(9), path;
    Map<String, Object> body;
    switch (key) {
      case "sessions.list" -> {
        if (JsonUtils.str(p, "create_date").isBlank() && JsonUtils.str(p, "close_date").isBlank())
          throw GatewayException.invalid("create_date 与 close_date 至少要给一个");
        path = "getChatSubSessionIdList";
        body =
            JsonUtils.obj(
                "state", p.get("state"), "channel", p.get("channel"), "pageNo", p.get("page_no"));
        if (!JsonUtils.str(p, "create_date").isBlank())
          body.put("createDate", p.get("create_date"));
        if (!JsonUtils.str(p, "close_date").isBlank()) body.put("closeDate", p.get("close_date"));
      }
      case "sessions.get" -> {
        path = "getChatSubSessionExtended";
        body = JsonUtils.obj("id", p.get("session_id"));
      }
      case "messages.list" -> {
        path = "pageQueryChatSubSessionMessageList";
        body =
            JsonUtils.obj(
                "chatSubSessionId",
                p.get("session_id"),
                "pageNum",
                p.get("page"),
                "pageSize",
                p.get("page_size"));
      }
      case "staff.list" -> {
        path = "queryStaffInfo";
        body = new LinkedHashMap<>();
      }
      case "analytics.get" -> {
        path = "chatStatistics";
        body = JsonUtils.obj("startTime", p.get("start"), "endTime", p.get("end"));
        if (!JsonUtils.list(p.get("channels")).isEmpty()) body.put("channels", p.get("channels"));
      }
      case "sessions.by_operator" -> {
        path = "listChatSubSessions";
        body =
            JsonUtils.obj(
                "userId",
                JsonUtils.str(p, "user_id"),
                "sortType",
                "visitorSendLastMsgTimeDesc",
                "pageNum",
                p.get("page"),
                "pageSize",
                p.get("page_size"),
                "chatType",
                p.get("chat_type"));
      }
      case "sessions.close" -> {
        path = "closeChatSubSession";
        body = JsonUtils.obj("chatSubSessionId", p.get("session_id"));
      }
      case "sessions.create" -> {
        path = "getOrCreateChatSubSession";
        body = JsonUtils.obj("channel", p.get("channel"), "mode", p.get("mode"));
        if (!JsonUtils.str(p, "email").isBlank())
          body.put(
              "visitorInfo",
              JsonUtils.obj("email", p.get("email"), "phone", "", "name", p.get("name")));
        if (!JsonUtils.list(p.get("tags")).isEmpty()) body.put("subSessionTags", p.get("tags"));
        if (!JsonUtils.str(p, "third_session_id").isBlank())
          body.put("thirdSessionId", p.get("third_session_id"));
      }
      case "messages.send" -> {
        boolean visitor = JsonUtils.bool(p.get("as_visitor"));
        path = visitor ? "visitorSendMessage" : "operatorSendMessage";
        var content = JsonUtils.obj("content", p.get("text"));
        body =
            JsonUtils.obj(
                "chatSubSessionId",
                p.get("session_id"),
                "channel",
                p.get("channel"),
                "contentType",
                "text",
                "content",
                content);
        if (visitor) body.put("createTime", System.currentTimeMillis());
        else {
          if (JsonUtils.str(p, "owner_id").isBlank())
            throw GatewayException.invalid("坐席发送必须指定 owner_id");
          body.put("ownerId", Long.parseLong(JsonUtils.str(p, "owner_id")));
          if (p.get("channel").equals("email")) content.put("from", p.get("from_addr"));
        }
        if (!JsonUtils.str(p, "mid").isBlank()) body.put("mid", p.get("mid"));
      }
      default -> throw GatewayException.config("未知 QuickCEP 操作: " + key);
    }
    String base = credentials.resolve("QUICKCEP_DOMAIN", system);
    if (base.isBlank()) base = "https://app.quickcep.com";
    var response =
        JsonUtils.map(
            http.json(
                "POST",
                base.replaceAll("/$", "") + "/channel/chat/v1/" + path,
                headers,
                body,
                op.timeoutSeconds()));
    String code = JsonUtils.str(response, "code");
    if (Boolean.FALSE.equals(response.get("success"))
        || !code.isBlank() && !Set.of("0", "200").contains(code))
      throw new GatewayException(502, "upstream_error", "QuickCEP 业务调用失败", false).code(code);
    Object data = response.get("data");
    var r = JsonUtils.map(data);
    return switch (key) {
      case "sessions.list" ->
          JsonUtils.obj(
              "ids",
              JsonUtils.list(r.get("chatSubSessionIdList")).stream().map(Object::toString).toList(),
              "valid_ids",
              JsonUtils.list(r.get("validChatSubSessionIdList")).stream()
                  .map(Object::toString)
                  .toList(),
              "page_no",
              r.get("pageNo"));
      case "sessions.get", "sessions.create" ->
          JsonUtils.obj("session", data == null ? Map.of() : data);
      case "messages.list" ->
          JsonUtils.obj(
              "messages",
              r.getOrDefault("list", List.of()),
              "total",
              r.getOrDefault("total", 0),
              "page",
              r.getOrDefault("pageNum", p.get("page")),
              "total_page",
              r.getOrDefault("totalPage", 1));
      case "staff.list" -> JsonUtils.obj("staff", data == null ? List.of() : data);
      case "analytics.get" -> JsonUtils.obj("items", data == null ? List.of() : data);
      case "sessions.by_operator" ->
          JsonUtils.obj(
              "records",
              r.getOrDefault("records", List.of()),
              "total",
              r.getOrDefault("total", 0),
              "page",
              r.getOrDefault("currentPage", p.get("page")),
              "total_page",
              r.getOrDefault("totalPage", 1),
              "has_next",
              JsonUtils.bool(r.get("hasNextPage")));
      default -> JsonUtils.obj("result", data == null ? Map.of() : data);
    };
  }
}

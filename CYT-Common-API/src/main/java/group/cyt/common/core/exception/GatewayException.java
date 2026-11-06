package group.cyt.common.core.exception;

import group.cyt.common.core.util.JsonUtils;
import java.util.*;

public class GatewayException extends RuntimeException {
  public final int status;
  public final String type;
  public final boolean retryable;
  public String trace;
  public String code;
  public Object detail;
  public Integer retryAfter;

  public GatewayException(int status, String type, String message, boolean retryable) {
    super(message);
    this.status = status;
    this.type = type;
    this.retryable = retryable;
  }

  public GatewayException detail(Object d) {
    detail = d;
    return this;
  }

  public GatewayException code(String c) {
    code = c;
    return this;
  }

  public Map<String, Object> payload() {
    Map<String, Object> e =
        JsonUtils.obj("type", type, "message", getMessage(), "retryable", retryable);
    if (trace != null) e.put("trace_id", trace);
    if (code != null) e.put("code", code);
    if (detail != null) e.put("detail", detail);
    if (retryAfter != null) e.put("retry_after", retryAfter);
    return JsonUtils.obj("ok", false, "error", e);
  }

  public static GatewayException invalid(String message) {
    return new GatewayException(422, "invalid_params", message, false);
  }

  public static GatewayException forbidden(String message) {
    return new GatewayException(403, "forbidden", message, false);
  }

  public static GatewayException unauth(String message) {
    return new GatewayException(401, "unauthenticated", message, false);
  }

  public static GatewayException missing(String message) {
    return new GatewayException(404, "not_found", message, false);
  }

  public static GatewayException config(String message) {
    return new GatewayException(503, "not_configured", message, false);
  }
}

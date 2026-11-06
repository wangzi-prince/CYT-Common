package group.cyt.common.core.exception;

import group.cyt.common.core.util.JsonUtils;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(GatewayException.class)
  public ResponseEntity<?> gateway(GatewayException e) {
    if (e.trace == null) e.trace = UUID.randomUUID().toString().replace("-", "");
    var response = ResponseEntity.status(e.status).header("X-Trace-Id", e.trace);
    if (e.retryAfter != null) response.header("Retry-After", e.retryAfter.toString());
    return response.body(e.payload());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    return gateway(
        GatewayException.invalid("请求参数无效")
            .detail(
                e.getBindingResult().getFieldErrors().stream()
                    .map(
                        f -> JsonUtils.obj("field", f.getField(), "message", f.getDefaultMessage()))
                    .toList()));
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
  public ResponseEntity<?> invalid(Exception e) {
    return gateway(GatewayException.invalid("请求格式或参数无效"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<?> conflict(DataIntegrityViolationException e) {
    return gateway(new GatewayException(409, "conflict", "数据重复或不符合约束", false));
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  public ResponseEntity<?> notFound(Exception e) {
    return gateway(GatewayException.missing("接口或资源不存在"));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> method(Exception e) {
    return gateway(new GatewayException(405, "method_not_allowed", "HTTP 方法不支持", false));
  }

  @ExceptionHandler({
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<?> parameter(Exception e) {
    return gateway(GatewayException.invalid("请求参数类型无效或缺少必填参数"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    String trace = UUID.randomUUID().toString().replace("-", "");
    log.error("Unhandled request failure trace={} type={}", trace, e.getClass().getSimpleName());
    var ex = new GatewayException(500, "internal_error", "服务暂时无法完成请求", false);
    ex.trace = trace;
    return gateway(ex);
  }
}

package group.cyt.common.module.auth.controller;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.auth.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api/auth")
public class AuthenticationController {
  public record LoginRequest(
      @NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {}

  private final AuthenticationService authentication;
  private final GatewaySettings settings;

  public AuthenticationController(AuthenticationService authentication, GatewaySettings settings) {
    this.authentication = authentication;
    this.settings = settings;
  }

  @GetMapping("/config")
  public Object config() {
    return JsonUtils.obj(
        "needs_login",
        true,
        "dev_login",
        settings.devLogin(),
        "env",
        settings.current(),
        "session_hours",
        JsonUtils.integer(settings.get("ADMIN_SESSION_HOURS", "8"), 8));
  }

  @PostMapping("/login")
  public Object login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
    return authentication.login(body.username(), body.password(), request.getRemoteAddr());
  }

  @PostMapping("/dev-login")
  public Object devLogin() {
    if (!settings.devLogin()) throw GatewayException.missing("当前环境未开启免密登录");
    return authentication.session("dev@local");
  }

  @GetMapping("/session")
  public Object session(HttpServletRequest request) {
    return JsonUtils.obj(
        "actor",
        AuthenticationService.actor(request),
        "expires_in",
        JsonUtils.integer(settings.get("ADMIN_SESSION_HOURS", "8"), 8) * 3600);
  }
}

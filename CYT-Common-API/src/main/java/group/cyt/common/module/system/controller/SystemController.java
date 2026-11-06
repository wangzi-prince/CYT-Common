package group.cyt.common.module.system.controller;

import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.auth.service.AuthenticationService;
import group.cyt.common.module.system.service.SystemService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api")
public class SystemController {
  public record SystemRequest(
      @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{0,31}") String key,
      @NotBlank @Size(max = 64) String display_name,
      @Size(max = 255) String description) {}

  public record KeyRequest(@Size(max = 64) String name) {}

  private final SystemService service;

  public SystemController(SystemService service) {
    this.service = service;
  }

  @GetMapping("/systems")
  public Object list() {
    return JsonUtils.obj("systems", service.list());
  }

  @PostMapping("/systems")
  public Object create(@Valid @RequestBody SystemRequest body, HttpServletRequest req) {
    return JsonUtils.obj(
        "ok",
        true,
        "id",
        service.create(
            body.key(),
            body.display_name(),
            body.description() == null ? "" : body.description(),
            AuthenticationService.actor(req)));
  }

  @PostMapping("/systems/{key}/toggle")
  public Object toggle(@PathVariable String key, HttpServletRequest req) {
    return JsonUtils.obj(
        "ok", true, "enabled", service.toggle(key, AuthenticationService.actor(req)));
  }

  @GetMapping("/systems/{key}/apikeys")
  public Object keys(@PathVariable String key) {
    return JsonUtils.obj("items", service.keys(key));
  }

  @PostMapping("/systems/{key}/apikeys")
  public Object key(
      @PathVariable String key, @Valid @RequestBody KeyRequest body, HttpServletRequest req) {
    return service.createKey(
        key, body.name() == null ? "" : body.name(), AuthenticationService.actor(req));
  }

  @DeleteMapping("/apikeys/{id}")
  public Object revoke(@PathVariable long id, HttpServletRequest req) {
    service.revokeKey(id, AuthenticationService.actor(req));
    return JsonUtils.obj("ok", true);
  }
}

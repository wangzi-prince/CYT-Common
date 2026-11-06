package group.cyt.common.core.controller;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.util.JsonUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public class FrontendController {
  private final GatewaySettings settings;

  public FrontendController(GatewaySettings settings) {
    this.settings = settings;
  }

  @GetMapping({"/", "/api-reference"})
  public String index() {
    return "forward:/index.html";
  }

  @RequestMapping(
      value = "/internal/echo",
      method = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.DELETE
      })
  @ResponseBody
  public Object echo(
      @RequestParam Map<String, String> query,
      @RequestBody(required = false) Object body,
      HttpServletRequest request) {
    if (settings.production() || !settings.flag("CUSTOM_API_ALLOW_LOOPBACK", false))
      throw GatewayException.missing("接口不存在");
    return JsonUtils.obj("method", request.getMethod(), "query", query, "body", body);
  }
}

package group.cyt.common.core.config;

import group.cyt.common.module.auth.service.AuthenticationService;
import jakarta.servlet.http.*;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final AuthenticationService authentication;
  private final GatewaySettings settings;

  public WebConfiguration(AuthenticationService authentication, GatewaySettings settings) {
    this.authentication = authentication;
    this.settings = settings;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(
        new HandlerInterceptor() {
          @Override
          public boolean preHandle(
              HttpServletRequest req, HttpServletResponse res, Object handler) {
            res.setHeader("X-Content-Type-Options", "nosniff");
            res.setHeader("Referrer-Policy", "no-referrer");
            res.setHeader("Cache-Control", "no-store");
            String path = req.getRequestURI();
            if (path.startsWith("/admin/api/")
                && !Set.of(
                        "/admin/api/auth/config",
                        "/admin/api/auth/login",
                        "/admin/api/auth/dev-login")
                    .contains(path)
                && !req.getMethod().equals("OPTIONS"))
              req.setAttribute("admin.actor", authentication.admin(req));
            return true;
          }
        });
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/**")
        .allowedOrigins(
            settings
                .csv("CORS_ALLOW_ORIGINS", "http://localhost:8190,http://127.0.0.1:8190")
                .toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders(
            "Content-Type",
            "Authorization",
            "Idempotency-Key",
            "X-Trace-Id",
            "X-System-Id",
            "X-Admin-Token")
        .maxAge(600);
  }
}

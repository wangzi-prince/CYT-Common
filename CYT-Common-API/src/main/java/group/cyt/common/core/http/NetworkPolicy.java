package group.cyt.common.core.http;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import java.net.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class NetworkPolicy {
  private final GatewaySettings settings;

  public NetworkPolicy(GatewaySettings settings) {
    this.settings = settings;
  }

  /** Returned addresses must be used by the HTTP connection, preventing DNS rebinding. */
  public List<InetAddress> resolve(String url) {
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (host == null || uri.getUserInfo() != null || uri.getFragment() != null)
        throw GatewayException.invalid("URL 必须包含合法域名且不能包含用户信息或片段");
      boolean local =
          !settings.production()
              && settings.flag("CUSTOM_API_ALLOW_LOOPBACK", false)
              && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host);
      boolean trustedInternal =
          settings.csv("INTERNAL_API_ALLOWED_ORIGINS", "").stream()
              .anyMatch(origin -> sameOrigin(uri, origin));
      if (!uri.getScheme().equals("https")
          && !((local || trustedInternal) && uri.getScheme().equals("http")))
        throw GatewayException.invalid("自定义接口必须使用 HTTPS");
      var allowed = settings.csv("CUSTOM_API_ALLOWED_HOSTS", "");
      if (!allowed.isEmpty() && !allowed.contains(host.toLowerCase(Locale.ROOT)))
        throw GatewayException.forbidden("域名不在自定义接口白名单内");
      List<InetAddress> addresses = List.of(InetAddress.getAllByName(host));
      for (var a : addresses)
        if (!local && privateAddress(a) && !(trustedInternal && internalServiceAddress(a)))
          throw GatewayException.forbidden("此服务地址尚未获准连接；内部服务需配置可信来源，回环与元数据地址禁止访问");
      return addresses;
    } catch (GatewayException e) {
      throw e;
    } catch (Exception e) {
      throw GatewayException.invalid("URL 无效或域名无法解析");
    }
  }

  private static boolean sameOrigin(URI target, String configured) {
    try {
      URI origin = URI.create(configured);
      if (origin.getHost() == null
          || origin.getUserInfo() != null
          || origin.getQuery() != null
          || origin.getFragment() != null
          || origin.getPath() != null && !Set.of("", "/").contains(origin.getPath())) return false;
      int targetPort =
          target.getPort() < 0 ? target.getScheme().equals("https") ? 443 : 80 : target.getPort();
      int originPort =
          origin.getPort() < 0 ? origin.getScheme().equals("https") ? 443 : 80 : origin.getPort();
      return origin.getScheme().equalsIgnoreCase(target.getScheme())
          && origin.getHost().equalsIgnoreCase(target.getHost())
          && originPort == targetPort;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static boolean internalServiceAddress(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isMulticastAddress()) return false;
    byte[] bytes = address.getAddress();
    return address.isSiteLocalAddress() || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
  }

  public static boolean privateAddress(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) return true;
    byte[] b = address.getAddress();
    if (b.length == 4) {
      int x = b[0] & 255, y = b[1] & 255;
      return x == 0
          || x == 127
          || x >= 224
          || x == 100 && y >= 64 && y <= 127
          || x == 192 && y == 0
          || x == 198 && (y == 18 || y == 19);
    }
    return (b[0] & 0xfe) == 0xfc;
  }
}

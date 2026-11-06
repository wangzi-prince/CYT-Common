package group.cyt.common.module.adapter.support;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TestWritePolicy {
  /**
   * A platform not listed here derives {@code GATEWAY_TEST_<PLATFORM>_ACCOUNTS}. The explicit
   * entries exist because one shared fallback variable is a trap: the Python gateway chose the list
   * with a Gmail-or-Shopify ternary, Lingxing fell onto the Gmail side, and its calls stayed
   * Forbidden after operators had already whitelisted the right accounts.
   */
  private static final Map<String, String> TARGET_ENV =
      Map.of(
          "shopify", "GATEWAY_TEST_SHOPIFY_STORES",
          "gmail", "GATEWAY_TEST_GMAIL_ACCOUNTS",
          "lingxing", "GATEWAY_TEST_LINGXING_ACCOUNTS",
          // Winit writes are gated by the return destination warehouse — the only handle on where
          // the parcel actually goes — not by an account.
          "winit", "GATEWAY_TEST_WINIT_WAREHOUSES");

  private final GatewaySettings settings;

  public TestWritePolicy(GatewaySettings settings) {
    this.settings = settings;
  }

  public void requireTarget(String platform, String target) {
    String name =
        TARGET_ENV.getOrDefault(platform, "GATEWAY_TEST_" + platform.toUpperCase(Locale.ROOT) + "_ACCOUNTS");
    if (!settings.flag("GATEWAY_ENABLE_TEST_WRITES", false)
        || target.isBlank()
        || settings.csv(name, "").stream().noneMatch(target::equalsIgnoreCase))
      throw GatewayException.forbidden("正式业务数据只读：写入只允许显式配置的测试账号");
  }
}

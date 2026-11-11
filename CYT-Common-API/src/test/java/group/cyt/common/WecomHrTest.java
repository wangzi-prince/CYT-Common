package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import group.cyt.common.core.http.UpstreamHttp;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.wecom.WecomAdapter;
import group.cyt.common.module.adapter.support.TokenCache;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.gateway.service.CallLogService;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;

class WecomHrTest {
  @Test void rosterReadsUseHrSecretAndOnlyRequestedFields() throws Exception {
    var registry = new OperationRegistry(mock(GatewayStore.class));
    var credentials = mock(CredentialService.class);
    when(credentials.resolve(anyString(), eq("oa"))).thenReturn("");
    when(credentials.required("WEWORK_SECRET", "oa")).thenReturn("app-secret-fixture");
    when(credentials.required("WEWORK_CORP_ID", "oa")).thenReturn("corp-fixture");
    when(credentials.resolve("WEWORK_HR_SECRET", "oa")).thenReturn("hr-secret-fixture");
    var http = mock(UpstreamHttp.class);
    when(http.object(eq("GET"), anyString(), anyMap(), isNull()))
        .thenReturn(JsonUtils.obj("errcode", 0, "access_token", "hr-token-fixture", "expires_in", 7200));
    when(http.json(eq("POST"), anyString(), anyMap(), any(), anyDouble()))
        .thenReturn(JsonUtils.obj("errcode", 0, "field_info", List.of()));
    var adapter = new WecomAdapter(credentials, http, new TokenCache(), registry);
    var op = registry.get("wecom.hr.get_staff_info");
    assertTrue(op.readOnly()); assertTrue(op.sensitive());
    var fields = List.of(JsonUtils.obj("fieldid", 7, "sub_idx", 0));
    adapter.invoke(op, registry.validate(op, JsonUtils.obj("userid", "Alice", "fieldids", fields)), "oa");
    verify(http).object(eq("GET"), contains("corpsecret=hr-secret-fixture"), anyMap(), isNull());
    var body = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(http).json(eq("POST"), contains("/hr/get_staff_info?access_token=hr-token-fixture"), anyMap(), body.capture(), anyDouble());
    assertEquals(false, JsonUtils.map(body.getValue()).get("get_all"));
    assertEquals(fields, JsonUtils.map(body.getValue()).get("fieldids"));
  }
  @Test void rosterSchemaRejectsBroadReadsAndLogsHideValues() throws Exception {
    var registry = new OperationRegistry(mock(GatewayStore.class));
    var op = registry.get("wecom.hr.get_staff_info");
    assertThrows(RuntimeException.class, () -> registry.validate(op, JsonUtils.obj("userid", "Alice", "get_all", true)));
    var logs = new CallLogService(mock(GatewayStore.class));
    String masked = JsonUtils.write(logs.redact(JsonUtils.obj("field_info", List.of(JsonUtils.obj("value_uint64", 1234567)))));
    assertFalse(masked.contains("1234567"));
  }
}

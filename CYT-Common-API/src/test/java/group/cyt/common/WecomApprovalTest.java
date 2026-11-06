package group.cyt.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.cyt.common.core.config.GatewaySettings;
import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.adapter.support.CursorCodec;
import group.cyt.common.module.adapter.wecom.approval.*;
import group.cyt.common.module.credential.service.CredentialService;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class WecomApprovalTest {
  private final WecomApprovalClient client = mock(WecomApprovalClient.class);
  private final GatewayStore store = mock(GatewayStore.class);
  private final GatewaySettings settings =
      new GatewaySettings(
          new MockEnvironment().withProperty("ADMIN_JWT_SECRET", "fixture-cursor-signing-key"));
  private final WecomApprovalService service =
      new WecomApprovalService(
          client, store, settings, new CursorCodec(settings), mock(CredentialService.class));

  private OperationRegistry registry() throws Exception {
    return new OperationRegistry(store);
  }

  @Test
  void businessRequestTrackingChecksOwnershipAndRestoresStepsAndNestedBusinessFields()
      throws Exception {
    when(client.profile("caller")).thenReturn("profile-A");
    when(store.one(
            contains("gateway_wecom_submission"),
            eq(settings.current()),
            eq("live"),
            eq("caller"),
            eq("business-42")))
        .thenReturn(JsonUtils.obj("approval_no", "SP-42", "profile", "profile-A"));
    when(store.rows(
            contains("SELECT alias,field_aliases"),
            eq(settings.current()),
            eq("live"),
            eq("caller"),
            eq("tpl")))
        .thenReturn(
            List.of(
                JsonUtils.obj(
                    "alias",
                    "expense",
                    "field_aliases",
                    "{\"reason\":\"Text-1\",\"lines\":\"Table-1\",\"sku\":\"Text-2\"}")));
    var child = JsonUtils.obj("id", "Text-2", "control", "Text", "value", Map.of("text", "SKU-A"));
    var fields =
        List.of(
            JsonUtils.obj("id", "Text-1", "control", "Text", "value", Map.of("text", "费用")),
            JsonUtils.obj(
                "id",
                "Table-1",
                "control",
                "Table",
                "value",
                Map.of("children", List.of(Map.of("list", List.of(child))))));
    var nodes =
        List.of(
            JsonUtils.obj("sp_status", 4, "approverattr", 1, "details", List.of()),
            JsonUtils.obj(
                "sp_status",
                1,
                "approverattr",
                2,
                "details",
                List.of(
                    JsonUtils.obj(
                        "approver",
                        Map.of("userid", "reviewer"),
                        "sp_status",
                        1,
                        "speech",
                        "",
                        "sptime",
                        0))));
    when(client.post(eq("/oa/getapprovaldetail"), anyMap(), eq("caller")))
        .thenReturn(
            Map.of(
                "info",
                JsonUtils.obj(
                    "sp_no",
                    "SP-42",
                    "template_id",
                    "tpl",
                    "sp_status",
                    1,
                    "sp_record",
                    nodes,
                    "apply_data",
                    Map.of("contents", fields))));
    var result =
        JsonUtils.map(
            invoke("wecom.approvals.track", Map.of("request_id", "business-42"), "caller", false));
    assertEquals("SP-42", result.get("approval_no"));
    assertEquals(List.of("reviewer"), result.get("pending_approvers"));
    assertEquals(
        "transferred", JsonUtils.map(JsonUtils.list(result.get("steps")).getFirst()).get("status"));
    var values =
        JsonUtils.map(
            JsonUtils.map(JsonUtils.list(result.get("business_forms")).getFirst()).get("values"));
    assertEquals("费用", values.get("reason"));
    assertEquals(List.of(Map.of("sku", "SKU-A")), values.get("lines"));
    assertEquals(
        404,
        assertThrows(
                GatewayException.class,
                () ->
                    invoke(
                        "wecom.approvals.track",
                        Map.of("request_id", "business-42"),
                        "other",
                        false))
            .status);
    when(client.profile("caller")).thenReturn("profile-B");
    assertEquals(
        409,
        assertThrows(
                GatewayException.class,
                () ->
                    invoke(
                        "wecom.approvals.track",
                        Map.of("request_id", "business-42"),
                        "caller",
                        false))
            .status);
    verify(client, times(1)).post(eq("/oa/getapprovaldetail"), anyMap(), anyString());
  }

  @Test
  void vacationPreflightRejectsUnknownTypeOrMismatchedUnitBeforeAnyWrite() throws Exception {
    when(client.post(eq("/oa/vacation/getcorpconf"), anyMap(), eq("caller")))
        .thenReturn(Map.of("lists", List.of(JsonUtils.obj("id", 1, "name", "年假", "time_attr", 0))));
    var params =
        JsonUtils.obj(
            "userid",
            "user",
            "vacation_id",
            1,
            "remaining",
            1.5,
            "unit",
            "hours",
            "confirm",
            true,
            "request_id",
            "quota-1");
    assertThrows(
        GatewayException.class, () -> invoke("wecom.vacation.set", params, "caller", false));
    params.put("vacation_id", 2);
    assertThrows(
        GatewayException.class, () -> invoke("wecom.vacation.set", params, "caller", false));
    verify(client, never()).post(eq("/oa/vacation/setoneuserquota"), anyMap(), anyString());
    params.put("vacation_id", 1);
    params.put("unit", "days");
    when(client.post(eq("/oa/vacation/setoneuserquota"), anyMap(), eq("caller")))
        .thenReturn(Map.of("errcode", 0));
    assertEquals(
        true, JsonUtils.map(invoke("wecom.vacation.set", params, "caller", false)).get("updated"));
    verify(client)
        .post(
            eq("/oa/vacation/setoneuserquota"),
            argThat(
                p ->
                    Objects.equals(p.get("leftduration"), 129600L)
                        && Objects.equals(p.get("time_attr"), 0)),
            eq("caller"));
  }

  private Object invoke(String key, Map<String, Object> p, String system, boolean mock)
      throws Exception {
    var r = registry();
    var op = r.get(key);
    return service.invoke(op, r.validate(op, p), system, mock);
  }

  private Map<String, Object> template(Object... controls) {
    return JsonUtils.obj(
        "template_names",
        List.of(JsonUtils.obj("text", "测试模板", "lang", "zh_CN")),
        "template_content",
        JsonUtils.obj("controls", List.of(controls)));
  }

  @Test
  void templateBuilderUsesOfficialNumericIdsAndPreservesExistingIdsOnUpdate() throws Exception {
    var fields =
        List.of(
            JsonUtils.obj("key", "reason", "label", "事由", "control", "Text", "required", true),
            JsonUtils.obj("key", "amount", "label", "金额", "control", "Money", "required", true));
    when(client.post(eq("/oa/approval/create_template"), anyMap(), eq("fixture")))
        .thenReturn(Map.of("template_id", "new-template"));
    invoke(
        "wecom.approval_templates.create",
        JsonUtils.obj("name", "费用申请", "fields", fields),
        "fixture",
        false);
    var capture = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(client).post(eq("/oa/approval/create_template"), capture.capture(), eq("fixture"));
    var created = ApprovalFormCodec.controls(capture.getValue());
    assertTrue(
        JsonUtils.str(JsonUtils.map(JsonUtils.map(created.getFirst()).get("property")), "id")
            .matches("Text-\\d+"));
    when(client.post(eq("/oa/gettemplatedetail"), anyMap(), eq("fixture")))
        .thenReturn(ApprovalSimulator.expense());
    when(client.post(eq("/oa/approval/update_template"), anyMap(), eq("fixture")))
        .thenReturn(Map.of("errcode", 0));
    invoke(
        "wecom.approval_templates.update",
        JsonUtils.obj("template", "demo-expense", "name", "费用申请", "fields", fields),
        "fixture",
        false);
    verify(client).post(eq("/oa/approval/update_template"), capture.capture(), eq("fixture"));
    var updated = ApprovalFormCodec.controls(capture.getValue());
    assertEquals(
        "Text-01",
        JsonUtils.str(JsonUtils.map(JsonUtils.map(updated.getFirst()).get("property")), "id"));
    assertEquals(
        "Money-02",
        JsonUtils.str(JsonUtils.map(JsonUtils.map(updated.get(1)).get("property")), "id"));
  }

  @Test
  void mockEventsCannotReadOrRetryLiveInboxAndUploadsStillValidate() throws Exception {
    var listed = JsonUtils.map(invoke("wecom.approval_events.list", Map.of(), "fixture", true));
    assertEquals(List.of(), listed.get("items"));
    var retry =
        JsonUtils.map(
            invoke(
                "wecom.approval_events.retry",
                Map.of("event_id", "a".repeat(64)),
                "fixture",
                true));
    assertEquals(false, retry.get("queued"));
    assertThrows(
        GatewayException.class,
        () ->
            invoke(
                "wecom.approval_files.upload",
                Map.of("filename", "../invalid.txt", "content_base64", "YQ=="),
                "fixture",
                true));
    verifyNoInteractions(client);
    verify(store, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void ambiguousTemplateDefinitionsAndLongLegacyWindowsFailBeforeUpstreamWrites() throws Exception {
    assertThrows(
        GatewayException.class,
        () ->
            invoke(
                "wecom.approval_templates.create",
                JsonUtils.obj(
                    "name",
                    "示例",
                    "fields",
                    List.of(Map.of("key", "a")),
                    "template_content",
                    Map.of()),
                "fixture",
                false));
    assertThrows(
        GatewayException.class,
        () ->
            invoke(
                "wecom.approvals.legacy_get",
                JsonUtils.obj("start_at", "2026-01-01", "end_at", "2026-02-01"),
                "fixture",
                false));
    verifyNoInteractions(client);
  }

  @Test
  void namedFieldsConvertNumbersAndSelectorLabelsWithoutGuessing() {
    var data =
        ApprovalFormCodec.encode(
            ApprovalSimulator.expense(),
            JsonUtils.obj("事由", "采购", "金额", 0, "费用类型", "办公"),
            Map.of());
    var contents = JsonUtils.list(data.get("contents"));
    assertEquals("0", JsonUtils.map(JsonUtils.map(contents.get(1)).get("value")).get("new_money"));
    var selector =
        JsonUtils.map(JsonUtils.map(JsonUtils.map(contents.get(2)).get("value")).get("selector"));
    assertEquals(
        "office", JsonUtils.map(JsonUtils.list(selector.get("options")).getFirst()).get("key"));
  }

  @Test
  void allMissingAndUnknownFieldsAreReportedTogether() {
    var error =
        assertThrows(
            GatewayException.class,
            () ->
                ApprovalFormCodec.encode(
                    ApprovalSimulator.expense(), JsonUtils.obj("typo", "value"), Map.of()));
    assertEquals(3, JsonUtils.list(error.detail).size());
  }

  @Test
  void duplicateTitlesRequireIdsAndDuplicateAssignmentsAreRejected() {
    var template =
        template(
            ApprovalSimulator.control("Text", "a", "重复", false, Map.of()),
            ApprovalSimulator.control("Text", "b", "重复", false, Map.of()));
    assertThrows(
        GatewayException.class,
        () -> ApprovalFormCodec.encode(template, JsonUtils.obj("重复", "x"), Map.of()));
    assertThrows(
        GatewayException.class,
        () ->
            ApprovalFormCodec.encode(
                ApprovalSimulator.expense(),
                JsonUtils.obj("Text-01", "x", "事由", "y", "金额", "1"),
                Map.of()));
    assertDoesNotThrow(
        () -> ApprovalFormCodec.encode(template, JsonUtils.obj("a", "x", "b", "y"), Map.of()));
  }

  @Test
  void rowsConvertNestedFieldsAndCheckRequiredChildren() {
    var table =
        ApprovalSimulator.control(
            "Table",
            "Table-lines",
            "明细",
            true,
            JsonUtils.obj(
                "table",
                JsonUtils.obj(
                    "children",
                    List.of(
                        ApprovalSimulator.control("Text", "sku", "商品", true, Map.of()),
                        ApprovalSimulator.control("Number", "qty", "数量", true, Map.of())))));
    var template = template(table);
    assertThrows(
        GatewayException.class,
        () ->
            ApprovalFormCodec.encode(
                template, JsonUtils.obj("明细", List.of(Map.of("商品", "a"))), Map.of()));
    var result =
        ApprovalFormCodec.encode(
            template, JsonUtils.obj("明细", List.of(JsonUtils.obj("商品", "a", "数量", 2))), Map.of());
    assertTrue(JsonUtils.write(result).contains("\"new_number\":\"2\""));
  }

  @Test
  void attachmentLimitAlsoCountsAdvancedValues() {
    var definition = template(ApprovalSimulator.control("File", "files", "附件", false, Map.of()));
    assertThrows(
        GatewayException.class,
        () ->
            ApprovalFormCodec.encode(
                definition, Map.of("files", Collections.nCopies(7, "file-id")), Map.of()));
    assertThrows(
        GatewayException.class,
        () ->
            ApprovalFormCodec.encode(
                definition,
                Map.of(
                    "files",
                    Map.of(
                        "$value",
                        Map.of("files", Collections.nCopies(7, Map.of("file_id", "id"))))),
                Map.of()));
  }

  @Test
  void durationNeverInfersWorkHoursFromWallClock() {
    var definition =
        template(ApprovalSimulator.control("DateRange", "range", "时长", true, Map.of()));
    assertThrows(
        GatewayException.class,
        () ->
            ApprovalFormCodec.encode(
                definition,
                Map.of(
                    "range", Map.of("start_at", "2026-11-05 09:00", "end_at", "2026-11-06 09:00")),
                Map.of()));
    var data =
        ApprovalFormCodec.encode(
            definition,
            Map.of(
                "range",
                Map.of(
                    "start_at",
                    "2026-11-05 09:00",
                    "end_at",
                    "2026-11-06 09:00",
                    "duration_seconds",
                    28800)),
            Map.of());
    assertTrue(JsonUtils.write(data).contains("\"new_duration\":28800"));
  }

  @Test
  void invalidNumberAndSingleLineNewlinesFailLocally() {
    assertThrows(
        GatewayException.class,
        () ->
            ApprovalFormCodec.encode(
                ApprovalSimulator.expense(), JsonUtils.obj("事由", "a\nb", "金额", "1"), Map.of()));
    assertThrows(
        GatewayException.class,
        () ->
            ApprovalFormCodec.encode(
                ApprovalSimulator.expense(), JsonUtils.obj("事由", "a", "金额", "NaN"), Map.of()));
  }

  @Test
  void computedFieldsAreNotSentAndDatesUseBeijingTimezone() {
    var definition =
        template(
            ApprovalSimulator.control("Formula", "f", "公式", true, Map.of()),
            ApprovalSimulator.control("Date", "d", "日期", false, Map.of()));
    var data = ApprovalFormCodec.encode(definition, Map.of("d", "2026-11-05"), Map.of());
    assertEquals(1, JsonUtils.list(data.get("contents")).size());
    assertEquals(1793808000L, ApprovalFormCodec.epoch("2026-11-05"));
  }

  @Test
  void prepareUsesCurrentProcessFormatAndDoesNotSubmit() throws Exception {
    when(client.post(eq("/oa/gettemplatedetail"), anyMap(), eq("caller")))
        .thenReturn(ApprovalSimulator.expense());
    var p =
        JsonUtils.obj(
            "template",
            "template-real",
            "applicant",
            "fixture-user",
            "fields",
            JsonUtils.obj("事由", "采购", "金额", "12.30"),
            "flow",
            List.of(JsonUtils.obj("users", List.of("a", "b"), "mode", "any")));
    var result = JsonUtils.map(invoke("wecom.approvals.prepare", p, "caller", false));
    assertEquals(true, result.get("valid"));
    var body = JsonUtils.map(result.get("payload"));
    assertEquals(0, body.get("use_template_approver"));
    var node =
        JsonUtils.map(
            JsonUtils.list(JsonUtils.map(body.get("process")).get("node_list")).getFirst());
    assertEquals(2, node.get("apv_rel"));
    verify(client, never()).post(eq("/oa/applyevent"), anyMap(), anyString());
  }

  @Test
  void changedTemplatesCannotBeSubmittedUsingStalePreview() throws Exception {
    when(client.post(anyString(), anyMap(), anyString())).thenReturn(ApprovalSimulator.expense());
    assertThrows(
        GatewayException.class,
        () ->
            invoke(
                "wecom.approvals.submit",
                JsonUtils.obj(
                    "template",
                    "t",
                    "template_version",
                    "stale",
                    "applicant",
                    "u",
                    "request_id",
                    "r",
                    "fields",
                    Map.of()),
                "caller",
                false));
    verify(client, never()).post(eq("/oa/applyevent"), anyMap(), anyString());
  }

  @Test
  void emptyAndShortPagesWithCursorAreNotTreatedAsEnd() throws Exception {
    when(client.post(eq("/oa/getapprovalinfo"), anyMap(), eq("caller")))
        .thenReturn(
            JsonUtils.obj("sp_no_list", List.of(), "new_next_cursor", "continue"),
            JsonUtils.obj("sp_no_list", List.of("A")));
    var result =
        JsonUtils.map(
            invoke(
                "wecom.approvals.collect",
                JsonUtils.obj("include_details", false),
                "caller",
                false));
    assertEquals(List.of("A"), result.get("approval_nos"));
    assertEquals(2, result.get("pages"));
    assertEquals(false, JsonUtils.map(result.get("page")).get("has_more"));
  }

  @Test
  void largeDateRangeSplitsAndReturnsSignedResumableCursor() throws Exception {
    when(client.post(eq("/oa/getapprovalinfo"), anyMap(), anyString()))
        .thenReturn(JsonUtils.obj("sp_no_list", List.of()));
    var p =
        JsonUtils.obj(
            "start_at",
            "2026-08-01",
            "end_at",
            "2026-11-01",
            "include_details",
            false,
            "max_pages",
            2);
    var first = JsonUtils.map(invoke("wecom.approvals.collect", p, "caller", false));
    String cursor = JsonUtils.str(JsonUtils.map(first.get("page")), "next_cursor");
    assertFalse(cursor.isBlank());
    p.put("cursor", cursor);
    var last = JsonUtils.map(invoke("wecom.approvals.collect", p, "caller", false));
    assertEquals(false, JsonUtils.map(last.get("page")).get("has_more"));
    assertThrows(
        GatewayException.class, () -> invoke("wecom.approvals.collect", p, "other", false));
    p.put("status", "approved");
    assertThrows(
        GatewayException.class, () -> invoke("wecom.approvals.collect", p, "caller", false));
  }

  @Test
  void repeatedUpstreamCursorStopsInsteadOfLooping() throws Exception {
    when(client.post(eq("/oa/getapprovalinfo"), anyMap(), anyString()))
        .thenReturn(JsonUtils.obj("sp_no_list", List.of(), "new_next_cursor", "same"));
    assertThrows(
        GatewayException.class,
        () -> invoke("wecom.approvals.collect", Map.of("include_details", false), "caller", false));
  }

  @Test
  void batchDetailsPreserveSuccessfulRecordsWhenOneFails() throws Exception {
    when(client.post(eq("/oa/getapprovaldetail"), anyMap(), eq("caller")))
        .thenAnswer(
            a -> {
              var body = JsonUtils.map(a.getArgument(1));
              if (body.get("sp_no").equals("bad")) throw GatewayException.missing("missing");
              return JsonUtils.obj("info", JsonUtils.obj("sp_no", "A", "sp_status", 2));
            });
    var result =
        JsonUtils.map(
            invoke(
                "wecom.approvals.get_many",
                Map.of("approval_nos", List.of("A", "bad")),
                "caller",
                false));
    assertEquals(1, JsonUtils.list(result.get("items")).size());
    assertEquals(1, JsonUtils.list(result.get("errors")).size());
  }

  @Test
  void officialCryptoVectorDecryptsAndRejectsTamperingOrWrongCorp() throws Exception {
    Map<String, Object> v;
    try (var in = getClass().getResourceAsStream("/wecom-crypto-vector.json")) {
      v = JsonUtils.MAPPER.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
    String plain =
        WecomEventCrypto.decrypt(
            JsonUtils.str(v, "token"),
            JsonUtils.str(v, "aes_key"),
            JsonUtils.str(v, "receiver"),
            JsonUtils.str(v, "signature"),
            JsonUtils.str(v, "timestamp"),
            JsonUtils.str(v, "nonce"),
            JsonUtils.str(v, "encrypted"));
    assertEquals(
        "hello", WecomEventCrypto.xml(plain.getBytes(StandardCharsets.UTF_8)).get("Content"));
    assertThrows(
        GatewayException.class,
        () ->
            WecomEventCrypto.decrypt(
                JsonUtils.str(v, "token"),
                JsonUtils.str(v, "aes_key"),
                "other-corp",
                JsonUtils.str(v, "signature"),
                JsonUtils.str(v, "timestamp"),
                JsonUtils.str(v, "nonce"),
                JsonUtils.str(v, "encrypted")));
    assertThrows(
        GatewayException.class,
        () ->
            WecomEventCrypto.decrypt(
                JsonUtils.str(v, "token"),
                JsonUtils.str(v, "aes_key"),
                JsonUtils.str(v, "receiver"),
                "bad",
                JsonUtils.str(v, "timestamp"),
                JsonUtils.str(v, "nonce"),
                JsonUtils.str(v, "encrypted")));
  }

  @Test
  void xmlParserRejectsExternalEntities() {
    assertThrows(
        GatewayException.class,
        () ->
            WecomEventCrypto.xml(
                "<!DOCTYPE xml [<!ENTITY x SYSTEM 'file:///private'>]><xml><Encrypt>&x;</Encrypt></xml>"
                    .getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void unknownStatusIsNotReportedAsApprovedOrTerminal() {
    assertEquals("unknown", WecomApprovalService.status(999).get("status"));
    assertEquals(false, WecomApprovalService.status(999).get("terminal"));
  }
}

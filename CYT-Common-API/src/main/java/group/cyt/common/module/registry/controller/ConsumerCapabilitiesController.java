package group.cyt.common.module.registry.controller;

import group.cyt.common.core.exception.GatewayException;
import group.cyt.common.core.persistence.GatewayStore;
import group.cyt.common.core.util.JsonUtils;
import group.cyt.common.module.grant.service.AuthorizationService;
import group.cyt.common.module.registry.service.DocumentationContracts;
import group.cyt.common.module.registry.service.OperationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api")
public class ConsumerCapabilitiesController {
  private final GatewayStore store;
  private final OperationRegistry registry;
  private final RegistryController documents;
  private final AuthorizationService authorization;
  private final DocumentationContracts contracts;

  public ConsumerCapabilitiesController(
      GatewayStore store,
      OperationRegistry registry,
      RegistryController documents,
      AuthorizationService authorization,
      DocumentationContracts contracts) {
    this.store = store;
    this.registry = registry;
    this.documents = documents;
    this.authorization = authorization;
    this.contracts = contracts;
  }

  @GetMapping("/consumer-openapi/{system}")
  public ResponseEntity<Object> export(
      @PathVariable String system, @RequestParam(defaultValue = "all") String direction) {
    var caller = store.one("SELECT * FROM gateway_system WHERE `key`=?", system);
    if (caller == null) throw GatewayException.missing("调用方系统不存在");
    if (!JsonUtils.bool(caller.get("enabled"))) throw GatewayException.forbidden("调用方系统已停用");
    var grants = store.rows("SELECT * FROM gateway_grant WHERE `system`=?", system);
    var operations =
        contracts.filter(registry.operations(), direction).stream()
            .filter(op -> JsonUtils.bool(authorization.decision(op, grants).get("allowed")))
            .toList();
    var spec = documents.openapiFor(operations);
    var paths = JsonUtils.map(spec.get("paths"));
    for (var operation : operations) {
      var path = JsonUtils.map(paths.get(operation.path()));
      var post = JsonUtils.map(path.get("post"));
      post.put("x-cyt-effective-quota", authorization.decision(operation, grants).get("quota"));
      post.put("x-cyt-sensitive", operation.sensitive());
      path.put("post", post);
      paths.put(operation.path(), path);
    }
    spec.put("paths", paths);
    var info = JsonUtils.map(spec.get("info"));
    info.put("title", "CYT Common · " + JsonUtils.str(caller, "display_name"));
    info.put("description", "只包含该调用方当前获授权的操作；运行时仍实时检查系统状态、授权和配额。");
    spec.put("info", info);
    spec.put("x-cyt-consumer", system);
    spec.put("x-cyt-direction", direction);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cyt-consumer-openapi.json\"")
        .body(spec);
  }

  @GetMapping("/consumer-package/{system}")
  public ResponseEntity<byte[]> integrationPackage(
      @PathVariable String system, @RequestParam(defaultValue = "all") String direction) {
    var spec = export(system, direction).getBody();
    String readme =
        "# CYT Common 接入包\n\n请先解压本包，并用浏览器打开 START_HERE.html。指南可离线阅读、按模块搜索和复制 HTTP / Python / TypeScript 示例，无需后台账号。\n\n调用方标识："
            + system
            + "\n\n"
            + "1. 本包 openapi.json 仅包含导出时已获授权的接口。运行时继续检查系统状态、权限和配额。\n"
            + "2. 向管理员领取本系统独立 API Key，存入后端环境变量 CYT_API_KEY。本包不包含密钥。\n"
            + "3. 使用管理员提供的可访问网关地址替换示例域名。不要向网关管理端 /admin/api 发业务请求。\n"
            + "4. 按 openapi.json 填写业务参数。SDK 的操作名称取各接口 x-cyt-operation 字段。写操作使用稳定业务编号，超时后先核对原结果。\n\n"
            + "Python SDK：将 sdk/python 加入模块路径，或把 cyt_gateway.py 放入业务项目。\n\n"
            + "```python\nimport os\nfrom cyt_gateway import Gateway\ngateway = Gateway('https://<网关域名>', os.environ['CYT_API_KEY'])\n# gateway.call('<openapi 中的操作标识>', {填写实际业务参数})\n```\n\n"
            + "TypeScript SDK：导入 sdk/typescript/index.ts 的 Gateway 类，在业务后端调用。\n\n"
            + "HTTP 成功返回 { ok, data, meta }，SDK 直接返回 data。联调时从 HTTP 响应检查 meta.mock，并处理 data 的 partial/errors 和续传位置；"
            + "HTTP 错误保留 trace_id。密钥和上游凭据不要放进浏览器构建变量。\n";
    try {
      var bytes = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
        entry(
            zip, "START_HERE.html", IntegrationGuide.render(spec).getBytes(StandardCharsets.UTF_8));
        entry(zip, "README.md", readme.getBytes(StandardCharsets.UTF_8));
        entry(zip, "openapi.json", JsonUtils.write(spec).getBytes(StandardCharsets.UTF_8));
        for (String resource :
            new String[] {"sdk/python/cyt_gateway.py", "sdk/typescript/index.ts"}) {
          try (var input = getClass().getResourceAsStream("/" + resource)) {
            if (input == null) throw GatewayException.config("SDK 资源未打包，请重新构建网关");
            entry(zip, resource, input.readAllBytes());
          }
        }
      }
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType("application/zip"))
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cyt-integration.zip\"")
          .body(bytes.toByteArray());
    } catch (IOException e) {
      throw GatewayException.config("接入包生成失败");
    }
  }

  private static void entry(ZipOutputStream zip, String name, byte[] value) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(value);
    zip.closeEntry();
  }
}

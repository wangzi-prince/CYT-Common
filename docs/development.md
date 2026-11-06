# 开发与验证

## 参考约定

结构参考 CYT-Cloud 的 `core` / `module` 后端分层，以及前端 `pages` / `api` / `stores` / `layouts` / `components` 拆分。

- Controller 负责协议和输入，Service 负责业务规则，公共持久层统一参数化 SQL。
- 动态 API 参数与第三方返回使用 JSON 对象；固定领域模型与请求优先使用 Java 类型。
- 敏感操作执行时必须匹配精确授权，`deny` 优先，过期授权失效，系统停用立即生效。
- 数据库存 UTC，接口输出带 `Z` 的时间；页面按浏览器时区展示。
- MySQL 标识符 `key`、`system` 必须引用；幂等键和系统标识保留大小写敏感性。
- 管理变更与审计使用同一事务。调用日志脱敏后放入有界队列异步写入，写入失败不改变业务响应。
- 上游地址只能来自固定平台适配器或管理员配置，自定义接口校验 DNS 并将地址固定到实际连接，不跟随重定向。
- API Key 只返回一次明文，数据库保存摘要；凭据不得写入源码、日志、浏览器构建变量或 Git。

前端使用 Vue `script setup`、严格 TypeScript、统一 Axios、Pinia 会话和路由懒加载。Element Plus 按需导入，图表独立加载。列表都有加载、空结果、失败重试，敏感变更有确认交互。

## 日常命令

后端：

```sh
mvn test
mvn fmt:format
mvn package
```

`mvn test` 在未设置 `TEST_MYSQL_URL` 时运行契约与单元测试，MySQL 集成测试会明确跳过。

前端：

```sh
pnpm install --frozen-lockfile
pnpm typecheck
pnpm format
pnpm build
pnpm build:backend
```

前端只保留 pnpm 锁文件。`API_PROXY_TARGET` 可在忽略的 `.env.local` 中覆盖开发代理地址。不要把密钥放进 `VITE_*`。

## 真实 MySQL 集成测试

PowerShell：

```powershell
$env:TEST_MYSQL_URL = 'jdbc:mysql://127.0.0.1:13309/'
$env:TEST_MYSQL_USERNAME = 'root'
$env:TEST_MYSQL_PASSWORD = '<仅测试实例的密码>'
mvn test
```

只允许显式指定的回环地址和端口，URL 不包含数据库名。测试创建随机 `cyt_common_check_<随机值>` 数据库，运行 Flyway 和服务，再仅删除自己创建的临时库。测试账号需要创建/删除测试库的权限。

不要把该账号作为应用账号。正常应用使用独立数据库和最小权限用户。集成测试只访问本机模拟上游，不调用供应商。

## 配置

- `ENV`：固定部署环境。页面选择其他环境仅用于管理该环境凭据。
- `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`：新库连接，也可使用标准 `spring.datasource.*`。
- `MOCK_ALL` / `<PLATFORM>_MOCK`：进程配置控制模拟。真实模式缺少凭据返回配置错误。
- `GATEWAY_DEV_MODE`：仅非生产允许 `X-System-Id`。
- `ADMIN_AUTH_MODES`：`admin_jwt`、`portal_jwt`、`portal_proxy`；`static` 只用于本地。
- `ADMIN_USERNAME`、`ADMIN_PASSWORD` / `ADMIN_PASSWORD_HASH`：管理员登录，摘要格式兼容原 SHA-256。
- `ADMIN_JWT_SECRET`：会话签名密钥，生产至少 32 字符。
- `CREDENTIAL_MASTER_KEY`：与原系统一致时可读取迁入的 Fernet 密文。留空禁止凭据写入。
- `JAVA_SEED_ENABLED`：非生产的空库初始化系统清单和授权，默认随模拟模式开启；有系统、授权或初始化审计时不会重复灌入，重启不会恢复已撤销的授权。数据迁移前设为 `false`。
- `REDIS_URL`：跨实例配额，留空为单实例内存配额。Redis 故障会告警并临时退回内存。
- `CUSTOM_API_ALLOWED_HOSTS`：自定义接口域名白名单。
- `CUSTOM_API_ALLOW_LOOPBACK`：仅非生产本地联调可开启。

Google ID token 使用 `GATEWAY_AUDIENCE`、`GATEWAY_ALLOWED_SA_EMAILS` 和 `GATEWAY_SA_SYSTEM_MAP`。服务账号映射必须显式配置，不按邮箱名称猜测系统身份。

## 扩展平台

在 `module/adapter/<platform>` 实现 `PlatformAdapter`，注册固定操作契约和测试。核心执行器不依赖具体平台。不要添加任意 URL、任意 GraphQL 或任意供应商写入透传。

开发期更新快照：

```sh
python tools/export_contract.py D:/code/cyt-common-api
python tools/export_test_cases.py D:/code/cyt-common-api
```

工具只导出元数据、查询模板和测试参数，不调用适配器、不读取业务数据库、不导出凭据值。快照已随代码提供，正常构建不需要 Python。


## 自有系统的受控内网连接

在导入的 application-local.properties 或部署环境变量中设置 INTERNAL_API_ALLOWED_ORIGINS，例如 https://orders.internal.example:8443,http://10.20.30.40:9000。名单按协议、主机和端口精确匹配，不填写路径、查询串、用户名密码，也不支持通配。空名单保持禁止内网连接。CUSTOM_API_ALLOWED_HOSTS 若已设置仍同时生效。HTTP 仅对明确登记的来源放行；受信任来源只允许普通内网服务地址，元数据、回环、链路本地地址等不因登记而放行。

菜单默认从 /workspace 开始。/grants?system=... 等旧授权链接转到对应 /systems/:system 的能力授权页；没有系统时先选择调用方。管理端导出 /admin/api/consumer-openapi/{system} 仅包含当前有效授权的接口，敏感操作仍需精确允许，拒绝和过期规则仍生效。

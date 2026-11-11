# CYT Common · 统一 API 平台

## 项目结构

```text
CYT-Common/
├─ CYT-Common-API/              Java 后端
│  ├─ src/main/java/group/cyt/common/
│  │  ├─ core/                 配置、异常、HTTP、持久化与公共工具
│  │  └─ module/               按业务领域拆分的 controller / service / model
│  ├─ src/main/resources/db/    Flyway MySQL 迁移
│  ├─ src/test/                参数契约、签名和真实 MySQL 集成测试
│  └─ tools/                   开发期契约导出与数据迁移工具
├─ CYT-Common-Frontend/         Vue 管理前端
│  ├─ src/pages/               按路由懒加载的业务页面
│  ├─ src/api/                 接口与类型
│  ├─ src/http/                Axios 认证和错误处理
│  ├─ src/stores/              Pinia 管理会话
│  ├─ src/components/          公共组件
│  └─ src/layouts/             统一工作台布局
└─ docs/                       开发、迁移与验收说明
```

## 技术栈

- 后端：Java 21、Spring Boot 3.5.16、MyBatis Plus 3.5.12、Spring JDBC、Flyway、MySQL 8.0.13+ / 8.4。
- 前端：Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router、Axios、按需加载的 ECharts。
- 配额：进程内滑动窗口，可配置 Redis 跨实例共享。
- 凭据：兼容原 Python Fernet 密文格式，按环境和调用系统解析。

核心系统管理使用 MyBatis Plus；动态配置和聚合查询由集中管理的 JDBC 持久层处理。网关管理员继续使用 JWT / Portal 协议，保留原业务系统调用方式。

## 本地启动

准备 JDK 21、Maven 3.9+、Node.js 22.12+ 和 pnpm 11.25.0，以及一个**独立 MySQL 数据库**。

1. 创建数据库及独立应用账号，字符集使用 `utf8mb4`，排序规则使用 `utf8mb4_bin`。
2. 复制 `CYT-Common-API/application-local.properties.example` 为同目录下 `application-local.properties`，填写新库连接。文件已忽略，不提交真实配置。
3. 在后端目录执行 `mvn spring-boot:run`，默认端口为 **8189**。Flyway 自动建表。
4. 在前端目录执行 `pnpm install --frozen-lockfile`，再执行 `pnpm dev`，访问 **http://127.0.0.1:8190**，开发代理默认连接后端 **8189**。构建后的 `pnpm preview` 也使用 **8190**。

本地默认显式模拟模式 `MOCK_ALL=true`；登录页可进入本地管理环境。管理员密码未配置时，密码登录不会接受空密码。生产环境禁止免密、开发身份和模拟模式。

旧 Python 网关 `D:/code/cyt-common-api` 使用 8088（另有本地实例使用 8089），前端使用 5173；本项目统一使用后端 8189 / 前端 8190，可与旧项目并行运行。新后端不会自动读取原项目的 `.env`。

## 平台、接口与调用授权

- **接入平台**：默认用分页列表管理上游连接，支持名称、标识、域名和用途的多关键词搜索，按来源与状态筛选，提供常用星标和分页卡片视图。列表每页默认 20 条、卡片每页默认 12 条，筛选和页码保留在地址栏，常用平台及视图偏好保存在当前浏览器。按 `/` 聚焦平台搜索，`Ctrl/Cmd+K` 打开全局快捷导航。普通 REST 平台可点击“接入新平台”，填写基础地址和鉴权引用。
- **操作目录**：统一发现内置与自定义接口，按平台、分类、来源及启用状态筛选。未启用配置也会显示，方便继续编辑。
- **接入配置**：维护自定义平台连接与 HTTP 接口。平台连接可以复用地址和鉴权；接口定义请求路径、参数、响应处理和分页。
- **调用方系统 / 操作授权**：控制哪个业务系统可以调用哪些接口。上游凭据与调用方 API Key 分别配置，保存接口不会自动新增授权，现有通配授权仍按网关规则生效。

新增现有平台的接口：在平台卡片或操作目录点击“新增接口”，依次填写接口信息、参数和调用策略。可从完整 HTTPS URL 拆分地址，从路径或请求体模板补全参数；默认保存为未启用。保存后可配置凭据、启用并调试，再管理调用系统的授权。内置平台的自定义 HTTP 扩展不会继承其原生签名及凭据逻辑，需要独立的 `CUSTOM_` 凭据。

新增平台：普通 HTTPS REST 服务先创建平台连接，再为该平台添加接口；复杂签名、SDK、文件协议和多接口编排需开发适配器，并声明契约、策略及测试，发布后进入同一目录。

目前是轻量的 API 集成与治理工作台，没有实现 WSO2 的完整版本生命周期、发布审批、订阅产品或独立开发者门户。接口启用状态会直接影响运行时注册，不等同于完整的发布生命周期。

## 界面与导航

管理界面统一采用 Element Plus 组件与图标，主题、文字、边框和间距集中维护在前端 `src/styles/main.css`。桌面侧栏可折叠，手机使用抽屉导航。按 `Ctrl/Cmd+K` 打开快捷导航，支持关键词搜索和方向键选择；在平台目录按 `/` 聚焦平台搜索。右上角可切换舒适或紧凑间距，侧栏和密度偏好保存在当前浏览器。

## 构建

上游凭据的新增、轮换、Google Cloud 本地同步及主密钥备份，见 [凭据管理](docs/credentials.md)。

```sh
cd CYT-Common-Frontend
pnpm install --frozen-lockfile
pnpm build:backend
cd ../CYT-Common-API
mvn package
java -jar target/common-api-1.0.0-SNAPSHOT.jar
```

`build:backend` 将前端静态资源同步到相邻后端。前后端可以单独开发，也可以打包成同源部署的一个 JAR。

## 功能范围

Discord 已增加 11 个原生接口、加密凭据登记、持久化实时监听及重启恢复；接入配置、缓存策略与切换边界见 [Discord 接入](docs/discord.md)。中台现用 Python 路由保持运行，Java 版需完成凭据与授权配置后再切换。

操作目录由版本化契约生成，保留原参数名、公开路径和旧操作名称别名，覆盖 DocuSign、企业微信、Shopify、Gmail、Google Drive、GCS、NAS MinIO、QuickCEP、领星、Winit、Discord 等平台。

管理界面包括调用总览、调用记录、审计日志、接入平台、操作目录、调用方系统、授权管理、环境与凭据、企微/DocuSign 回调、自定义模块及接口、调试台与接入文档。审批作为企业微信平台的业务接口集成在同一目录，提供按模板填写的调试辅助；封装指南从操作目录和接入文档进入。

企微审批的模板、字段别名、预检、提单、防重、查询、事件和假期能力见 [审批接入](docs/wecom-approvals.md)。全部原有操作的封装审阅和已落地的聚合能力见 [能力设计](docs/capability-design.md)，调用示例见 [Python / TypeScript SDK](sdk/README.md)。

所有公开调用、批量调用和管理调试统一经过授权、配额、参数校验、幂等、超时和日志流程。HTTP 路径继续使用 `POST /integrations/{platform}/{resource}/{action}`，返回 `ok / data / meta`。

## 验证与切换

见 [开发与验证](docs/development.md)、[迁移说明](docs/migration.md) 和 [实现边界](docs/verification.md)。

本地测试和模拟上游验证不等同于真实供应商账号验收。原项目、原数据库、原 `.env` 和线上部署未切换；真实账号联调及生产数据迁移需在专用环境另行执行。

管理工作台从“工作台”开始，按服务接入 → 对外能力 → 调用方 → 接入文档组织。服务接入配置上游连接，对外能力展示供业务系统调用的业务接口。独立的接入文档入口提供快速接入、参数参考及 HTTP / Python / TypeScript 示例；选择调用方后可导出专属接入包，接入方解压打开 START_HERE.html 即可离线阅读，无需后台账号。密钥单独交付。运行概览、调用记录和审计统一在运行中心，环境、上游凭据及回调归入设置。

调用方向已明确分开：服务接入仅管理我们调用的第三方平台；对外能力仅管理绑定 internal 提供方的自有业务接口。接入文档、调试与授权可按方向筛选，导出 OpenAPI / 接入包带 direction 参数；省略参数仍保留旧版全部授权导出行为。OA 组织花名册为 UI 契约设计示例，不注册接口、不扫描或连接其他 CYT 项目。

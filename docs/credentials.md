# 上游凭据管理

上游凭据用于网关连接供应商；调用方 API Key 和操作授权用于业务系统访问网关，两者独立管理。管理入口为“环境与凭据”，支持平台、调用方作用域、缺失项、来源和关键词筛选。列表只显示状态、长度或非敏感配置，不返回密钥原文。

## 从 Google Cloud 同步到本地

工具使用电脑上现有的 `gcloud` 登录访问 Secret Manager，不复制个人 CLI 登录令牌。默认读取 `cyt-digital` 项目、`us-central1` 区域的 `cyt-common-api` 服务绑定，并按当前操作契约匹配同项目中的平台密钥。别名只采用代码中显式确认的映射；无关密钥和网关主密钥不在导入范围。

在 `CYT-Common-API` 目录执行：

```powershell
python -m pip install -r tools/requirements-credentials.txt
python tools/gcloud_credentials.py plan
# 检查 target/gcloud-credential-plan.json 中的变量、Secret ID 和固定版本号
python tools/gcloud_credentials.py apply
```

计划文件只包含资源与版本元数据，`plan` 不读取密钥值。`apply` 将值保留在进程内，加密后写入 `application-local.properties` 所配置的本机 `cyt_common*` MySQL 数据库，默认环境为 `local`。已有不同值会保留；整个数据库写入事务成功后才提交。日志和审计不记录原文。工具不更新云端 Secret、IAM 或服务配置，也不切换模拟模式。

首次导入时，如果本地没有主密钥且凭据库为空，工具会生成本地专用 `CREDENTIAL_MASTER_KEY` 并写入已忽略的 `application-local.properties`；重启 Java 网关一次以加载它。已有加密数据必须使用原主密钥。数据库与主密钥需要分别备份；更换主密钥必须重加密已有数据，不能直接覆盖配置。

本次匹配并导入了 8 个平台的 30 个变量：DocuSign、企业微信、Shopify、领星、马帮、Winit、QuickCEP 和 NAS MinIO。具体变量与固定云端版本可从本地导入计划及 `secrets_import_gcloud` 审计查看。凭据存在只表示已配置，不代表供应商授权、账号环境或接口真实调用已经验收；缺少的 endpoint、账号参数仍需在页面中补全。

## 新增与轮换

1. 本地新增：选择环境和调用方作用域，点击“新增凭据”，选择变量并填写新值。私钥、证书和 JSON 使用多行输入，保持换行。`CUSTOM_` 变量用于自定义 HTTP 平台的鉴权引用。
2. 本地更新：点击“更新 / 轮换”，输入框保持空白，填写新的供应商凭据后加密保存；后续请求读取新值。页面显示来源、云端版本与更新时间，修改记录进入审计。保存本地值不会更新云端。
3. 云端轮换：先在 Secret Manager 中增加版本，再重新生成并检查计划；确认选中的版本后执行 `python tools/gcloud_credentials.py apply --overwrite`。该选项会替换计划中已有的不同本地值。保留原版本以便供应商允许时回退，验收完成后再按供应商规则撤销旧令牌。
4. 删除：删除页面中的变量仅移除本地数据库值，同名部署配置可能继续生效；撤销供应商凭据需在供应商侧完成。

环境下拉框只选择维护目标，不会切换网关运行环境。当前运行环境使用 `ENV` 配置，数据库凭据优先于同名部署配置。需要调用方隔离的平台应配置对应的系统专属变量，结合调用授权控制业务系统权限。

## Google API 与生产环境

`gcloud` 登录、应用默认凭据（ADC）、Gmail/Drive 用户 OAuth 是不同用途。此次 Secret Manager 同步没有发现可明确对应的 Google 用户 OAuth 组合，因此没有借用其他业务密钥或个人 CLI refresh token。Gmail/Drive 需按本项目的 `GOOGLE_CLIENT_ID`、`GOOGLE_CLIENT_SECRET`、`GOOGLE_REFRESH_TOKEN` 配置，并确保目标账号与 scopes 正确；GCS 按项目支持的服务账号/ADC 配置单独接入。

生产环境应使用服务身份访问 Secret Manager，只授予需要的 Secret 权限，部署时固定版本并按环境隔离。此工具用于本地开发同步，不是生产凭据发布服务。参见 Google 官方 [ADC 说明](https://docs.cloud.google.com/docs/authentication/application-default-credentials) 与 [Secret Manager 最佳实践](https://docs.cloud.google.com/secret-manager/docs/best-practices)。

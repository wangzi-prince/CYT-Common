# Discord 原生接入

Java 网关现在注册与 Python 网关一致的 11 个 Discord 操作，公开路径沿用
`POST /integrations/discord/{resource}/{action}`。包括账号发现、机器人身份、服务器、频道、
服务器机器人目录、论坛活动与归档帖子、消息分页、单条消息、实时事件及确认发送。

## 凭据与范围

在凭据页的 Discord 平台登记账号 `epomaker` 及 `DISCORD_EPOMAKER_BOT_TOKEN`。
Token 使用现有加密凭据服务保存，账号列表可通过 `DISCORD_ACCOUNTS=epomaker` 声明。
服务器内其他机器人的名字和 ID 来自托管角色，不表示已获得它们的 Token。
读取这些机器人在可见频道中的发言，只需要当前 CYT-CS 机器人的读取权限。

调用系统仍需显式授权对应的 `discord.*` 操作，保存凭据不会自动增加授权。
人工发送使用 `discord.messages.send`，需要 `confirm=true`、发送 nonce 和有效频道；
网关重新读取实际频道归属，禁止向论坛父频道直接发消息，关闭自动提及并禁止自动重试。

当前 CYT-CS 中台只启用 Epomaker 服务器 `726610136084250764`，由中台配置
`CS_DISCORD_GUILDS_JSON={"epomaker":["726610136084250764"]}` 控制。
该配置限制频道发现、预览、同步与发送范围，不会自动加入其他服务器。

## 实时监听

Flyway `V6__discord_gateway.sql` 新增持久化收件箱与会话表。启用监听前配置：

```properties
DISCORD_ACCOUNTS=epomaker
DISCORD_GATEWAY_ENABLED=true
DISCORD_LISTENER_SYSTEMS_JSON={"epomaker":"cs"}
```

监听默认关闭。启动后按账号解析已授权系统的凭据，使用 Gateway v10 WebSocket。
支持 Hello、心跳与 ACK、Identify、Resume、失效会话及重连；消息新增、修改、删除、批量删除及
帖子事件先持久化，再原子更新恢复游标。租约按环境和机器人 ID 隔离，防止同库多实例重复监听。
凭据更新会在下一个检查周期重新连接。状态只返回连接信息，不包含 Token、会话凭据或原始异常内容。

机器人私信与员工个人账号的私信不同。大量服务器需要分片时，当前监听会报错，需补充分片部署，
不能把未连接状态视为同步完成。当前一个服务器的接入不需要分片。

## 响应速度

上游 HTTP 使用已有共享连接池。基础身份缓存 60 秒，服务器、频道与机器人托管角色目录缓存
30 秒，活动帖子目录缓存 10 秒；缓存按环境、调用系统、账号与实际 Token 摘要隔离。
并发相同请求合并，凭据轮换立即使用新缓存键，失败结果不缓存。

`refresh=true` 强制更新。`discord.messages.list` 只有显式 `preview=true` 时缓存 5 秒；
正式同步、单条消息核对、发送前频道检查与真实发送不使用预览缓存。
两个网关保持相同参数和语义，中台页面无需改业务接口即可选择其中一个网关路由。

## 验证与切换

`DiscordAdapterTest` 使用模拟上游与 H2 验证原生 REST、缓存隔离、发送校验、收件箱去重、
事务回滚、租约、重启恢复及 Gateway URL 限制；`discord-cases.json` 与 Python Pydantic 模型的
默认值和参数结构一致。以上不会向真实服务器发送测试消息。

2026-11-11 验证：15 项 Discord 专项与 Spring 装配检查通过；编译及跳过全量测试的 JAR 打包通过。
全量测试运行 333 项，25 项真实 MySQL 集成检查因未配置测试数据库而跳过，另有 3 项现存失败：
企微 HR `wecom.hr.get_fields`、`wecom.hr.get_staff_info` 已登记但尚缺参数夹具和能力审阅，
导致两项全量数量校验失败；领星消息测试要求不带 `issue_type`，而现有实现返回该字段，导致一项字段断言失败。
这三项未作为 Discord 改动修正，也不能据此宣称整个仓库全量测试通过。

本次只补 Java 代码与契约，没有把中台现用的 Python 路由切换到 Java，也没有复制真实 Token。
切换时应先在 Java 凭据页配置并验证账号、迁移数据库、配置调用系统授权，然后修改中台的
`CS_GATEWAY_ROUTES_JSON`。旧网关的事件 ID 不能直接作为新网关的事件游标，切换时需在停掉旧
同步后重置该账号的事件游标并进行 REST 补拉；中台原消息唯一键会防止重复入库。

协议依据：[Discord Gateway](https://docs.discord.com/developers/events/gateway)、
[消息接口](https://docs.discord.com/developers/resources/message)、
[频道与帖子](https://docs.discord.com/developers/resources/channel)。

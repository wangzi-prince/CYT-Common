# CYT-Common 测试服部署（参照 CYT-CS）

一键发版入口是 Jenkins 上的 **`cyt-common-deploy`**：选分支 → 构建镜像 → 推局域网仓库 → 重建容器 → 健康检查。
服务器不留源码，只跑镜像。

## 和旧 common-api 的关系（重要）

测试服上已经有一个 `cyt-common-api`（Python 版，8085 端口，编排目录 `/opt/1panel/docker/compose/common-api`）。
**本次部署完全不碰它**：新服务用独立的镜像名 / 容器名 / 编排目录 / 端口 / 数据库卷。等旧服务要下线时由你手动处理。

| | 旧 common-api | 本次新服务 |
|---|---|---|
| 镜像 | `192.168.1.182:5000/cyt-common-api:latest` | `192.168.1.182:5000/cyt-common-java:latest` |
| 容器 | `cyt-common-api` | `cyt-common-java` / `cyt-common-java-db` |
| 编排目录 | `/opt/1panel/docker/compose/common-api` | `/opt/1panel/docker/compose/common-java` |
| 端口 | 8085 | **8088** |
| 数据库 | 外部 | 专用 MySQL 8 容器 + 独立卷 |

## 访问

- 页面 / API：`http://192.168.1.182:8088/`
- 健康检查：`http://192.168.1.182:8088/health`（返回 `ok:true` 且会真的查一次 MySQL）
- Jenkins：`http://192.168.1.182:8090/job/cyt-common-deploy/`
- 后台登录：见服务器 `/opt/1panel/docker/compose/common-java/.env` 里的 `ADMIN_PASSWORD`；
  `ENV=test` 时登录页也有「开发身份」免密入口。

## 一键发版怎么用

1. Jenkins → `cyt-common-deploy` → **Build with Parameters**
2. `BRANCH` 下拉选分支（默认 `main`），`SKIP_DEPLOY` 保持不勾
3. 构建日志结尾出现 `✅ 健康` 即完成

新建了分支但下拉里没有？跑一次 **`cyt-common-refresh-branches`**，它会把 GitHub 当前分支列表热更新进下拉框，
不用装插件、不用重启 Jenkins（这台 Jenkins 是裸 war，没装 git-parameter 插件）。

## 机器上的文件

| 路径 | 作用 |
|---|---|
| `/opt/jenkins/github_token_common` | 本项目专用的 GitHub PAT（600）。**不要动 `/opt/jenkins/github_token`**，那是 CS 在用的 |
| `/opt/cyt-common/deploy/git-hub.sh` | 拉源码的 bootstrap 副本（755）。Jenkins 要先拿到源码才能读仓库里的脚本，所以这份必须预先放在机器上 |
| `/opt/cyt-common/deploy/jenkins.env` | Jenkins 管理员账号密码（600），刷新分支任务用 |
| `/opt/1panel/docker/compose/common-java/docker-compose.yml` | 编排文件，每次发版从仓库同步 |
| `/opt/1panel/docker/compose/common-java/.env` | 随机密钥，**只在首次生成**，之后保留 |

## 源码是怎么到服务器的（为什么不是 git clone）

这台测试服到 `github.com:443` 不通（TCP 超时，走 mihomo 代理也在 TLS 握手断开），
但 `api.github.com` 和 `codeload.github.com` 能通。所以 `git-hub.sh` 不用 `git clone`：

1. `GET /repos/<owner>/<repo>/branches` 拿到分支名 + 提交 SHA；
2. 下载 `codeload.github.com/<owner>/<repo>/tar.gz/<sha>`；
3. 解到临时工作区，写入 `.deploy-sha` 记录版本。

每次请求都是「直连 → 代理」最多两轮重试，因为这台机器对 GitHub 是时通时不通。
构建完临时工作区即删除，服务器只留镜像。

因此 Jenkins 任务需要一份**预先放在机器上**的 `git-hub.sh` bootstrap 副本
（检出源码之前就要用它）。改了仓库里的 `git-hub.sh` 后，记得同步：

```bash
sudo install -m 755 deploy/test-server/git-hub.sh /opt/cyt-common/deploy/git-hub.sh
```

## 手动执行（不经 Jenkins）

```bash
# 任意一份源码根目录（git clone 或 tar 包解开都行）
bash deploy/test-server/ci-build.sh          # 构建 + 推送 + 重建 + 健康检查
SKIP_DEPLOY=1 bash deploy/test-server/ci-build.sh   # 只构建推送，不重启容器
bash deploy/test-server/git-hub.sh branches          # 看远端分支
bash deploy/test-server/refresh-branches.sh          # 刷新 Jenkins 分支下拉
```

## 脚本职责

| 文件 | 干什么 |
|---|---|
| `Dockerfile` | 三阶段：pnpm 构建前端并同步进后端 → Maven 打 JAR → JRE 运行 |
| `docker-compose.yml` | MySQL 8 + Spring Boot 单 JAR 两个容器 |
| `build-on-server.sh` | docker build + push 到 `192.168.1.182:5000` |
| `ci-build.sh` | Jenkins 构建步骤的总入口 |
| `redeploy.sh` | 首次自动建编排目录与随机 `.env`，`up -d` 后等 `/health` |
| `git-hub.sh` | 走 GitHub API 列分支、codeload 下源码包；直连优先、失败走 mihomo 代理重试 |
| `refresh-branches.sh` | 热更新 Jenkins 的 BRANCH 下拉 |
| `jenkins/*.xml` | 两个 Jenkins 自由风格任务的配置模板（不含任何密钥） |

## 排障

```bash
sudo docker logs --tail 100 cyt-common-java        # 后端日志（Flyway / 启动校验）
sudo docker logs --tail 50 cyt-common-java-db      # MySQL 启动
curl -s http://127.0.0.1:8088/health | head -c 300
sudo docker compose -f /opt/1panel/docker/compose/common-java/docker-compose.yml --env-file /opt/1panel/docker/compose/common-java/.env ps
```

常见：首次构建要拉 4 个基础镜像 + Maven 依赖，慢是正常的；之后有层缓存会快很多。
若 Docker Hub 拉不动，构建时传 `BASE_REGISTRY=docker.m.daocloud.io/`。

拉源码报 `ERROR: 直连与代理都拿不到 ...` 时，在服务器上分别测一下：

```bash
curl -s -m 10 -o /dev/null -w 'api:%{http_code}\n' https://api.github.com/repos/wangzi-prince/CYT-Common/branches
curl -s -m 10 -o /dev/null -w 'proxy:%{http_code}\n' -x http://172.18.0.6:7890 https://api.github.com/
```

代理容器 `marketing-proxy` 在 1panel-network 上的 IP 若变了，改 `git-hub.sh` 的 `GH_PROXY`
并重新同步 bootstrap 副本。

若 `Build with Parameters` 里 `BRANCH` 下拉不见了（Jenkins 对格式错误的 choices 会静默吞掉），
用模板恢复任务配置再跑一次刷新任务：

```bash
curl -s -u admin:<密码> -H 'Content-Type: application/xml; charset=utf-8' \
  --data-binary @deploy/test-server/jenkins/job-deploy.xml \
  -X POST http://127.0.0.1:8090/job/cyt-common-deploy/config.xml
```

`refresh-branches.sh` 每次热更新后会回读 API 校验 BRANCH 选项仍在，失败会直接报错。

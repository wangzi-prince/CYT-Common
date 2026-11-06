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
| `/opt/cyt-common/deploy/jenkins.env` | Jenkins 管理员账号密码（600），刷新分支任务用 |
| `/opt/1panel/docker/compose/common-java/docker-compose.yml` | 编排文件，每次发版从仓库同步 |
| `/opt/1panel/docker/compose/common-java/.env` | 随机密钥，**只在首次生成**，之后保留 |

## 手动执行（不经 Jenkins）

```bash
# 在任意一份仓库检出的根目录
bash deploy/test-server/ci-build.sh          # 构建 + 推送 + 重建 + 健康检查
SKIP_DEPLOY=1 bash deploy/test-server/ci-build.sh   # 只构建推送，不重启容器
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
| `git-hub.sh` | GitHub 直连优先、失败走 mihomo 代理；提供 branches / clone |
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

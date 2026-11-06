# CYT-Common 测试服镜像：前端构建 → 后端打包 → JRE 运行，最终只留一个可执行 JAR。
#
# build context 必须是【仓库根目录】：
#   docker build -f deploy/test-server/Dockerfile -t 192.168.1.182:5000/cyt-common-java:latest .
#
# 为什么要三个阶段：仓库里不含 target/*.jar，也不含 src/main/resources/web/（都被 .gitignore 挡了），
# 所以镜像里必须自己跑 pnpm build:backend + mvn package，把 Vue 页面打进 JAR 做同源部署。
#
# BASE_REGISTRY：Docker Hub 拉不动时传加速前缀，例如 docker.m.daocloud.io/
ARG BASE_REGISTRY=

# ── 1) 前端：Vue 3 / Vite 构建，并把 dist 同步进后端 src/main/resources/web ──
FROM ${BASE_REGISTRY}node:22-alpine AS frontend
ARG NPM_REGISTRY=https://registry.npmmirror.com
ENV NPM_CONFIG_REGISTRY=${NPM_REGISTRY}
ENV CI=true
WORKDIR /build
# scripts/sync-backend.mjs 用 ../CYT-Common-API 定位后端，两个目录必须保持同级
COPY CYT-Common-Frontend/ ./CYT-Common-Frontend/
COPY CYT-Common-API/ ./CYT-Common-API/
RUN npm install -g pnpm@11.25.0 --registry="${NPM_REGISTRY}"
RUN cd CYT-Common-Frontend \
 && pnpm install --frozen-lockfile \
 && pnpm build:backend

# ── 2) 后端：Maven 打成 Spring Boot 可执行 JAR ──
FROM ${BASE_REGISTRY}maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build/CYT-Common-API
COPY CYT-Common-API/pom.xml ./pom.xml
COPY CYT-Common-API/src ./src
# pom.xml 的 <resources> 还会从 ../sdk 打包两份 SDK 示例，少了这个目录 mvn package 会失败
COPY sdk/ /build/sdk/
COPY --from=frontend /build/CYT-Common-API/src/main/resources/web ./src/main/resources/web
RUN mvn -B -q -DskipTests -Dfmt.skip=true package

# ── 3) 运行：JRE + 一个 JAR，同源提供 API 与管理页面 ──
FROM ${BASE_REGISTRY}eclipse-temurin:21-jre-noble AS runtime
WORKDIR /app
COPY --from=backend /build/CYT-Common-API/target/common-api-1.0.0-SNAPSHOT.jar /app/app.jar
ENV HOST=0.0.0.0 PORT=8189
EXPOSE 8189
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

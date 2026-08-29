# PostgreSQL 存储配置

AgentOS 的正式存储统一使用 PostgreSQL 与 MyBatis-Plus。进程内实现只用于测试，
SQLite 与文件存储不再由服务端生产配置装配。

## 本地启动

```bash
docker compose -f /Users/whale_fall/developer/docker/postgres/docker-compose.yml up -d
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/agentos'
export SPRING_DATASOURCE_USERNAME='agentos'
export SPRING_DATASOURCE_PASSWORD='agentos'
export AGENTOS_MODEL_SECRET_KEY="$(openssl rand -base64 32)"
mvn -pl agentos-server -am spring-boot:run
```

`AGENTOS_MODEL_SECRET_KEY` 必须长期稳定保存。它是模型 API Key 的 AES-256-GCM
主密钥；更换或遗失后，数据库里已有的模型密钥将无法解密。主密钥不得写入 Git、
数据库或管理接口。

首次启动会执行 `db/schema-postgresql.sql`。脚本使用幂等 DDL，并为每个字段提供了
PostgreSQL 字段注释。

## 数据分层

- `agent_events`、`agent_sessions`、`agent_checkpoints`、`agent_continuations`：运行状态。
- `session_usage`、`users`：用量和账户。
- `memory_records`：L0-L3 记忆、向量快照和记忆加工任务。
- `model_providers`、`model_routes`：加密模型连接和 planner/chat 路由。

生产环境应通过 Secret Manager 或部署平台注入数据库密码和模型主密钥。数据库需启用
备份、TLS 和最小权限账户；不要复用示例 Compose 的默认密码。

Docker 服务配置统一放在 `/Users/whale_fall/developer/docker` 下。新增配置前先检查
是否已有相同镜像的目录，再按镜像名称建立或复用目录；本服务使用 `postgres:17-alpine`，
对应 `postgres` 目录。现有 `pg` 目录运行的是 `pgvector/pgvector`，因此不混用。

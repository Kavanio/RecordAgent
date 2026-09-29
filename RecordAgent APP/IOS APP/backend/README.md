# RecordAgent 业务后端

本目录是独立 Maven 根工程，使用 Java 21、Spring Boot 3.5.16、PostgreSQL 17.11、Spring JDBC、Flyway 和 Spring Security。版本锁定参考 [Spring Boot 稳定版本列表](https://docs.spring.io/spring-boot/spring-projects.html)、[系统要求](https://docs.spring.io/spring-boot/system-requirements.html)及 [PostgreSQL 支持周期](https://www.postgresql.org/support/versioning/)。不依赖桌面端 Maven 工程或 SQLite。

当前只完成规划中的第 1 阶段工程底座：入口、领域包边界、数据库连接、`V1` 迁移、健康检查和默认拒绝业务请求的安全配置。`/api/v1` 业务接口、账户认证、领域表及附件存储尚未实现；不要把当前健康状态当作业务已就绪。

## 本地启动

1. 安装 Java 21、Maven 3.9+ 和 Docker，确保 Docker 服务正在运行。
2. 复制 `.env.example` 为 `.env`，设置仅用于本地的数据库用户名和随机密码。`compose.yaml` 只读取其中的数据库用户名和密码。
3. 在本目录运行 `docker compose --env-file .env up -d postgres`。
4. 将 `.env` 的三个 `RECORDAGENT_DB_*` 变量导出到当前 shell，然后运行 `mvn spring-boot:run`。应用启动时 Flyway 自动迁移空库；访问 `http://localhost:8080/actuator/health` 查看健康状态。

数据库只绑定本机回环地址。部署环境须通过环境变量注入数据库连接，不使用本地 Compose 配置。`V1` 建立独立 `record_agent` schema，后续迁移脚本需明确使用该 schema；Flyway 历史表仍位于 `public`。已执行的迁移脚本不可修改。

## 验证

运行 `mvn verify`。`BusinessBackendApplicationIT` 使用 Testcontainers 启动独立 PostgreSQL，验证空库迁移、重复迁移、健康检查和业务入口拒绝访问；执行该集成测试需要可用的 Docker 服务。默认镜像为 `postgres:17.11`；镜像仓库不可用时，可用 `-Dtest.postgres.image=postgres:17` 指向本机已有的兼容镜像，仍需在正式环境用锁定版本复核。`mvn package -DskipTests` 仅验证编译与打包，不能代替数据库集成测试。

后续先完成[后端开发规划.md](后端开发规划.md)的第 0 阶段契约盘点，再逐域创建新的迁移与 API。所有 REST 方法需使用 `@ApiLog`；日志只记录脱敏元数据，不能包含请求正文或凭据。

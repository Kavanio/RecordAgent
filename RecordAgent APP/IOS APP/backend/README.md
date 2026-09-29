# RecordAgent 业务后端

本目录是独立 Maven 根工程，使用 Java 21、Spring Boot 3.5.16、PostgreSQL 17.11、Spring JDBC、Flyway 和 Spring Security。版本锁定参考 [Spring Boot 稳定版本列表](https://docs.spring.io/spring-boot/spring-projects.html)、[系统要求](https://docs.spring.io/spring-boot/system-requirements.html)及 [PostgreSQL 支持周期](https://www.postgresql.org/support/versioning/)。不依赖桌面端 Maven 工程或 SQLite。

当前只完成规划中的第 1 阶段工程底座：入口、数据库连接、`V1` 迁移、健康检查和默认拒绝业务请求的安全配置。业务域包随实际功能落地时创建。`/api/v1` 业务接口、账户认证、领域表及附件存储尚未实现；不要把当前健康状态当作业务已就绪。

## 基础设施复用盘点

| `server-network` 能力 | 本工程处理方式 |
| --- | --- |
| `@ApiLog`、`ApiLogAspect` | 沿用标注与切面方式；本工程日志只输出接口名、脱敏参数标记、服务端请求 ID、客户端 IP、状态与耗时，不导入 Agent 类型或请求正文。 |
| `Result`、`ApiExceptionHandler` | 沿用统一错误处理思路；本工程使用实际 HTTP 状态和稳定的 `{ code, message, requestId }` 错误体，避免桌面端 HTTP 200 包装错误的语义。 |
| SQLite 数据源与 Flyway 装配 | 复用 Spring JDBC/Flyway 技术栈，由 Spring Boot 管理 PostgreSQL 连接和事务；不复制本地 SQLite 配置与迁移。 |
| `CorsConfig` | 桌面端的通配源配置不适合公网服务；当前 iOS 原生客户端无需 CORS，未来有浏览器客户端时再按域名配置。 |
| `BlobStore`、邮件发送接口 | 接口边界可供后续文件和账户阶段参考；存储位置与邮件供应商尚未定案，当前不引入桌面端路径和本机配置。 |

HTTP 请求由服务端生成 `X-Request-Id`，错误体使用同一个 `requestId`。通用错误码包括 `INVALID_ARGUMENT`、`UNAUTHENTICATED`、`FORBIDDEN`、`NOT_FOUND`、`VERSION_CONFLICT`、`RATE_LIMITED`、`SERVICE_UNAVAILABLE` 和 `INTERNAL_ERROR`；iOS 按 `code` 本地化显示。成功响应由各接口返回明确的 DTO，不添加空泛的通用包装。

## 本地启动

1. 安装 Java 21、Maven 3.9+ 和 Docker，确保 Docker 服务正在运行。
2. 复制 `.env.example` 为 `.env`，设置仅用于本地的数据库用户名和随机密码。`compose.yaml` 只读取其中的数据库用户名和密码。
3. 在本目录运行 `docker compose --env-file .env up -d postgres`。
4. 将 `.env` 的三个 `RECORDAGENT_DB_*` 变量导出到当前 shell，然后运行 `mvn spring-boot:run`。应用启动时 Flyway 自动迁移空库；访问 `http://localhost:8080/actuator/health` 查看健康状态。

数据库只绑定本机回环地址。部署环境须通过环境变量注入数据库连接，不使用本地 Compose 配置。`V1` 建立独立 `record_agent` schema，后续迁移脚本需明确使用该 schema；Flyway 历史表仍位于 `public`。已执行的迁移脚本不可修改。

## 验证

运行 `mvn verify`。`BusinessBackendApplicationIT` 使用 Testcontainers 启动独立 PostgreSQL，验证空库迁移、重复迁移、健康检查和业务入口拒绝访问；执行该集成测试需要可用的 Docker 服务。默认镜像为 `postgres:17.11`；镜像仓库不可用时，可用 `-Dtest.postgres.image=postgres:17` 指向本机已有的兼容镜像，仍需在正式环境用锁定版本复核。`mvn package -DskipTests` 仅验证编译与打包，不能代替数据库集成测试。

后续先完成[后端开发规划.md](后端开发规划.md)的第 0 阶段契约盘点，再逐域创建新的迁移与 API。所有 REST 方法需使用 `@ApiLog`；日志只记录脱敏元数据，不能包含请求正文或凭据。

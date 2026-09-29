# RecordAgent 业务后端

本目录是独立 Maven 根工程，使用 Java 21、Spring Boot 3.5.16、PostgreSQL 17.11、Spring JDBC、Flyway 和 Spring Security。版本锁定参考 [Spring Boot 稳定版本列表](https://docs.spring.io/spring-boot/spring-projects.html)、[系统要求](https://docs.spring.io/spring-boot/system-requirements.html)及 [PostgreSQL 支持周期](https://www.postgresql.org/support/versioning/)。不依赖桌面端 Maven 工程或 SQLite。

当前已完成工程底座及 `V1`～`V7` 的账户、日历、财务、资料、学习和文件表结构，设计见 [数据库设计.md](数据库设计.md)。业务域包随实际功能落地时创建。`/api/v1` 业务接口、账户认证及附件二进制存储尚未实现；不要把当前健康状态当作业务已就绪。

## 目录规范

Spring Boot [建议将启动类放在根包、按领域组织子包](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)；[Spring Modulith](https://docs.spring.io/spring-modulith/reference/fundamentals.html) 也以根包的直接子包识别模块。本工程采用这一布局，具体规则以 [AGENTS.md](AGENTS.md) 为准：

```text
src/main/java/com/butvan/recordagent/
├── RecordAgentApplication.java  # 组合根与组件扫描起点
├── platform/                    # 跨业务的 HTTP、安全、可观测性
│   ├── http/                    # 错误码、错误 DTO、异常映射
│   ├── security/                # 安全过滤与授权装配
│   └── observability/           # 请求 ID、接口日志
├── account/                     # 实现账户时创建
├── calendar/                    # 实现日历时创建
└── ...                          # finance、record、study、file、overview 等按需创建
```

业务模块内部按实际代码建立 `api`、`application`、`domain`、`persistence`，不预建空目录。`src/test/java` 镜像对应生产包；Flyway 脚本位于 `src/main/resources/db/migration`。

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
4. 在本目录运行 `mvn spring-boot:run`。Spring Boot 会从当前工作目录导入被 Git 忽略的 `.env`；应用启动时 Flyway 自动迁移空库。访问 `http://localhost:9090/actuator/health` 查看健康状态。也可由部署环境变量覆盖 `.env` 中的值。

连接已有 PostgreSQL 服务时，将 `.env` 中的 `RECORDAGENT_DB_URL` 设为目标数据库的 JDBC URL，并填写对应数据库用户名和密码；数据库必须先存在，且账号需有执行 Flyway 迁移的权限。`.env` 权限建议设为仅当前用户可读写（`chmod 600 .env`）。数据库密码与服务器地址不写入源码、示例配置或提交记录。

本地 Compose 数据库只绑定本机回环地址。部署环境须通过环境变量注入数据库连接，不使用本地 Compose 配置。`V1` 建立独立 `record_agent` schema，`V2`～`V7` 建立业务表；Flyway 历史表仍位于 `public`。已执行的迁移脚本不可修改。

## 验证

运行 `mvn verify`。`RecordAgentApplicationIT` 使用 Testcontainers 启动独立 PostgreSQL，验证空库迁移、重复迁移、健康检查和业务入口拒绝访问；执行该集成测试需要可用的 Docker 服务。默认镜像为 `postgres:17.11`；镜像仓库不可用时，可用 `-Dtest.postgres.image=postgres:17` 指向本机已有的兼容镜像，仍需在正式环境用锁定版本复核。`mvn package -DskipTests` 仅验证编译与打包，不能代替数据库集成测试。

后续先完成[后端开发规划.md](后端开发规划.md)的第 0 阶段契约盘点，再逐域创建新的迁移与 API。所有 REST 方法需使用 `@ApiLog`；日志只记录脱敏元数据，不能包含请求正文或凭据。

# RecordAgent 业务后端

独立 Java 21 / Spring Boot 3.5.16 / PostgreSQL / Spring JDBC 工程，不依赖桌面 Agent 或 SQLite。账户、日历、收支、总览和个人图片接口已接入；完整编辑删除、工作台和任务仍按阶段开发。当前接口以 [接口实现进度与局域网联调.md](接口实现进度与局域网联调.md) 为准。

## 当前目录与职责

以 [AGENTS.md](AGENTS.md) 为工程契约，采用水平分层，业务层内部按领域组织：

```text
src/main/java/com/butvan/recordagent/
├── RecordAgentApplication.java
├── config/                 # 启动校验的业务策略配置
├── controller/<领域>/      # 协议、参数校验与认证身份
├── service/<领域>/         # 公共业务接口
│   └── impl/               # 用例、事务和规则
├── service/query/          # 有界明细读取策略
├── repository/<领域>/      # PostgreSQL JDBC 适配
├── repository/mapper/      # 持久化和聚合结果映射
├── entity/                 # 表行快照，不直接作为 API 响应
├── dto/<领域>/             # 请求与响应契约
├── storage/file/           # BlobStore 及本机存储适配
└── security、filter、handler、annotation、aspect  # 横切基础设施
```

Controller 调用 Service，领域服务通过 Repository 读写自己的表，跨领域组合调用公开服务接口。成功直接返回 DTO；错误使用实际 HTTP 状态及 `{code,message,requestId}`。`@ApiLog` 与 `X-Request-Id` 关联请求，日志不记录正文、令牌、密码或财务明细。

## 本地运行

1. 准备 Java 21、Maven 3.9+、Docker；复制 `.env.example` 为仅本地使用的 `.env`，设置独立开发库及 SMTP。
2. 在本目录执行 `docker compose --env-file .env up -d postgres`，再执行 `mvn spring-boot:run`。
3. 默认监听 `127.0.0.1:9090`；查看 `/actuator/health`。手机局域网联调及正式 HTTPS 要求见接口进度文档。

数据库名和业务 schema 均为 `record`；部署凭据由环境注入。`.env`、`data/`、构建产物不入库。Flyway 在启动时执行，务必使用专用开发库。V1 是空库基线，V2～V5 按序升级，已有迁移不可改写；旧的其他基线历史需要另定兼容方案，不使用 `repair` 掩盖差异。

## 配置与接口约束

- `config/BusinessProperties` 管理验证码、会话、限流、文件大小和明细上限；非法配置阻止启动，SMTP 显式设置连接、读取和写入超时。
- 收支写入支持用户范围内的 UUID `Idempotency-Key`，重试同请求重放首次响应；缓存与流水、余额同事务提交。
- 明细使用上限加一条检测溢出，超限返回 422；分页返回 `{items,nextOffset,hasMore}`。月历只读取完整计数和最多三条标题，趋势使用完整聚合。
- 认证入口来源及密码登录邮箱限流使用 PostgreSQL 原子计数；生产反向代理信任、HTTPS、网关防护及备份恢复在部署阶段配置。

字段、错误码、分页和兼容行为见接口进度文档；表结构见 [数据库设计.md](数据库设计.md)，优化过程及验证证据见 [工程优化执行记录.md](工程优化执行记录.md)。

## 检查与测试

- `mvn spotless:apply`：统一 Java AOSP 四空格格式、导入和空白。
- `mvn test`：单元和协议错误测试。
- `mvn verify`：格式检查、单元测试、打包及真实 PostgreSQL 集成测试；需要 Docker。

默认测试镜像锁定 `postgres:17.11`。镜像仓库不可用时可用 `-Dtest.postgres.image=postgres:17` 指向本地缓存的兼容镜像；此替代不等于锁定版本验收。Testcontainers 使用随机端口与独立数据库，不连接真实用户数据库。独立 CI 见 `.github/workflows/verify-ios-backend.yml`。

## 历史目录说明

以下段落保留旧设计记录，已由上面的当前目录规范替代，不代表当前包路径和接口进度。

<details>
<summary>旧设计记录</summary>

业务模块内部按实际代码建立 `api`、`application`、`domain`、`persistence`，不预建空目录。`src/test/java` 镜像对应生产包；Flyway 脚本位于 `src/main/resources/db/migration`。

</details>

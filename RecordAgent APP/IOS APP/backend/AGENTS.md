# RecordAgent 业务后端工程约束

本文件适用于当前目录及全部子目录，叠加仓库根目录 `AGENTS.md`。开发或修改本项目时先读取 [后端开发规划.md](后端开发规划.md)，核对当前阶段、接口和数据归属。

## 目录与包结构

- 本目录是独立 Maven 根工程；启动类固定在 `com.butvan.recordagent` 根包。根包不放业务逻辑，避免组件扫描范围和包职责含糊。
- 工程采用标准水平分层，且在 Controller、Service、Mapper、Repository、DTO 等业务层内部，统一按业务领域（如 `auth`、`calendar`、`finance`、`record` 等）细分二级子包：
  - `controller.<module>`：REST Controller 控制层，负责接收参数、认证鉴权与响应返回（如 `controller.auth`、`controller.calendar`）；每个方法标注 `@ApiLog`。
  - `service.<module>`：业务服务接口（如 `service.auth.AuthService`、`service.calendar.TodoService`、`service.mail.MailService`），具体实现类统一内聚在子包 `service.<module>.impl` 中（如 `service.auth.impl.AuthServiceImpl`、`service.calendar.impl.TodoServiceImpl`）。
  - `repository.<module>`：数据持久层，以 SQL / JDBC 访问权威数据库（如 `repository.auth.AuthRepository`、`repository.calendar.TodoRepository`）。
  - `repository.mapper.<module>`：Spring JDBC RowMapper 列映射（如 `repository.mapper.auth`、`repository.mapper.calendar`）。
  - `dto.<module>`：请求与响应数据传输对象（如 `dto.auth`、`dto.calendar`）。
  - `entity`：与表列对应的持久化行快照实体，不直接暴露给 API。
  - `security`、`annotation`、`aspect`、`handler`、`filter`：认证授权、日志切面、统一异常处理与通用过滤器等横切基础设施。
- 严禁提交空包或占位目录，不得保留无代码的残留目录。
- `src/main/resources/db/migration/` 当前以单个 `V1` 作为新建空库基线；后续结构调整新增递增版本，不再改写已应用脚本。合并前已有 Flyway 历史的数据库须另行制定兼容迁移，不得直接套用新基线或用 `repair` 掩盖差异。测试包镜像生产包，端到端测试可放根包。目录移动时同步修正包名、导入、测试和本文件。

- 本目录是独立的 Spring Boot + PostgreSQL 多用户业务后端；初期服务 iOS，后续承接桌面业务。Agent 编排、模型调用、桌面 sidecar 和本机操作能力归属其他项目。
- 以日历、财务、资料、记录、任务等业务域划分代码。Controller 只处理协议、认证结果和 DTO；业务规则在领域服务；数据库与附件通过各自 Adapter 访问。跨域组合通过公开服务接口，不直接查询其他领域表。
- 所有业务读写从已认证请求取得稳定用户 ID；查询、更新、删除及文件访问必须校验所有权。客户端传入的用户 ID、邮箱或文件路径不得作为授权依据。
- PostgreSQL 是结构化业务数据的权威存储；数据库名及业务 schema 名均为 `record`，结构变更只通过版本化迁移脚本。金额用最小货币单位整数，日期、时间和 IANA 时区分开表达；写入提供版本冲突语义，跨表不变量在事务内维护。
- 附件只以稳定文件 ID 关联业务对象，二进制内容经受控存储 Adapter 读写。密码、验证码、令牌、数据库和邮件凭据不得出现在源码、提交文件、日志或 API 响应中。
- 新增或修改 API 时同步维护 HTTP 方法、路径、请求和响应 DTO、错误码、鉴权要求与 iOS 映射；每个 REST Controller 方法遵守根目录 `@ApiLog` 约束，日志对敏感字段脱敏。
- HTTP 错误统一使用实际状态码及 `{ code, message, requestId }`；`code` 是稳定的客户端分支依据，`requestId` 由服务端生成并通过 `X-Request-Id` 返回。禁止向客户端或日志输出异常原文、用户输入及凭据。
- 每个阶段至少验证认证隔离、领域不变量、PostgreSQL 迁移及主要 HTTP 错误路径；用真实 PostgreSQL 的隔离测试环境验证持久化行为，不以桌面 SQLite 测试替代。

本目录已建立独立 Maven 工程与版本化领域表；表结构和事实归属见 [数据库设计.md](数据库设计.md)。账户及业务 API、部署环境仍按规划分阶段建立。新增更具体的子目录规则时在对应目录建立中文 `AGENTS.md`，并更新本文件的子级索引。

## 子级 DOX 索引

当前无子级 `AGENTS.md`。

# iOS 页面与后端接口清单

> 依据：2026-09-29 的 `RecordAgent/Features/`、`Services/`、`AppEnvironment` 与本后端源码。统计“页面”时计入可独立进入的整页、Sheet 与日历便签浮层；不计导航栏、列表卡片、警告框等组件。同一个视图在不同业务入口展示独立内容时分别计数。

## 1. 页面盘点：24 个

| 模块 | 页面（数量） | 当前页面行为 | 服务端事实 / 需要的能力 |
| --- | --- | --- | --- |
| 认证（3） | 登录/注册/验证码登录、忘记密码、用户协议/隐私协议 | `MockAuthService`；找回密码按钮目前仅模拟成功；协议为本机内容 | 邮箱验证码、注册、密码及验证码登录、找回密码、会话续期/退出、当前用户；协议如需在线更新再提供版本接口 |
| 首页（3） | 首页日览、年/月选择、记录详情 | Debug 使用会话内样例；Release 业务读取不可用；可切换待办完成 | 按日快照、近七日趋势、按 ID 详情、待办实例完成；月选择器自身不需 API |
| 日历（7） | 月历与日时间线、新建类型选择、新建待办、新增日程、写手记、记一笔、周期便签详情浮层 | 月标记/周期便签/日详情及三种新增走同一假服务；记一笔只有填写页，保存禁用 | 月摘要、按日详情、周/月周期实例、待办完成、待办/日程/手记 CRUD、财务账户和收支写入；选择页自身不需 API |
| 工作台（5） | 工作台入口、财务、资料、记录、任务 | 财务/资料/记录复用当日投影；任务显示未开放 | 财务账户/流水、资料/手记、学习时段与统计；自动任务属于后续阶段；入口自身不需 API |
| 我的（6） | 个人总览、设置目录、账户、偏好、关于、资料编辑 | 总览 Debug 样例、Release 不可用；活动从按日数据循环读取；昵称/头像/封面是按邮箱隔离的本机副本；语言和关于信息本机处理 | 当前用户、月总览、按月活动/连续天数、昵称与图片持久化、登出；语言切换、包版本与静态协议不需 API |

合计 **3 + 3 + 7 + 5 + 6 = 24**。`CalendarCreateEntryView` 的待办、日程、手记三种导航目的地按三个界面计；`WorkspacePreviewView` 的财务、资料、记录、任务按四个业务页面计。首页的搜索、通知仅为提示框，不计独立页面。

### 当前真实接入状态

- iOS 的 `AppEnvironment` 始终默认 `MockAuthService`；Debug 的首页/日历/个人总览为假服务，Release 的这些服务返回不可用。现有页面不是生产接口联调的证据。
- 新后端目前有 PostgreSQL V1 表结构、行映射和通用 HTTP/安全底座，**没有业务 Controller**；`SecurityConfiguration` 当前拒绝除健康检查外的全部请求。
- 资料编辑的本机副本、忘记密码假成功和“记一笔”禁用保存必须在接入相应服务后才可改为真实成功态。

## 2. 统一 HTTP 契约

前缀 `/api/v1`；除注册、验证码和登录外均需 `Authorization: Bearer <accessToken>`。用户 ID 只取服务端认证结果，客户端的邮箱或 URL 参数不得作为所有权依据。成功响应直接返回明确 DTO；错误使用实际 HTTP 状态和 `{ code, message, requestId }`，并带 `X-Request-Id`。常用错误：`INVALID_ARGUMENT` 400、`UNAUTHENTICATED` 401、`FORBIDDEN` 403、`NOT_FOUND` 404、`VERSION_CONFLICT` 409、`RATE_LIMITED` 429、`SERVICE_UNAVAILABLE` 503。资源 ID 猜测不得泄露他人记录。

日期使用 `YYYY-MM-DD`，月份 `YYYY-MM`，带时间的事件使用 ISO 8601 与 IANA `timeZone`；金额用整数分。列表提供有上限的 `limit` 与游标。所有修改返回稳定 `id` 和递增 `version`；更新/删除带 `expectedVersion`，冲突返回 409。分页、正文长度、附件大小与可用日期范围由服务端校验。

下表 `P0` 为可登录并完成日历纵向流程；`P1` 补齐首版日历与个人总览；`P2` 是工作台完整业务与自动任务。`现状` 均为待实现，除非另行标明。

## 3. 接口与功能

### 3.1 账户和个人资料

| 优先级 | 方法与路径 | 请求关键字段 → 响应关键字段 | 功能与页面 |
| --- | --- | --- | --- |
| P0 | `POST /auth/codes` | `email, purpose(register/login/reset_password)` → `retryAfterSeconds` | 发送验证码；邮箱规范化、频率限制、摘要存储、过期与一次性使用；登录/注册/找回密码 |
| P0 | `POST /auth/register` | `email, code, password` → `AuthSession` | 验证后建立稳定用户 ID；密码自适应哈希、邮箱唯一 |
| P0 | `POST /auth/password-login` | `email, password` → `AuthSession` | 密码登录；与验证码登录指向同一用户 |
| P0 | `POST /auth/code-login` | `email, code` → `AuthSession` | 验证码登录；防重放与频率限制 |
| P0 | `POST /auth/refresh` | `refreshToken` → 轮换后的 `AuthSession` | 短期访问令牌续期、旧刷新令牌撤销 |
| P0 | `POST /auth/logout` | 当前会话 → `204` | 撤销当前设备会话；账户页 |
| P0 | `GET /me` | 无 → `id,email,nickname,avatarFileId,coverFileId,createdAt,version` | 登录恢复、账户资料、个人页身份区 |
| P1 | `POST /auth/reset-password` | `email,code,newPassword` → `204` | 真正重置密码并撤销旧会话；替换找回密码的假成功 |
| P1 | `PATCH /me` | `nickname,avatarFileId,coverFileId,expectedVersion` → 更新后的用户资料 | 昵称/头像/封面跨设备同步；替换本机展示副本 |

`AuthSession` 需提供 `token,refreshToken,expiresAt,user`，与当前 iOS 类型同名；凭据仅存客户端 Keychain，服务端只保存摘要。邮件发送供应商与生产部署配置仍需确定；未配置时验证码接口返回 503，不返回验证码或在日志中打印。

### 3.2 首页与日历

| 优先级 | 方法与路径 | 请求关键字段 → 响应关键字段 | 功能与页面 |
| --- | --- | --- | --- |
| P0 | `GET /overview/days/{date}` | `timeZone` → 当日有界 `entries,totals,trend[7]` | 首页按日四类摘要、近七日支出/专注趋势；条目含稳定 ID、类型、日期和摘要 |
| P0 | `GET /calendar/months/{month}` | `timeZone` → 日期标记、至多三条标题摘要、分类计数、封面文件 ID | 月历切月；不得返回整月正文 |
| P0 | `GET /calendar/days/{date}` | `timeZone` → 当日待办/日程/手记/财务/学习/资料投影 | 日时间线与详情入口；正文按日或 ID 再取 |
| P0 | `POST /calendar/todos` | `title,detail,date,time?,timeZone,recurrence` → `id,version` | 新建待办；周期支持无/每日/每周/每月，周/月投影到自然周期末 |
| P0 | `PATCH /calendar/todos/{id}/occurrences/{date}` | `completed,expectedVersion` → `id,date,completed,version` | 首页、时间线与便签复用同一实例完成事实 |
| P0 | `POST /calendar/schedules` | `title,startAt,endAt?,location?,detail,timeZone` → `id,version` | 新增日程；结束不得早于开始 |
| P0 | `POST /calendar/journals` | `date,title,body,mood?,timeZone` → `id,version` | 写手记；与资料中的每日手记共用稳定 ID |
| P1 | `GET /calendar/periodic-todos` | `date,timeZone` → 周/月组、发生日、完成状态、版本 | 两种周期便签与详情浮层；只返回有效周期实例 |
| P1 | `GET /calendar/entries/{id}` | 无 → 类型化完整详情及版本 | 首页/日历详情；附件仅返回文件 ID |
| P1 | `PATCH /calendar/todos/{id}`、`DELETE /calendar/todos/{id}` | 字段或 `expectedVersion` → 更新 DTO / `204` | 待办编辑删除；周期规则变更须有明确后续实例语义 |
| P1 | `PATCH /calendar/schedules/{id}`、`DELETE /calendar/schedules/{id}` | 字段或 `expectedVersion` → 更新 DTO / `204` | 日程编辑删除 |
| P1 | `PATCH /calendar/journals/{id}`、`DELETE /calendar/journals/{id}` | 字段或 `expectedVersion` → 更新 DTO / `204` | 手记编辑删除；资料侧同步投影 |
| P1 | `GET /calendar/preferences`、`PUT /calendar/preferences` | 周/月便签及其他已定义设置、`expectedVersion` → 偏好 DTO | 后续日历设置；当前 iOS 尚无该设置页 |

日历读取由持有事实的领域服务形成投影；不能在 Controller 直接跨域查询表。`CalendarDataServiceProtocol` 目前只有创建、月标记和便签查询，接入前需扩展编辑/删除/详情 DTO，并对自然日与时区做映射。

### 3.3 财务、资料、学习与文件

| 优先级 | 方法与路径 | 请求关键字段 → 响应关键字段 | 功能与页面 |
| --- | --- | --- | --- |
| P1 | `GET /finance/accounts`、`POST /finance/accounts` | 账户名称、初始余额等 → `id,balanceInCents,version` | “记一笔”必须先选本人账户；无账户状态可创建第一个账户 |
| P1 | `GET /finance/categories` | 收支类型 → 本人分类与默认分类 | 收支分类；服务端保持分类归属 |
| P1 | `POST /finance/transactions` | `accountId,type,category,amountInCents,note,date,time,timeZone` → `id,version,balanceInCents` | 日历“记一笔”创建收支；账户余额与流水同事务；支出不能使余额负数 |
| P1 | `GET /finance/transactions` | 日期范围、游标 → 有界流水列表 | 财务当日投影与后续完整流水页 |
| P1 | `PATCH /finance/transactions/{id}`、`DELETE /finance/transactions/{id}` | 字段、`expectedVersion` → DTO / `204` | 更正或删除流水时回滚旧余额影响再应用新影响 |
| P1 | `GET /records/{id}`、`GET /records` | ID 或日期/分类/游标 → 资料正文或摘要 | 手记与资料双向可见；日历只拿摘要，不复制第二份手记 |
| P1 | `GET /study/days/{date}` | 自然日、时区 → 时段与分钟数摘要 | 日历学习记录、首页专注趋势、个人累计时长 |
| P1 | `POST /files`、`GET /files/{id}/content`、`DELETE /files/{id}` | 受限二进制/文件 ID → 元数据或内容 | 手记、资料、头像、封面和日历图片；校验格式、大小、绑定及所有权 |
| P2 | `GET /finance/overview`、`GET /finance/statistics` | 日期范围 → 账户余额、分类/月趋势 | 完整财务工作台 |
| P2 | `POST/PATCH/DELETE /records` | 资料正文、标签/分类、版本 → 资料 DTO | 完整资料工作台、回收站与附件绑定 |
| P2 | `POST/PATCH/DELETE /study/sessions` | 开始/结束、分类、版本 → 时段 DTO | 完整记录工作台及补录；跨日与重叠规则 |
| P2 | `/automation/...` | 配置、提醒渠道与执行记录 | 工作台“任务”；当前页面明确未开放，先单独设计通知和确认语义 |

### 3.4 我的与本地页面

| 优先级 | 方法与路径 | 输出与用途 |
| --- | --- | --- |
| P1 | `GET /overview/profile?month=YYYY-MM` | 总资产、累计支出、资料数/字数、学习分钟数、月待办完成率及上月对比；个人总览 |
| P1 | `GET /overview/activity?month=YYYY-MM` | 每日计数、最近条目稳定 ID、连续记录天数；替代目前最多 46 次逐日请求 |
| 无 | 年/月选择器、工作台入口、设置目录、语言选择、包版本、静态协议内容 | 本机计算/资源；不为了页面数量添加空 API |

## 4. 开发次序与验收

1. **P0：账户安全 → 日历纵向流程。** 先实现受认证身份、邮件验证码/会话，再让一个测试用户完成登录、按日读取、新建待办、完成待办、查看月历；随后日程/手记创建。任何业务 Controller 都不能在当前全拒绝的安全配置下误开放。
2. **P1：补齐首版日历。** 完成财务账户/收支、周期便签、手记/资料单一事实、学习只读、详情编辑删除、文件、个人总览与活动。写入后重新读取权威状态，版本冲突和跨用户访问有回归验证。
3. **P2：工作台完整能力与自动任务。** 财务全量报表、资料库、专注记录和任务模块按后续独立需求扩展；不能把当前预览页当作接口已完成。

首批接口的实现需同时补 iOS HTTP DTO、协议适配和认证失效处理。后端至少校验参数、鉴权/所有权、幂等或版本冲突、事务不变量及错误码；具备隔离 PostgreSQL 后再验 Flyway 与跨用户读写。前端的演示/不可用服务在真实 API 接通前保留明确边界，不切换为假成功。

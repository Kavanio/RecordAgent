# ButvanAgent 工程协作规范（DOX）

本文件是仓库根目录的工程契约，适用于整个 `ButvanAgent` 项目。所有新增、修改、移动或删除文件的工作，均必须遵守本文件及目标目录下更近层级的 `AGENTS.md`。

## 一、DOX 文档治理

- `AGENTS.md` 是其所在目录树的强制性工作契约，不是参考建议。
- 修改任何文件前，必须从仓库根目录开始，逐级读取目标路径上的全部 `AGENTS.md`；距离目标文件最近的规则优先，但不得削弱本文件的 DOX 要求。
- 每次会话中都必须重新读取适用的 `AGENTS.md`，不得依赖历史记忆。
- 发生影响职责边界、目录结构、接口契约、工作流、输入输出、权限、持久化位置或用户体验的变更后，必须执行 DOX 复核。
- 若变更影响本文件所属的全局规则，必须同步更新本文件；若新增子目录规范，必须在对应目录创建中文 `AGENTS.md`，并维护父级的子级索引。
- 小型、纯实现细节的修复可以不修改 `AGENTS.md`，但仍必须确认现有约束未被破坏。

## 二、项目定位与目录职责

本项目是基于 `React + Tauri` 桌面端、`Spring Boot + AgentScope Java` 后端的智能体应用。目录必须按职责稳定划分，禁止把临时代码、构建产物、运行态数据或跨层实现混入业务源码。

iOS 业务后端位于 `RecordAgent APP/IOS APP/backend/`，规划为独立 Spring Boot 项目，以 PostgreSQL 保存多用户业务数据；当前 `agent-backend/` 继续服务桌面端并使用本地 SQLite。新项目初期仅供 iOS 使用，后续桌面业务功能迁入同一服务，Agent 再独立拆分；不得把其代码混入现有桌面 sidecar 模块。

| 目录 | 职责与约束 |
| --- | --- |
| `agent-frontend/` | React、TypeScript、Vite 与 Tauri 桌面端代码；仅放置前端构建、展示和桌面壳层相关内容。 |
| `RecordAgent APP/IOS APP/RecordAgent/` | 独立 Swift/SwiftUI iOS App 的规划文档与后续原生工程；通过服务器 API 承载日历、财务、资料、记录和任务能力，不混入 Agent 编排或桌面端构建产物。 |
| `RecordAgent APP/IOS APP/backend/` | 独立 Spring Boot + PostgreSQL 多用户业务后端；首期为 iOS 提供账户及业务 API，后续承接桌面业务，不承载 Agent 编排。 |
| `agent-frontend/src/components/` | 可复用视图组件；按业务域建立目录，例如 `chat/`、`model/`、`layout/`、`common/`。 |
| `agent-frontend/src/services/` | HTTP、SSE、存储与第三方调用等基础设施适配；不得承载页面状态或 JSX。 |
| `agent-frontend/src/context/` | 跨页面共享状态与领域上下文；不得把一次性局部状态提升到此处。 |
| `agent-frontend/src/features/` | 前端领域能力模块；封装不属于视图或基础设施的解析、规则与应用交互逻辑，例如 Slash Command。 |
| `agent-frontend/src/types/` | 前端领域类型、接口和 DTO 定义；禁止放置运行逻辑。 |
| `agent-frontend/src-tauri/` | Tauri/Rust 桌面端能力；系统权限与原生能力必须最小化授权。 |
| `agent-frontend/src-tauri/binaries/` | Tauri sidecar 产物目录：启动器脚本入库，fat jar 与最小 JRE 运行时不入库。 |
| `agent-backend/scripts/` | 后端构建与打包脚本（sidecar 产物生成）；不得混入业务代码。 |
| `scripts/` | 项目级一键打包脚本（前端 + 后端 sidecar 组装）；不得混入业务代码。 |
| `scripts/backend-launcher/` | Windows 后端 sidecar 原生启动器源码（Rust）；由打包脚本在 Windows 上编译生成 exe。 |
| `.github/workflows/` | GitHub Actions 自动化；SemVer tag 创建桌面端 Release，另允许 `develop` 后端相关变更构建并推送 ACR 私有镜像；不构建桌面安装包、不自动部署服务器、不运行 CI 测试。 |
| `agent-backend/server-network/` | Spring Boot 启动、Controller、DTO、API 通用能力、AOP、网络适配层、自动任务、业务 Agent Tool Adapter、通用文件资产与存储 Adapter，以及单机业务数据的 SQLite 持久化；业务表必须按领域归属，禁止形成通用数据大杂烩。 |
| `agent-backend/server-network/src/main/java/butvan/agent/network/review/` | 长期复盘：独立授权、固定模型身份、结构化事实摘要、用户目标、建议与反馈、有界后台生成与两段式邮件冻结；使用现有 SQLite，默认关闭，未授权时不调用模型。 |
| `agent-backend/server-agents/` | AgentScope、模型工厂、智能体编排、Tool 注册 seam、工作区与配置领域逻辑；不得反向依赖 `server-network` 的业务实现。 |
| `agent-backend/server-feishu/` | 飞书等即时通讯渠道集成：长连接事件接收、消息收发与渠道适配；仅依赖 `server-agents`，不承载 Agent 编排逻辑。 |
| `agent-backend/*/src/main/resources/` | 仅保存不含密钥的默认配置和资源；真实用户配置不得硬编码于 yml。 |
| `.agentscope/` | AgentScope 运行态工作区；不得手工提交会话、日志、缓存或临时任务数据。 |
| `UI COMPONENTS.md` | 前端全量统一封装 UI 组件契约清单（表格形式）；任何 UI 组件的新增、修改、重构或废弃均需同步维护此文档。 |
| `docs/`（新增时） | 架构、接口、决策记录和操作文档；文档应使用中文，专业术语可保留英文。 |

### 目录与文件管理

- 新增代码前先确认归属目录；不确定归属时优先建立清晰的业务域目录，而不是继续堆积在根目录或 `components/` 顶层。
- 禁止新增无职责说明的 `utils`、`common`、`helper` 大杂烩目录；通用能力必须按明确领域命名。
- 禁止提交 `node_modules`、`dist`、`target`、IDE 配置、日志、缓存、运行会话、真实密钥和用户本地配置。
- 一个文件只承担一个清晰职责；页面编排、可复用组件、请求服务、领域类型和样式不得无边界混写。
- 重命名、移动或删除文件后，必须同步更新所有导入、文档、测试和相关 `AGENTS.md` 索引。
- 桌面端打包采用 Tauri sidecar 方案：统一入口为 `scripts/build-app.sh`（内部执行 `pnpm tauri build`，由 beforeBuildCommand 调用 `agent-backend/scripts/package-sidecar.sh` 生成后端 sidecar）；`pnpm tauri dev` 不打包、不拉起 sidecar，开发时后端在 IDEA 等本机环境启动（默认 8081）。打包模式默认由 Tauri（`src-tauri/src/backend.rs`）启动、健康检查与退出清理 sidecar，端口动态分配；用户通过桌面设置“后端连接”的配置弹窗选择本机或远程、填写 API 地址与 Token；页面分别显示本次实际连接和已保存配置，保存有变化时提示用户确认自动重启（可稍后重启并保留待生效入口），配置不变不要求重启，重启必须通过 Tauri 正常退出流程清理原 sidecar；启动时先检查远程健康状态，8 秒未通过则本次自动降级到本机后端，重启后重新尝试远程连接；开发模式降级到本机 8081。远程可用时不启动本地 sidecar，远程不可用时才启动，保证自动任务只有一个调度器。前端 API 地址由 `services/api.ts` 统一获取，禁止在组件中硬编码后端地址或端口。远程部署必须设置 `BUTVAN_API_REQUIRE_TOKEN=true`、通过受保护环境配置提供至少 32 字符的 `BUTVAN_API_TOKEN`、设置受限 CORS 来源并配置网络访问控制；当前业务仍是单用户本地数据模型，不得暴露到不可信网络或作为多用户服务。
- 桌面 Release 工作流 `.github/workflows/release.yml`：只有推送严格格式 `v<major>.<minor>.<patch>` 或带 `-alpha.N`、`-beta.N`、`-rc.N` 后缀的 SemVer tag 才会运行；工作流校验 tag、根目录 `VERSION`、版本同步状态、tag 是否已合入 `develop`，以及 `CHANGELOG.md` 中对应的非空章节，然后仅创建 GitHub Release 并引用该章节作为说明。带预发布后缀的 tag 创建 GitHub Pre-release；普通版本 tag 创建正式 Release。工作流不运行构建、测试或安装包上传。该 Release 工作流不响应 PR、分支 push 或 iOS 后端路径变更。独立的 `.github/workflows/backend-image.yml` 仅在 `develop` 的桌面后端源码、Maven 配置、镜像构建文件或该工作流变化时自动运行，也允许在 `develop` 手动触发；使用 Java 21 构建 JAR（跳过测试），只发布 `linux/amd64` 到阿里云 ACR，保留 `sha-<完整提交号>` 标签与摘要，只有提交仍是 `develop` 最新提交时才更新 `develop` 镜像标签。ACR 用户名和密码仅通过 GitHub Secrets 注入；不得自动连接或部署 Windows 服务器，不影响既有数据挂载；操作说明与 Compose 覆盖文件分别位于 `agent-backend/DOCKER.md`、`agent-backend/compose.acr.yaml`。版本准备、变更日志格式和 tag 操作步骤见 `README.md` 的“桌面端版本发布”一节；Windows sidecar 仍由 Rust 原生启动器（`scripts/backend-launcher/`）支持。

### 分支管理策略

本项目采用三层分支模型，分支流向为：`ios-app/*` 或 `desktop/*` → `main` → `develop`。

| 分支 | 定位与约束 |
| --- | --- |
| `ios-app/*` | iOS App 及其独立业务后端的代码分支；从 `main` 创建，完成后合并回 `main`。 |
| `desktop/*` | 桌面端及其本地 Agent/后端的代码分支；从 `main` 创建，完成后合并回 `main`。 |
| `main` | 本地集成分支；用于汇聚已完成的功能分支，在本地执行完整测试与验证。验证通过后合并到 `develop`。不直接向远程推送，仅作为本地集成测试的中间层。 |
| `develop` | 生产推送分支；`main` 本地验证通过后合并到此分支并推送到远程仓库。版本 tag 触发桌面 Release；后端相关变更触发 ACR 镜像发布。 |

- 凡修改 `RecordAgent APP/IOS APP/` 下 iOS App 或独立业务后端的代码，分支名必须以 `ios-app/` 开头；凡修改 `agent-frontend/`、`agent-backend/` 或桌面端打包与运行代码，分支名必须以 `desktop/` 开头。修复、重构和新增功能均适用，开始代码修改前先核对当前分支。
- 同一任务涉及两端代码时，按端拆成各自前缀的分支；共同的接口契约先确定，再分别实施。仅修改全局文档时不受这两个代码分支前缀限制。
- 新功能开发必须从 `main` 创建独立的功能分支，禁止直接在 `main` 或 `develop` 上提交业务代码；创建分支仍须遵守本文件的授权约束。
- 功能分支完成后，先合并到 `main` 进行本地集成测试；测试通过后再从 `main` 合并到 `develop` 推送。
- 合并到 `main` 和 `develop` 时，优先使用 `--no-ff`（非快进合并）以保留合并记录。
- 功能分支合并完毕且确认无需保留后，应及时删除已合并的功能分支，保持分支列表清洁。

## 三、后端工程规范

### 分层与接口

- `Controller` 只负责协议适配、参数接收、响应包装和鉴权边界；不得编写 Agent 编排、模型创建、文件操作或复杂业务逻辑。
- 业务逻辑放入 `Service` 或明确的领域组件；模型供应商适配集中在 `ModelFactory` 或其专属实现中，禁止散落条件判断。
- 请求/响应对象使用独立 DTO；禁止把持久化对象、AgentScope 第三方对象或内部领域对象直接暴露给 API。
- 所有 REST Controller 方法必须标注 `@ApiLog("接口作用描述")`，由 `ApiLogAspect` 输出包含 Description、参数、客户端 IP、状态和毫秒级 Cost 的 `[API-LOG] START/END/ERROR` 日志。
- 新增 API 必须同步明确 HTTP 方法、URL、请求字段、响应结构、异常语义和权限要求；对前端有影响时同步更新前端类型与服务层。
- Agent 运行时默认不得将项目 `AGENTS.md`、完整 `MEMORY.md` 或整个 Knowledge 内容注入每次模型调用；项目规则和领域资料通过对应工具按任务需要检索，个人历史只允许由下述有界上下文模块自动召回或由记忆工具显式读取。若重新启用自动 Workspace Context，必须设置真实 Token 预算并补充用量回归测试。
- 主 Agent 保持禁用 AgentScope 默认 Workspace Context；常驻 System Core 必须通过测试限制在 1,200 个 `TokenCounter` 估算 Token 内。个人上下文统一由 `server-agents/context` 的 `ConversationContextAssembler` 组装，默认总预算 850（Profile 300、Memory 500、Top-K 4），并由 `ContextInjectionMiddleware` 仅在 Model Call 前临时注入，禁止写入 AgentState 或 transcript；个人画像和开关分别保存于用户工作区的 `profile/PROFILE.md` 与 `profile/settings.json`，显式空画像必须阻止 `MEMORY.md#User Profile` 回退，暂停只停止自动注入而不得删除数据。画像辅助维护默认关闭，只能在聊天完成后按记忆指纹和 24 小时间隔低频生成提案；提案、维护状态和确认历史分别保存于 `profile/proposals/pending.json`、`profile/maintenance.json` 与 `profile/history/`，必须携带来源与置信度，并通过画像 revision 校验后由用户显式确认才能写入，禁止后台静默改写。新增上下文来源必须接入该唯一 seam、设置硬预算并补充注入与归因测试。
- 主 Agent 的 System Prompt 按会话类型组装：普通会话含日常聊天、语言与判断模块，项目会话只保留工程执行与安全模块；两者共用身份、上下文检索、计划审批、危险动作、工具调度、语气与环境规则，并各自保持在 1,200 个 `TokenCounter` 估算 Token 内。普通聊天不要求计划书或验收报告，但任何会话中的工程／业务操作仍受工具权限与审批约束。英语练习跟随当前语言与情境，不得因既往经历长期强制双语；未核对来源不得引用书籍原话，分歧须给具体依据且不升级为人格判断。
- 学习方法卡由 `server-agents/method` 从随应用发布的 `src/main/resources/methods/cards.json` 只读检索，经延迟加载的 `methods` 能力组 `method_search` 返回，每次最多两卡、结果不超过 1,800 个本地估算 Token；正文不常驻 System Prompt，也不进入个人上下文自动召回。新增或修改卡片必须核实一手来源及定位，保留核验日期、概括、适用／不适用条件和证据限制；应用建议与书籍原话分开，不自动创建目标、待办或个人记忆。
- Agent 恢复出厂仅由设置页经预览与明确确认触发，不注册为 Tool；`server-agents/reset` 的持久化屏障统一控制准入与代次，网络层负责协调与领域 Adapter。新增个人状态写入者、异步任务或远端记忆时必须接入该生命周期，并验证重置后旧结果不可发布。删除限于白名单，保护业务数据、人工复盘目标、附件、凭据、偏好和真实项目；未知 workspace 内容保持失败保护状态。细节与实施记录见 `docs/Agent恢复出厂设置/README.md`。
- 经历记忆的生命周期 seam 位于 `server-agents/memory`，空间、授权和无正文来源／任务账本位于 `server-network/memory`，默认关闭；受理后登记资格、权威成功终态后尝试入队；默认不配置外发连接，真实聊天试用经显式连接确认、用途授权及独立开关启用后，由有界后台消费者处理已登记来源。来源资格必须绑定原授权 revision、可信渠道和重置代次，重新授权不得补录旧轮次；补偿只检查已有资格凭据，原始聊天仍以 transcript 为权威，账本不保存正文或凭据。后续接入远端发送、召回及删除时阅读 `docs/RecordAgent总体建设规划/M2开工方案.md`，不得以保存设置代替用途授权。
- 经历记忆的远端目标必须在副作用前持久化，并由必需重置参与方核对清理；撤权不等于删除，接收方不可用或删除结果未知时保留目标与重置屏障。重置后的记忆保存／召回关闭，凭据、业务数据和原 M1 历史状态按各自契约保留。
- 经历记忆设置与来源管理位于桌面“个人上下文”；纠正正文独立存于 `memory_correction`，当前仅本地保存，不扩大聊天外发授权。纠正／删除先原子停用旧来源并保留原聊天，再通过原管理任务显式核对远端目标；只有已验证删除且异步写入终结才能显示完成。管理界面须区分“已记录的已知目标清理步骤”和“完整擦除已核实”，前者不得单独解除 UNKNOWN、空间召回暂停或重置屏障。实现管理或删除能力时遵守 `agent-backend/server-network/AGENTS.md` 的“经历记忆”节。
- Hindsight 只读连接检查默认关闭，仅由显式请求触发；服务健康与必要 OpenAPI 字段存在不构成保存／召回授权或删除完成证明。生产删除必须覆盖远端任务载荷中的原文及所承诺范围的派生数据，不能仅凭取消成功或文档 404 标记 VERIFIED；严格接入不以健康报告自动启用；用户明确接受限制的真实聊天试用单独配置，不升级完整删除保证。
- Hindsight HTTP 协议 Adapter 的父／子操作身份与清理意图由 `memory_remote_operation`／`memory_remote_child` 无正文账本持久化；重启沿用原目标，受理未知不重复提交正文。已知源文档及任务载荷清理不等于完整派生删除，证据不足持续 UNKNOWN；该 Adapter 可供隔离测试、纯合成试跑及明确确认的真实聊天试用；运行时由默认未配置的 HindsightTrialService 装配，不替代用途授权或重置租约。
- 经历记忆合成试跑只走测试源码中的显式入口，独立本地账本与随机 bank，不开放真实聊天、生产 Gateway 或前端后门；运行、恢复或清理时阅读 `docs/RecordAgent总体建设规划/M2合成试跑说明.md`。调用次数限制不代表供应商费用封顶，试跑通过也不代表完整删除已验证。合成入口的明文例外只能限定事先确认的唯一地址、空 Token 与固定虚构内容，不构成真实聊天上传授权。
- 真实聊天试用通过个人上下文中的 Hindsight 连接入口配置，连接确认、用途授权、保存／召回开关各自独立；只有启用后的获准新普通聊天可处理，不补传旧记录。公网 HTTP 另需确认当前地址的明文风险且不允许携带 Token；正常 Adapter 构造仍保持 HTTPS／字面 loopback 限制。连接与凭据存于受保护的 `~/.butvan-agent/hindsight-connection.json`，恢复出厂保留；未核实旧目标存在时不能更换接收方。删除仍可 UNKNOWN，保持来源停用、空间召回暂停及原重置屏障，不把试用接通说成完整删除或 M2 全部验收通过。具体 HTTP、文件、调度契约见 network 子级规范。
- 自动经历召回从 `ConversationContextAssembler` 经 `MemoryRecallPort` 接入，保存与查询许可独立；查询只取获准普通用户文本，默认未配置远端，未接入时保持原本地检索。预算上限 850／300／500 Token、Top-K 4；有远端参与时不借空结果、暂停或失败回落本地绕过来源失效。远端抽取仅定位来源，模型参考正文使用同一次权威读取中核对过的历史用户原文与本地时间，按来源去重，避免把抽取补充的日期、频率或转述观点当成用户事实；这不替代真实回复效果验收。每次 Model Call 前复核画像开关、重置代次及来源有效性，失效片段移出本轮缓存；查询、结果及有效性回调只留在本轮内存，禁止写入 AgentState／transcript。
- Agent 工具 Schema 默认按 `ToolSchemaRoutingPolicy` 中的能力组延迟暴露，只常驻轻量元工具；新增或重命名工具时必须同步确认其分组，未知工具仅作为兼容兜底保持常驻，禁止无评估地恢复全量 Schema 注入。
- `AgentFactory` 按会话 ID 与权威项目根隔离 Agent／Toolkit；同一项目下不同会话也不得共用可变工具分组，防止并发会话互相停用已向模型暴露的工具。相同会话继续复用自身状态，模型切换时统一关闭旧实例；不得以开放全量工具或绕过权限修复分组不一致。
- Agent 聊天运行必须使用稳定 `runId` 贯穿请求、SSE、运行注册表和终态收尾关联；显式取消必须通过后端取消接口向 AgentScope、生产线程、模型适配器和工具传播。传输层断开与用户显式取消必须区分；取消后必须保留 partial assistant、工具状态和 usage，且只有一个终态路径可写入。真正可恢复的暂停只能在有明确 checkpoint、待恢复动作和工具幂等语义后开放，不得用 Java 线程 suspend/resume 冒充。
- 聊天图片附件（桌面端上传、飞书下载）统一经 `AgentImages` 按文件头校验后包装成 `AgentImageAttachment`，仅接受 PNG／JPEG／WebP／GIF、单张 ≤ 10 MiB、每轮 ≤ 4 张且原始字节合计 ≤ 12 MiB；`mediaType` 与扩展名不可信。图片只在本轮内存中存在，不得写入 transcript、checkpoint、渠道账本或 Token 用量，请求记录里只能保存可读文字说明。发送图片前必须检查 `ModelHolder.supportsVision()`，未标注一律按纯文本模型拒绝。HTTP JSON 请求体另限 17 MiB，必须在反序列化前对实际读取字节执行限制；桌面草稿随会话隔离，迟到读图不得进入另一会话。修改图片接口、输入框或限额时阅读 `docs/聊天图片输入契约.md`。
- Agent 正常终态正文使用主 Agent 的 `AgentResultEvent` 完整快照，增量只用于运行中展示；子 Agent 结果不得覆盖主回复。非 HTTP 渠道通过 `AgentStreamSession.completion()` 取得已持久化且运行解注册后的精确 `AgentTurnResult`，禁止仅凭队列增量拼接判定最终正文；持久化渠道在受理时固定 `turnId` 并通过 `AgentUserCall` 传递，已有轮次不可重复追加或重放。权限暂停时 completion 仅表示本段生产者收尾，结果可以为空，必须先等待其解注册再恢复原 runId。
- 工具审批以“每用户、每会话最多一个待处理批次”为硬约束，审批必须绑定原始 `runId`，恢复前必须原子领取并立即释放旧批次槽位，禁止以新 `runId` 重放同一批工具。同一批次的全部未决工具必须在一张审批卡片内展示，允许逐项决定或批量选择，并通过单次原子请求提交完整决定，禁止前端逐项提交形成半完成状态。桌面端刷新后通过后端权威查询恢复审批卡片；正常 HITL 恢复必须优先消费完整 `ConfirmResult`，不得启用会在确认前补写失败结果的框架自动恢复。进程重启不承诺继续旧审批，只有新用户轮次开始且不存在应用审批句柄时，才允许把遗留 AgentScope 工具调用安全收尾为中断结果，避免会话永久停留在 `ASKING`。
- 学习状态跨窗口同步统一使用后端 SSE：所有写入在事务成功提交后发布领域事件，连接建立及自动重连时必须先下发当前权威快照；主窗口内只允许一个共享订阅，禁止使用定时 HTTP 轮询或仅依赖前端本地事件推断状态。

### 代码质量与安全

- 后端使用 Lombok 消除样板代码；类、公共方法、关键字段和复杂分支必须保留规范、准确的中文 Javadoc 或注释。
- 类名使用 PascalCase，方法与变量使用 camelCase，常量使用全大写蛇形命名；包名全小写并遵循业务域分层。
- 禁止捕获异常后静默忽略；必须记录有上下文的日志，或转换为可识别的业务异常。
- 禁止在日志、异常响应、配置文件和代码中输出 API Key、Token、密码或完整敏感请求体。
- 用户模型配置统一持久化在 `~/.butvan-agent/config.json`，不得把用户密钥或个性化配置写入 `application.yml`、`application-vendor.yml` 或源码。自定义模型以独立 Provider ID 区分连接，`type=custom`、`protocol=openai|anthropic` 与 `baseUrl` 必须贯穿保存、选择及重启恢复；协议适配统一复用 `ModelFactory`，连接测试通过后端实际模型请求完成。模型条目上的 `supportsVision` 是用户声明的图片识别能力，必须随模型（不是随连接）保存与恢复，未标注一律按纯文本模型处理，禁止猜测或默认开启。实现或调整模型配置时须阅读 `docs/自定义模型API配置.md` 的地址、接口及验证契约。
- 桌面端长期偏好（顶栏模块开关、日历便签状态、侧边栏宽度、学习小窗位置、Slash Command 定制、记录摘要文案、后台运行、提醒音色与上传音频、远程后端地址）统一持久化在 `~/.butvan-agent/desktop/`：`preferences.json` 保存前端长期偏好键值，`task-desktop.json`、`task-reminder/`、`backend-connection.json` 保存桌面能力配置。该目录与后端业务数据同寿命，删除应用、重装或用第三方卸载工具清理 `~/Library` 都不得丢失；WebView localStorage 只能作为同步缓存与浏览器开发环境兜底，不得作为长期偏好的唯一来源。启动时由 `desktop_preferences` 模块执行幂等迁移，把旧版本写在 Tauri 应用配置目录与 localStorage 的值搬入新位置，且已存在的新值优先于旧值。
- 通用文件资产由 `server-network/file` 的 `FileAssetService` 管理元数据、所有权、业务绑定和生命周期，业务模块只能持有稳定文件 ID，不得直接依赖磁盘路径或第三方存储 SDK；二进制内容通过 `BlobStore` seam 读写，本地文件默认位于数据库同级的 `files/objects/`，SQLite 仅保存元数据。第三方存储密钥不得写入文件资产表、接口响应或日志。
- 聊天轮次 Token 用量随 assistant 消息写入 `~/.butvan-agent/transcripts/*.jsonl`；标题等非聊天模型调用写入 `~/.butvan-agent/usage/system-usage.jsonl`；未结束轮次仅暂存在 `~/.butvan-agent/runs/*.json`，终态落盘或重启恢复后必须清理。供应商 Usage 是实际总量，System、History、Current User、Tool Schema、Tool Result、Profile Context、Memory Recall、RAG 与 Other 是携带计数器版本的本地归因估算，两者不得混淆或互相补齐。SQLite 中的 Token 用量表仅作为可从上述文件重建的统计读模型，不得取代原始记录。
- AgentScope 工作区、工具权限、文件与网络访问必须按最小权限设计；任何可能执行本机操作的能力都应具备明确的审批、范围和错误反馈。
- 系统设置跳转只能通过参数固定的 Tauri 命令暴露，禁止允许前端传入任意 URL 或本机命令；不支持直达的平台必须提供可执行的手工路径说明。
- 自动任务以 `server-network/automation` 的配置、执行和通知记录为权威事实；远程模式下由服务器持续运行调度和邮件队列；桌面端提供本机原生使用状态采集与系统通知，电脑关机或 App 完全退出时本机能力暂停。需确认的提醒统一使用固定尺寸的全局提醒窗口：到点播放提示音，用户确认后停止声音；久坐提醒继续倒计时至配置的休息时长，结束且后端确认成功时再播放一次提示音并关闭窗口。休息遮罩默认关闭，仅在用户确认休息后的有效倒计时期间覆盖所有显示器，结束、断连或原生续约超时必须释放，保留系统快捷键；不得在等待确认时锁定。普通桌面通知离线超过 15 分钟后跳过，人工待确认提醒保留。提醒窗口音色、播放结束后的重复间隔及用户上传音频属于本机桌面偏好，统一由 `~/.butvan-agent/desktop/task-reminder/` 管理，上传须校验格式、可播放性和大小，试听与正式提醒隔离。无需确认的任务继续使用系统通知。预览不得发送，邮件只能投递到已验证的绑定邮箱；每次执行的邮件、桌面和确认状态分别持久化，进程中断后的未知投递不可自动重发。

## 四、前端工程与 UI 组件规范

### 组件复用与封装

- 本项目以**自定义 UI 组件**为唯一设计与实现基线；未经明确批准，不引入通用 UI 组件库来替代现有视觉体系。
- 一旦某类交互或展示元素被确定为产品组件，例如输出框、消息气泡、输入框、按钮、选择器、空状态、加载态、错误提示或设置卡片，必须抽离为职责单一的统一组件；禁止在多个页面复制 JSX、内联样式或交互逻辑。
- 可复用基础组件放在 `agent-frontend/src/components/common/`；领域复用组件放在对应域目录，例如 `components/chat/`。页面只负责数据编排和布局，不重复实现基础组件细节。
- 组件应提供清晰的 TypeScript `Props` 类型、受控状态边界、可访问性语义和必要的事件回调；禁止通过 `any` 逃避领域类型设计。
- 同一组件的尺寸、间距、圆角、状态颜色、禁用态、加载态和错误态必须一致；新增变体优先通过受控 `variant`、`size`、`state` 等属性扩展，而不是复制一份组件。
- 组件对应样式使用 CSS Modules，与组件同目录同名保存；禁止在业务页面持续扩张大段内联样式。仅一次性的动态数值可使用内联样式。
- 引入或调整组件时，必须检查已有组件能否复用；若发现重复实现，应在本次改动中合并或记录明确的后续重构任务。
- 日历、财务、资料、记录及后续同级工作区统一使用 `common/TopBar`：左侧为图标、页面名称与可选副标题，搜索通过 `search` 插槽接入，操作通过 `actions` 接入。顶部按钮统一使用 `TopBarAction`：主要操作用 `primary`、次要操作用 `outline`、轻量操作用 `ghost`；纯图标操作设置 `iconOnly` 和中文 `aria-label`。尺寸、颜色、圆角、间距、焦点与窗口安全内边距由公共组件维护，页面通过属性扩展，不覆盖按钮外观。
- 根目录 `UI COMPONENTS.md` 为全量统一封装 UI 组件的权威表格清单与长期维护契约；凡新增、修改、重构、移动或废弃公共 UI 组件及领域统一复用组件，必须同步更新 `UI COMPONENTS.md` 中的表格记录（包含组件名称、源码路径、样式/依赖、核心职责、接口契约、状态变体与应用场景），确保契约与实现严格对齐。

### 前端分层与状态

- 页面组件负责路由或页面级编排；领域组件负责具体业务交互；`services` 负责 API/SSE 调用；`types` 负责模型定义；不得跨层反向依赖。
- 所有后端 API 调用必须集中在 `services`，统一处理响应结构、超时、错误映射与类型；组件内不得散落重复 `fetch` 实现。
- 跨页面且需要持久化或同步的状态使用 Context 或专用状态模块；仅限单组件使用的状态保留在组件内部。
- API 基础地址、功能开关和环境差异必须通过配置集中管理，禁止在多个组件硬编码。
- 聊天 Top 栏的日期、待办、财务、天气与节假日等摘要必须作为独立模块接入统一的信息栏与覆盖式详情面板；模块可见性由对应业务设置页管理并通过 `featurePreferences` 即时同步。各模块只请求自身启用的数据，加载或失败状态必须相互隔离，不得阻断聊天主流程；外部数据源必须经后端稳定 DTO 适配并设置缓存，密钥不得返回前端。
- 月历保持白底，用低饱和事项色条区分待办、日程、学习、资料和支出；日期格优先展示真实事项标题，每格最多三条，超出按需查看。月视图仅加载统计与有界标题摘要，完整正文按日读取。固定日期的周／月待办按指定日期进入日历，31 表示自然月最后一天；未指定星期／日期的周／月待办仅进入对应便签，勾选后记录到实际完成日的日历；月历统一维护一个延迟悬浮框，点击日期更新右侧详情；浮层分类折叠，右侧完整展开，共用详情组件。
- 日历周期待办便签使用 `components/calendar/StickyTodoNote` 统一实现纸张、胶带、卷角、勾选进度和动画；仅在存在未指定具体星期或日期的真实周／月待办时显示对应便签，固定日期待办不进入便签，勾选复用待办完成接口。周、月便签在日历右下角独立展开或折叠，多张展开时只允许轻微错位重叠；可见性、默认展开状态和每张便签当前状态必须持久化并在日历设置中管理。展开后可在日历工作区内拖拽，并保留抬起、速度倾斜及松手回弹反馈。
- 未检测到模型配置时，必须展示全屏居中的独立 `ModelInitPage`：纯白背景、椭圆形“取消 / 继续”按钮、极简排版；禁止改为遮罩弹窗。
- 所有用户可见文案、错误信息和空状态应使用清晰中文；技术名词、模型名和协议名可保留英文。
- 设置详情页必须按功能归纳为“分组标题 + 圆角设置卡片 + 行分隔”的结构，统一复用 `components/settings/SettingsGroup` 与 `SettingsRow`；相关选项放入同一分组，禁止把不同职责的开关连续平铺在页面中。
- 保存、删除、导入、权限请求等短暂操作结果统一通过窗口顶部居中的全局 `Message` 展示，不得在页面内容流中临时插入横幅；字段校验、加载失败、空状态和需要就地重试的上下文反馈仍应保留在所属组件附近。
- macOS 主窗口使用 Tauri Overlay 标题栏时，窗口安全内边距必须统一由 `--window-titlebar-inset` 提供；拖拽区域只能标注非交互容器，按钮、输入框和链接必须保持可点击，其他平台不得额外增加标题栏空白。

## 五、验证、评审与交付

- 修改前先检查工作区状态，保留并避免覆盖用户已有改动。
- 修改后至少执行与改动最贴近的格式化、类型检查、单元测试、构建或静态检查；若因环境或权限无法执行，必须说明原因和未验证范围。
- 前端测试只有 `pnpm --dir agent-frontend test` 一个入口（`node --test` 自动发现 `*.test.mjs`），禁止恢复或新增按模块拆分的具名测试脚本；测试统一经 `agent-frontend/src/testing/harness.mjs` 引导。具体约束见 `agent-frontend/AGENTS.md`。
- 不修复与当前任务无关的问题；发现阻断性问题时，应单独说明，不得隐式扩大改动范围。
- 提交结果必须说明：修改了什么、为何修改、验证结果、遗留风险以及需要用户决策的事项。
- 每次代码修改完成并通过必要验证后，必须将本次修改提交到当前分支；提交前应确认范围并保留用户已有改动。不得自行创建分支、推送、发布或写入外部系统，除非用户明确要求。

- 长期复盘默认关闭且保存配置不等于授权；只有用户显式确认范围、周期、回看窗口和固定模型连接后才可生成。模型选择固定为该连接，不跟随聊天默认模型；撤销授权立即生效并禁用未冻结的 AI 邮件副本，已经发送的邮件无法撤回。复盘生成在独立线程与短事务中运行，不阻塞原任务调度，失败、超时或超预算一律降级为基础报告且不自动重试；同一周期数据补录默认复用既有结果。复盘反馈只由用户提交，未反馈不得判为失败。

## 六、子级 DOX 索引

- 已建立子级 `AGENTS.md`：`agent-backend/server-feishu/`（渠道集成模块职责与配置约束）。
- 已建立子级 `AGENTS.md`：`agent-backend/server-network/`（HTTP 接口与本地 SQLite 持久化约束）。
- 已建立子级 `AGENTS.md`：`agent-frontend/`（前端验证入口、测试引导、文件规模与样式组件契约）。
- 已建立子级 `AGENTS.md`：`RecordAgent APP/IOS APP/RecordAgent/`（原生 iOS 技术栈、API 数据边界与工程约束）。
- 已建立子级 `AGENTS.md`：`RecordAgent APP/IOS APP/backend/`（独立多用户业务后端、PostgreSQL 与 API 约束）。
- 已建立子级 `AGENTS.md`：`agent-backend/server-network/src/main/java/butvan/agent/network/review/`（长期复盘授权、事实与记忆持久化契约）。
- 自动任务的细节约束见 `agent-backend/server-network/AGENTS.md` 的“自动任务”节；产品行为见 `docs/任务模块产品需求与操作说明.md`。
- 根目录负责项目级工程规范、目录边界、架构契约与根文档。
- 当 `agent-frontend/` 或 `agent-backend/` 出现独立且稳定的局部规则时，应分别建立中文 `AGENTS.md`，并在本节登记其职责范围。

## 行为准则
- 回复尽量简短。一个简单问题配一个直接回答，不要分段加标题。
- 做任务之前先说一句你要做什么，别一声不吭就开始。
- 做完之后一两句话总结。改了什么，接下来该做什么。
- 探索性问题（"这个怎么办？""你觉得呢？"）回 2-3 句建议，不要直接动手。
- 可查明的事实先查证；在已明确的任务范围内，常规、可逆的实现选择沿用项目惯例和用户已有决策。需求存在实质歧义、授权不明或涉及重大取舍时先询问用户，不把未经验证的假设当作事实；本文件的分支、推送、发布和外部写入权限约束继续适用。
- 除非用户明确要求，不进行截图、浏览器逐页查看或其他手动界面效果检查；前端改动优先通过构建、类型检查、Lint 和自动化测试验证，避免不必要的 Token 消耗。

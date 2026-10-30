# agent-frontend 工程契约

本文件是 `agent-frontend/` 的目录级工作契约，在根 `AGENTS.md` 之下生效，只补充前端局部规则，不削弱根契约的任何要求。修改本目录内任何文件前必须先读根 `AGENTS.md` 与本文件。

## 一、验证入口（唯一）

前端只有三个验证命令，其他一律不得新增具名分片脚本。

```bash
pnpm lint    # oxlint 静态检查
pnpm test    # node --test，自动发现 src 下所有 *.test.mjs
pnpm build   # tsc -b && vite build，类型检查与生产构建
```

- `pnpm test` 是**唯一**测试入口。历史遗留的 `test:tasks`、`test:models`、`test:finance`、`test:sidebar`、`test:loading` 已删除，禁止恢复；它们曾导致 32 个测试文件里 20 个从未被执行。
- 只跑单个文件时显式传路径：`pnpm test src/components/chat/ChatWorkspace.test.mjs`。
- 新增测试文件必须命名为 `*.test.mjs`，并放在被测源码同目录；放在别处会脱离自动发现。
- 任何改动提交前必须三个命令全绿；无法执行时必须说明原因与未验证范围。

## 二、测试写法

- 所有测试统一通过 `src/testing/harness.mjs` 引导，它负责 jsdom 环境、Vite 模块加载、React 挂载与 fetch 桩。
- **禁止**在测试里手写 `new JSDOM(...)`、`createServer(...)`、`createRoot(...)` 引导样板。存量尚未迁移的测试在被修改时必须顺带迁移，不得复制原样板继续新增。
- 被测源码一律用 `loadModule('/src/...')` 加载，走与生产一致的 Vite 模块图（别名 `@/`、无扩展名导入、TS/JSX 转换）。**禁止**用 `import './foo.ts'` 直接让 Node 解析源码——Node 的 ESM 解析器不认无扩展名导入，会误报模块缺失。
- 被测模块持有模块级可变状态（偏好缓存、单例注册表等）时，必须 `resetModules()` 后重新 `loadModule`，保证用例与执行顺序无关。
- 组件测试必须挂载真实组件并断言用户可见行为（文案、角色、`aria-*`、交互结果）。只测抽出来的纯函数不算覆盖该组件。
- 测试不得访问真实网络、真实用户数据或真实文件系统；请求一律经 `useFetchStub()` 与 `deferred()` 控制。
- `src/testing/` 目录不得改名为 `src/test/`：Node 会把 `test/` 目录下所有文件当作测试执行。

## 三、文件规模与职责

- 单个 `.ts` / `.tsx` 文件不得超过 **400 行**。超限必须按职责拆分：状态逻辑进 hook，纯计算进 `features/` 的纯函数模块，视图碎片进同目录子组件。
- 页面组件只做数据编排与布局；可复用视图进 `components/`；跨页面状态进 `context/`；HTTP/SSE/Tauri 适配进 `services/`；领域类型进 `types/`；领域纯逻辑进 `features/`。
- 禁止新增 `utils/`、`helpers/`、`common` 之类无明确领域的杂物目录。`components/common/` 只放与业务域解耦的基础组件，`components/ui/` 只放底层复合控件（树、高亮代码块、模型选择器），业务组件不得塞进 `ui/`。
- 已超限的存量文件（`App.tsx`、`ChatWorkspace.tsx`、`ModelSettingsPage.tsx`、`ChatTopBarInformation.tsx`、`Sidebar.tsx`、`ui/ai-model-select.tsx`）列为待拆分债务：改动这些文件时只允许继续减行，不得再增加行数。

## 四、样式与组件契约

- 组件样式使用与组件同目录同名的 CSS Modules；仅一次性动态数值可用内联样式。
- `components/ui/ai-model-select.tsx` 是仅存的 Tailwind 写法，不得再向其他组件扩散 Tailwind 类名。
- 新增、修改、重构、移动或废弃公共 UI 组件时，必须同步更新根目录 `UI COMPONENTS.md` 的表格条目；该文件是唯一权威清单。

## 五、本地数据边界

- 长期偏好统一走 `services/desktopPreferences.ts`，权威来源是 `~/.butvan-agent/desktop/`，WebView `localStorage` 只是同步缓存与浏览器开发兜底。禁止绕过该入口直接读写 `localStorage` 作为持久化。
- 后端地址一律经 `services/api.ts` 获取，禁止在组件内硬编码地址或端口。
- 经历记忆 UI 位于 `components/memory/`，从个人上下文设置进入；HTTP 集中于 `experienceMemoryService` 并继承 apiFetch 的 Agent 代次。用途授权和独立开关分开提交，未接入时关闭远端操作；来源详情按需读取，纠正明确标注仅本地。刷新恢复原管理任务，不自动发送删除核对；未知／失败不得显示完整远端删除成功，版本冲突提示刷新，失败重试复用同一请求内容的 requestId。清理文案统一经 `features/memory/cleanupPresentation.ts`：旧后端无 `cleanupProgress` 时保守回退；原文、直接事实、父任务和非空已知子清单全部有清理记录时，只显示“已完成已知内容清理，完整擦除未核实”，并明确是上次记录、召回仍暂停。此时按钮改为仅 GET 的“刷新清理进度”，不反复发送无新增证据的删除核对。

## 六、Agent 重置交互

- `components/reset/AgentResetBoundary` 在应用树挂载前读取后端屏障，处理中卸载旧树，代次变化后重新挂载，丢弃旧会话、审批与图片草稿；长期桌面偏好仍归原持久化模块。
- 状态查询失败时继续保护应用树，但保留手动重查及独立的原生后端连接设置入口；复用 `BackendConnectionSettings` 的保存与确认重启流程，不热切换当前后端。查询恢复不得中断正在编辑的连接草稿；浏览器环境提供开发后端排查指引。
- 重置请求集中在 `services/agentResetService.ts`；确认绑定预览时的实际后端，重复提交复用 requestId。状态带单调 revision，忽略迟到旧状态；个人 HTTP 写入通过统一 apiFetch 携带已确认的 Agent 代次。
- 设置页使用独立危险操作分组和确认文字；失败、等待旧写入者或正常重启时展示后端权威状态，不显示成功。仅本机 sidecar 提供原生正常重启按钮；开发或远程后端展示对应的手工正常重启说明。

### Hindsight 记忆库配置

- 个人上下文显示并配置 Bank ID；GET/PUT `/agent/memory/trial-connection` 增加 `bankId`。空串使用持久化空间对应的 `ra-<spaceId>`，缺省字段保留原配置，默认库 ID 原样保存须归一化为空串以保持旧指纹。自定义 ID 限 1—128 个英文字母、数字、下划线或连字符，保存于受保护连接文件并绑定接收方指纹；保存、召回、状态查询、删除统一使用同一目标。已有未 VERIFIED 的旧接收方目标时拒绝切换，不迁移或删除旧记忆，不解除暂停；连接检查不证明库存在或库权限已通过。前端遇到不返回 bankId 的旧后端只显示升级提示，禁止编辑库字段。

## 生活复盘入口

- 复盘设置通过 `components/review/ReviewWorkspace` 分开生活正文用途与既有统计摘要用途；生活复盘 HTTP 统一经 `services/lifeReviewApi`。保存设置不授予权限，有未保存变更时不得授权或生成。
- 版本卡片展示原始时区、归属日、覆盖截止点、来源与各渠道状态；打开历史只读，补充单独创建版本。飞书 UNKNOWN 仅显示人工核对说明，不提供自动或隐式重发。
- 作息是用户明确提供的有期限候选；未回复不生成负反馈。前端不按本机日期推断后端时区的复盘归属日。

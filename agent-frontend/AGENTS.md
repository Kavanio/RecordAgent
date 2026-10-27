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

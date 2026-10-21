# React + TypeScript + Vite

## 桌面端加载反馈测试（临时）

- `services/api.ts` 的 `DEV_API_DELAY_MS` 当前为开发客户端启用 **1000ms**：每次经过 `apiFetch` 的业务 HTTP 请求发送前额外等待一秒，并行请求独立计时；串行调用会逐次累加。
- 仅 `pnpm dev` / `pnpm tauri dev` 的客户端启用；生产构建、SSR 自动化测试、直接健康检查及 EventSource 推送不受影响，聊天流也只延迟初始请求，不逐条延迟事件。
- 无需重启后端；Vite 热更新或刷新前端窗口即可测试。测试结束后将 `DEV_API_DELAY_MS` 设为 `0` 关闭模拟，不要据此判断真实接口性能。
- 验证：`node --test src/services/requestDelay.test.mjs`、`pnpm test:loading`。

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend enabling type-aware lint rules by installing `oxlint-tsgolint` and editing `.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "options": {
    "typeAware": true
  },
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

See the [Oxlint rules documentation](https://oxc.rs/docs/guide/usage/linter/rules) for the full list of rules and categories.

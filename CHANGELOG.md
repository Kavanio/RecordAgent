# ButvanAgent 更新日志

本文档记录桌面端版本的变更。发布工作流只读取与推送 tag 完全一致的二级标题，例如 `## v0.4.0` 或 `## v0.4.1-beta.1`；该章节正文会成为 GitHub Release 说明。带 `-alpha.N`、`-beta.N` 或 `-rc.N` 后缀的 tag 会创建 GitHub 预发布版本，普通 SemVer tag 会创建正式 Release。

## v0.4.0

- **发布日期（Asia/Shanghai）**：2026-10-16
- **对比基线**：v0.3.0...v0.4.0
- **发布通道**：Stable（正式版）

ButvanAgent 0.4.0 将桌面端扩展为覆盖学习、日历、财务、资料和自动任务的个人工作台，并补齐 macOS 学习计时入口。本版以 v0.4.0-beta.1 的功能基线发布稳定版本，同时补充 macOS、Windows、Linux 从源码构建应用的完整说明。本次 GitHub Release 不含自动构建的安装包；可按 README 在目标系统上克隆该 tag 并本机打包。

### 学习与个人工作台

- 支持学习记录、学习目标、年度热力图、学习计时小窗，以及通过 macOS 状态栏查看计时和结束本次学习。
- 完善日历、财务、资料与任务工作区，包括重复待办、日历便签、流水编辑和资产调整。
- 学习状态通过服务端事件实时同步，减少多窗口状态不一致。

### Agent 与聊天

- 提供单轮及全局 Token 用量统计、Slash Command、资料引用和业务数据查询。
- 支持按需路由 Agent 工具，并完善个人上下文控制、对话停止、批量工具审批和异常恢复。
- 支持导入本地项目，并增强资料引用和 Mermaid 图表展示。

### 自动任务与桌面体验

- 提供自动任务、每日汇总和桌面提醒，并扩展天气、节假日等工作台信息。
- 完善远程后端优先、本地 sidecar 降级的桌面运行模式，以及 macOS 标题栏和系统集成体验。

### 安装与构建

- GitHub Release 提供版本说明和源码 tag，不附带预构建安装包。
- 克隆 `v0.4.0` 后，可依照 README 的平台指南在 macOS、Windows 或 Linux 原生构建安装包。

### Commits

- `52bdb41` feat: add study tracking module
- `3340d86` feat: record token usage per conversation turn
- `7943330` feat(frontend): 增加 Slash Command 基础交互
- `0b74447` feat(study): replace status polling with SSE
- `e76db35` feat: add desktop automation tasks and daily reports
- `3967074` feat: end active study from menu bar
- `bba3ecf` chore(release): 准备 v0.4.0-beta.1 预发布

**Full Changelog**: https://github.com/Kavanio/RecordAgent/compare/v0.3.0...v0.4.0

## v0.4.0-beta.1

- **发布日期（Asia/Shanghai）**：2026-10-16
- **对比基线**：v0.3.0...v0.4.0-beta.1
- **发布通道**：Beta（预发布）

这是一版功能预览版本。自 v0.3.0 以来，ButvanAgent 从聊天与 Agent 能力继续扩展为覆盖学习、日历、财务、资料和自动任务的个人工作台，并补齐 macOS 学习计时入口。本次 Release 提供变更说明与源码 tag，不包含自动构建的安装包。

### 学习与个人工作台

- 新增学习记录、学习目标、年度热力图、学习计时小窗，并支持通过 macOS 状态栏查看计时和直接结束本次学习。
- 日历、财务、资料与任务工作区持续完善；支持重复待办、日历便签、流水编辑与资产调整。
- 学习状态改为服务端事件实时同步，减少多窗口状态不一致。

### Agent 与聊天

- 新增单轮及全局 Token 用量统计、Slash Command、资料引用和业务数据查询能力。
- Agent 工具按需路由，并补充个人上下文控制、对话停止、批量工具审批及异常恢复。
- 支持导入本地项目，并增强聊天中的资料引用和 Mermaid 图表展示。

### 自动任务与桌面体验

- 新增自动任务、每日汇总及桌面提醒，并扩展天气、节假日等工作台信息。
- 完善远程后端优先、本地 sidecar 降级的桌面运行模式，以及 macOS 标题栏和系统集成体验。

### 预发布说明

- 此版本用于功能与稳定性验证，尚未提供自动构建的安装包；不建议作为稳定生产版本使用。
- 版本比较范围：[`v0.3.0...v0.4.0-beta.1`](https://github.com/Kavanio/RecordAgent/compare/v0.3.0...v0.4.0-beta.1)。

### Commits

- `52bdb41` feat: add study tracking module
- `3340d86` feat: record token usage per conversation turn
- `7943330` feat(frontend): 增加 Slash Command 基础交互
- `0b74447` feat(study): replace status polling with SSE
- `e76db35` feat: add desktop automation tasks and daily reports
- `3967074` feat: end active study from menu bar

## [未发布]

在日常开发中将尚未发布的变更追加到此处。准备发布时，将相关条目整理到新的版本章节，并清空本节。

### 新增

### 变更

### 修复

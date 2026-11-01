# ButvanAgent 更新日志

本文档记录桌面端版本的变更。发布工作流只读取与推送 tag 完全一致的二级标题，例如 `## v0.4.0` 或 `## v0.4.1-beta.1`；该章节正文会成为 GitHub Release 说明。带 `-alpha.N`、`-beta.N` 或 `-rc.N` 后缀的 tag 会创建 GitHub 预发布版本，普通 SemVer tag 会创建正式 Release。

## v0.6.0

- **发布日期（Asia/Shanghai）**：2026-11-01
- **对比基线**：v0.5.0...v0.6.0
- **发布通道**：Stable（正式版）

本版新增聊天图片输入、个人经历记忆、生活复盘及现实信息源提醒，并修复当天复盘展示、邮箱凭据保护和主动提醒的关键边界。

### 生活复盘与主动提醒

- 新增独立授权的生活复盘，支持手记、普通聊天原文、待办与学习事实、可选财务及人工目标，固定使用授权时选定的模型。
- 支持按日生成、补充经历、不可变历史版本、飞书投递，以及基于互动时间的作息参考与可选久未互动问候。
- 接入天气变化与恢复、支出变化、服务器只读健康探针和重要邮件提醒；检测规则不调用模型，各来源保持独立开关。
- 修复“准备今天”可能仍展示旧日期卡片的问题；按归属日及版本排序，新增需确认的“更新今天的复盘”，重新采集资料并保留旧版。
- 修复同一天恢复后再次异常漏报、更换探针沿用旧状态、HTTP 跳转与显式 DOWN 被误判正常的问题。
- 修复发件人白名单被显示名绕过、不同邮件去重撞号、较早未读漏扫及连接失败被当成空邮箱的问题，展示最近一次邮箱检查结果。
- 旧检测结果不得借新授权发送，移除来源后的旧复盘观点不进入新请求；模型生成与信号检查分开调度。

### 图片、记忆与 Agent

- 桌面和飞书支持向已声明图片识别能力的模型发送图片；统一校验真实格式、大小和单轮数量，图片不写入聊天持久化记录。
- 新增默认关闭的经历记忆连接、用途授权、来源管理、纠正与删除进度，以及有预算限制的跨会话召回；支持显式配置 Hindsight Bank ID。
- 增加 Agent 恢复出厂的预览、确认与代次屏障，阻止重置前的后台结果重新写回个人状态。
- 普通会话与项目会话使用不同提示词；学习方法卡按需检索，收紧历史读取范围并隔离不同会话的工具分组状态。
- 重整飞书文本投递、分段、取消、任务终态和本人回复关联，补充报告邮件发出后的飞书提示与聊天摘要召回。

### 工作台与部署

- 支持删除财务流水并按原方向回滚余额，完善周期报告的同比、资产回溯与大额收支说明。
- 桌面长期偏好统一迁入 `~/.butvan-agent/desktop/` 并迁移旧位置；前端测试统一使用同一入口。
- 提供 Windows 后端一键更新脚本，修复容器与 JVM 默认时区，保留原数据挂载及认证配置。

### 升级与验证

- 当前 IMAP 凭据启动时迁入数据库同级、仅属主可读写的 `life-mail-credentials.json`，再从业务配置和历史授权移除；同一邮箱可继续使用原凭据，变更主机、端口或账号须重新填写。
- IMAP 的 993 端口使用 TLS，其他端口强制 STARTTLS；探针只读 GET、不跟随跳转，限制完整请求等待时间和响应大小。
- 追加 V34 数据迁移。更新服务器前按部署说明备份整个用户数据目录，再拉取并重新创建容器；桌面界面需要使用新版本前端构建。
- 本地完整后端 634 项、前端 237 项测试通过，前端 Lint、生产构建及 macOS 应用打包和签名校验通过。真实模型表达和外部渠道送达不以自动测试结果替代。
- 经历记忆的远端删除仍以界面显示的已知内容清理范围为准，结果未知不表示完整擦除；未知投递不自动重发。
- 本次 GitHub Release 提供发布说明与源码，不附带预构建安装包；后端 ACR 镜像由独立工作流发布，服务器需手动更新。

**Full Changelog**: https://github.com/Kavanio/RecordAgent/compare/v0.5.0...v0.6.0

## v0.5.0

- **发布日期（Asia/Shanghai）**：2026-10-21
- **对比基线**：v0.4.1...v0.5.0
- **发布通道**：Stable（正式版）

本版新增自定义模型 API 接入，并集中完善项目与会话管理、任务同步、自动报告、桌面加载体验和后端容器部署。

### 自定义模型与模型配置

- 新增“自定义 API”模型，可填写基础地址、Model ID 和密钥，并选择 OpenAI Chat Completions 或 Anthropic Messages 请求格式。
- 自定义连接使用独立 Provider ID，支持多套网关与密钥并存，协议和地址可随配置保存并在重启后恢复。
- 模型连接测试改为由后端发起真实请求，失败时返回可执行的中文提示，不暴露密钥或供应商原始错误正文。
- 自定义 API 使用专属图标，并补齐首次配置、模型编辑、切换与删除流程。

### 项目、会话与同步

- 项目目录移动或失效时显示明确警告，并禁用新建项目会话入口。
- 项目支持从应用移除登记；移除不会删除磁盘文件，关联会话会保留为普通会话。
- 修复任务列表、执行历史与桌面通知的多窗口状态同步，区分断线与显式操作，避免自动重放有副作用的请求。

### 桌面体验

- 日历、财务、资料、设置与 Token 用量等工作区补充统一加载骨架、大树动画和局部操作遮罩。
- 明确后端连接配置的保存、待生效与重启流程，并优化窗口尺寸、提示音设置和资料/图表展示。
- 修复久坐休息遮罩、提醒窗口、日报邮件时间线和学习热力图等桌面体验问题。

### 自动任务与报告

- 日报蓝调摘要补充资产收支和待办完成情况，并统一周期报告的时间线与邮件布局。
- 完善任务执行、邮件排队、久坐提醒和确认状态处理，减少离线及进程中断后的重复投递。

### 后端与部署

- 提供后端容器镜像构建与 Linux 部署说明，`develop` 的后端变更可自动发布到阿里云 ACR。
- 更新 Windows 服务器的 ACR 镜像拉取、Compose 覆盖和数据保留操作说明。

### Commits

- `0f45cee` feat(model): support custom OpenAI and Anthropic API endpoints
- `96cd115` style(model): replace custom API placeholder icon
- `f785bd8` fix(projects): allow removing moved project references
- `ec35405` fix(tasks): restore reliable synchronization and offline-safe management
- `1b353d0` feat(desktop): 重设计图表与资料记录工作区加载骨架
- `a51d673` feat: include asset cashflow in daily reports
- `0870796` feat: containerize agent backend
- `9e0809a` fix(desktop): clarify backend connection changes and offer app restart

**Full Changelog**: https://github.com/Kavanio/RecordAgent/compare/v0.4.1...v0.5.0

## v0.4.1

- **发布日期（Asia/Shanghai）**：2026-10-16
- **对比基线**：v0.4.0...v0.4.1
- **发布通道**：Stable（正式版）

优化桌面应用在 Linux 与不同 macOS 架构上的本地打包流程。

### 安装包构建

- Linux 支持交互选择 `.deb`、AppImage 或两者，也可通过 `--bundles` 单独选择格式；未变化的最小 JRE 会复用以减少重复打包耗时。
- Linux `.deb` 将主程序和 sidecar 安装到 `/opt/ButvanAgent`，并保留 `/usr/bin` 启动符号链接。
- macOS 打包会校验 Intel、Apple Silicon 与 JDK 架构匹配，明确按目标设备原生构建。

### Commits

- `54d98f7` feat(build): improve Linux and macOS packaging

**Full Changelog**: https://github.com/Kavanio/RecordAgent/compare/v0.4.0...v0.4.1

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

# Windows 极简 JAR 部署

MinIO 只发布一个固定地址的 JAR，Windows 只需手动复制四个 BAT。BAT 以明文包含所需运行逻辑，首次自动准备文件，无需另外上传 PS1、JSON 模板、XML、说明文档或安装 MinIO Client。

## 复制与运行

1. 安装 Java 21，确认 `java -version` 输出 21。
2. 将 `deployment/windows/` 下这四个文件复制到 Windows 的一个空目录，例如 `D:\ButvanBackend`：

   | 文件 | 用途 |
   | --- | --- |
   | `update-and-start.bat` | 从固定 HTTPS 地址下载最新 JAR，校验后正常停机、备份、更新并后台启动 |
   | `start-backend.bat` | 启动已经安装的 JAR |
   | `stop-backend.bat` | 校验进程身份后正常关闭后端 |
   | `view-logs.bat` | 查看实时日志；关闭日志窗口不停止后端 |

3. 完成下文的数据迁移后，双击 `update-and-start.bat`。首次会提示输入原后端 API Token（至少 32 字符，输入隐藏），继续沿用原客户端配置的 Token；不要填写 MinIO 密钥。
4. 更新或启动成功后可以关闭 BAT 窗口。后端在当前普通用户下运行；注销或重启系统后需再次启动，本方案不安装系统服务或开机任务。

运行目录只新增一个 `data/` 文件夹，集中保存内部启动文件、本机配置、JAR、日志、临时下载和备份。四个 BAT 都能单独准备运行文件，日常通常使用“更新并启动”和“查看日志”即可。

本机配置保存在 `data/backend.env`、`data/backend-settings.json`。默认监听 `127.0.0.1:8081`，强制业务 Token，继续沿用宿主机 HTTPS 反向代理与受限 CORS。`jarUrl` 默认是下方固定地址；Java 不在 PATH 时，可将 `javaExe` 改为 Java 21 的 `java.exe` 完整路径。`userHome` 留空表示当前 Windows 用户主目录，业务数据仍在其 `.butvan-agent/` 下，与脚本的 `data/` 不是同一目录。

需要从聊天请求 Windows 主屏截图时，可在 `data/backend.env` 添加 `BUTVAN_SCREEN_CAPTURE_ENABLED=true`，正常停止后重新启动。后端须由要截图的 Windows 用户在仍连接、未锁定的交互桌面运行。截图工具每次经当前对话审批，配置、图片限额和验收见 [聊天图片回复契约](../docs/聊天图片回复契约.md)。

Java 检测会把 `javaExe` 解析为可执行文件完整路径，并在本次启动中固定使用同一结果；通过直接持有的 .NET Process 查询 `-version`，同时读取 stdout／stderr，只有退出码为 0 且主版本为 21 才通过。Windows `Start-Process` 重定向后可能返回空退出码的背景见 [PowerShell 问题记录](https://github.com/PowerShell/PowerShell/issues/5421)。空退出码、非零退出码、无法识别版本、版本不符和超时分别提示，不再统一提示重装 Java。

检测完成后的摘要在 `data/runtime/java-check.json`，包含时间、实际程序路径、版本和退出码；`java-check.err`／`java-check.out` 只保留识别出的版本文本，不保存完整 JVM 输出。出现 Java 检查错误时，可提供该摘要协助排查，不需要发送业务 Token 或本机配置。

真实配置不入库；配置文件权限限定为当前用户、SYSTEM 与管理员。备份含业务数据和凭据，部署目录与备份应限制访问，并定期清理确认不用的旧备份。

## 安全软件和脚本执行策略

2026-11-18 的火绒安全日志显示，旧版 `update-and-start.bat` 在执行时被文件实时监控按 `Trojan/BAT.Agent.df` 删除。该日志没有给出具体命中特征，不能单凭检测名断定误报。旧版包含编码命令和压缩内嵌脚本；新版已移除这些打包方式，改为可直接查看的明文源码、提取到文件后普通 PowerShell `-File` 执行，停止辅助脚本也使用普通可见进程。

重新复制项目中的四个新版 BAT 覆盖旧入口，保留既有 `data/` 与业务数据。不要从隔离区恢复旧版后继续运行。保持安全软件正常工作，先扫描新版；若仍被识别，保留检测名、日志、文件和 SHA-256，通过[火绒官方病毒反馈](https://bbs.huorong.cn/forum-44-1.html)提交复核，不以扩大信任目录来处理未知判定。仅提交不含本机配置的 BAT；不要附带 `data/` 或业务数据。

新版遵循 Windows 现有 PowerShell 执行策略，保留 BAT 的互联网来源标记到派生 PS1，不自动修改策略或解除来源限制。如果提示“禁止运行脚本”或“未进行数字签名”，先用 `Get-ExecutionPolicy -List` 查看原因，再按设备管理要求审核脚本及签名／执行授权。`Restricted` 不允许运行脚本，`AllSigned` 要求签名，`RemoteSigned` 对互联网来源脚本要求签名；参见 [Microsoft 执行策略说明](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies/)。当前交付脚本没有发行签名，要求签名的设备需先完成可信签名分发，不能直接双击运行。

启动器开始时显示 `Preparing backend launcher...`。准备失败会输出阶段、异常类型和底层原因，例如 `[write-bootstrap]` 表示写入引导文件失败，`[copy-origin]` 表示读取或复制来源标记失败；此时尚未下载或启动 JAR。保留该行错误用于排查，不要发送本机配置正文。Windows PowerShell 5.1 的来源标记通过文件系统 Provider 的 `-Stream` 参数读写；不把 `文件名:Zone.Identifier` 交给 .NET Framework 普通文件接口，来源标记仍按原字节保留。接口依据见 [Get-Content 5.1](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/get-content?view=powershell-5.1) 与 [Set-Content 5.1](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/set-content?view=powershell-5.1)。

本地自动测试覆盖明文文件准备和模拟来源标记传递；开发环境为 macOS，无法验证真实火绒引擎或 Windows 策略结果，新版是否通过火绒仍须在目标电脑复核。

## MinIO 发布

固定 JAR 地址：

[butvan-backend.jar](https://minio.server.butvan.top/recordagent/backend/butvan-backend.jar)

- Bucket 为 `recordagent`，对象键为 `backend/butvan-backend.jar`。流水线只上传这一个 JAR，不再上传启动器 ZIP、版本清单、校验文本或文档；既有历史对象由管理员按需清理，不自动批量删除。
- 提交号与 SHA-256 保存在同一 JAR 对象的 `commit`、`sha256` 元数据中，通过 `x-amz-meta-commit`、`x-amz-meta-sha256` 响应头读取；没有额外文件。Windows 在同一次 GET 中取得元数据与正文，检查实际大小、哈希和可执行 JAR 结构后才停止旧服务。
- 一次对象 PUT 完成后才切换固定地址；失败时原对象保留。流水线串行发布，写入前检查提交仍是 `develop` 最新提交，旧提交重跑不覆盖现有 JAR。
- 仅 `recordagent/backend/*` 允许匿名 `s3:GetObject`，不增加匿名列目录或写入权限，其余范围保持原策略。Windows 不保存 MinIO 凭据。
- GitHub Secrets 继续使用 `MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`。上传身份需要目标对象的 `s3:PutObject`，以及 Bucket 的 `s3:GetBucketPolicy`、`s3:PutBucketPolicy`，用于合并已授权的发布前缀只读策略。
- `develop` 后端源码、Maven、发布脚本或工作流变化触发构建与上传，也允许在 `develop` 手动触发。BAT 与文档变化不再触发 JAR 发布。流水线不部署 Windows、不构建桌面安装包、不运行 CI 测试。

## 从 Docker 或旧版启动器迁移

先正常停止旧 Docker／原生后端，停机备份整个旧数据目录，再把四个新 BAT 放到空目录。不要仅删除旧启动脚本来停止 Java。旧部署的进程与端口仍在使用时，新版会失败保护，避免两个自动任务调度器同时运行。

Docker 需先停止 `backend` 容器并禁用自动重启，同时关闭旧计划任务或旧更新入口；具体停机命令与 Compose 文件列表见 `DOCKER.md`。后续运行旧 Compose `up` 可能恢复容器的重启策略，应避免误启动。

将旧容器挂载的整个 `user-data` 内容复制到当前 Windows 用户主目录的 `.butvan-agent/`。若目标已有数据，先独立备份并人工确认，不能盲目覆盖。模型、SQLite 及 WAL、会话、图片、附件、画像和渠道账本都要一起迁移，不能只复制数据库。

容器路径与 Windows 路径不同，项目根、工作树、旧附件路径、证书等需要逐项核对；脚本不批量改写业务数据。确认历史会话、资料附件、档案图片、自动任务与邮件绑定存在后，才算迁移完成。

若四个 BAT 放在旧启动器目录中，会在目标配置缺失时将根目录的 `backend.env`、`backend-settings.json` 复制到 `data/`，已有新配置优先；旧进程、旧 JAR 和旧日志不会自动迁移或删除。应先用旧版停止入口正常停止，再使用新版更新。

## 运行、失败与回退

日志在 `data/logs/backend.log`，按天与 10MB 大小滚动，最多保留 14 天、总上限 512MB。启动初期输出在 `data/logs/boot.out.log`、`boot.err.log`，保留上一份。更新成功关闭窗口不会结束后端，日志窗口也可以随时重新打开。

下载、哈希或 JAR 格式校验失败时不停止旧服务；进程身份不符、无法正常退出或完整备份失败时不覆盖旧 JAR。新版本启动失败则保留现场，不自动回退已发生迁移的数据库。

停机备份位于 `data/backups/<时间>/`，包含整个业务目录、原 JAR 与本机版本记录。回退前先正常停止，核对数据库兼容性；必要时恢复完整停机数据备份，再恢复 JAR 与对应的 `installed.json`，最后启动。JAR 回退不等于数据库回退。

## 维护与验证

四个 BAT 是入库的交付脚本；可维护源码位于 `deployment/windows/source/`，仅开发时使用，不上传 Windows。修改源码后运行：

```bash
python3 agent-backend/scripts/build-windows-launchers.py
python3 agent-backend/scripts/build-windows-launchers.py --check
python3 -m unittest discover -s agent-backend/scripts -p 'test_*.py'
```

PowerShell 测试通过 PATH 的 `pwsh` 或 `BUTVAN_TEST_PWSH` 指定的程序运行；缺失时对应测试明确跳过。还可运行 `deployment/windows/source/test-backend.ps1`。Java 检测回归使用 `source/test-java.ps1`（也由 Python 测试发现）；设置 `BUTVAN_TEST_JAVA` 为 Java 21 完整路径后，除模拟分支外还执行真实 `-version` 查询，不启动业务后端。Windows 上另有真实 `cmd.exe` + `powershell.exe` 准备路径测试，只准备文件，不启动业务后端；macOS 明确跳过，受限执行策略也保持跳过，不自动放宽策略。测试使用临时目录和本地模拟服务，覆盖单独复制 BAT、明文文件准备、普通脚本调用、模拟来源标记传递、配置保留、损坏内容、下载元数据、文件校验、停止和备份边界，不触碰个人业务数据。真实 Windows 的双击、后台控制台、Ctrl+C 正常关闭仍需实际验收。

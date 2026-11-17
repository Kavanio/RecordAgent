# Windows 原生后端与 MinIO 发布

本方案用 Java 21 在 Windows 当前普通用户下运行后端，MinIO 保存发布产物。GitHub Actions 仅构建、上传，不连接或重启 Windows。旧 Docker 部署保留；ACR 工作流归档到 `.github/workflow-archive/backend-image.yml.disabled`，不再自动运行。桌面 Release 工作流不变。

## 执行顺序与验收

1. 配置 MinIO 私有发布空间与 GitHub Secrets，合入并推送 `develop` 后由流水线上传。
2. Windows 安装 Java 21 和 MinIO Client，复制启动器模板，设置用户目录、业务环境变量与下载账户。
3. 停止旧容器并禁用自动重启，备份、复制整份业务数据，修复容器绝对路径引用。
4. 双击 `update-and-start.bat` 下载、校验、备份并启动；检查健康状态与历史数据。
5. 双击 `view-logs.bat` 查看实时日志；关闭两个 BAT 窗口后确认后端仍可用。
6. 从客户端审批一次 PowerShell 命令，验证中文输出、工作目录、非零退出码与取消；重新更新并验证旧进程正常退出且只有一个监听端口。

本机自动测试不替代真实 Windows 控制台信号和现网迁移验收。

## MinIO 与流水线

- S3 API 地址：`https://minio.server.butvan.top`，必须是 S3 API 入口，不能是 Console 网页。
- Bucket：`recordagent`，保持私有，提前由管理员创建。
- 发布前缀：`backend/`。不可变产物在 `backend/releases/<commit>/<run-id>-<attempt>/`，跟随清单在 `backend/channels/develop.json`。
- 每次发布包含 `butvan-backend.jar`、`butvan-backend.jar.sha256`、`windows-launcher.zip` 与 `manifest.json`；清单记录提交、Java 版本、文件大小和 SHA-256。
- 只有提交仍为 `develop` 最新提交时才更新跟随清单；上传失败不更新指针，旧产物保留。清单使用禁止缓存响应，Windows 在下载前后核对清单内的固定版本路径。
- `develop` 后端源码、Maven、发布脚本、Windows 模板或工作流变化触发；支持在 `develop` 手动运行。不在 CI 中运行测试，不创建桌面 Release。

在 GitHub 仓库 Settings → Secrets and variables → Actions 添加：

| Secret | 内容 |
| --- | --- |
| `MINIO_ACCESS_KEY` | 专用上传 Access Key |
| `MINIO_SECRET_KEY` | 对应 Secret Key |

上传身份至少需要目标 Bucket 的 `backend/*` 对象 `s3:PutObject`，不需要根账户或业务数据权限。Windows 下载身份只需该前缀的 `s3:GetObject`；`mc cp` 使用完整键，无需授予列举全 Bucket 的权限。现有 ACR Secrets 可保留，但不再用于启用的工作流。GitHub Runner 和 Windows 均须能通过 HTTPS 访问该 S3 API，证书须可信。

凭据只在 GitHub Secrets 和 Windows 受保护的 MinIO Client 配置中保存，不发到聊天、不放进清单、BAT 或源码。若已公开粘贴过实际密钥，应在 MinIO 更换，并用专用上传／下载账户替代。

## Windows 首次准备

1. 安装 Java 21，确认 `java -version`。安装官方 [MinIO Client](https://minio.github.io/mc/)，将 `mc.exe` 加入 PATH，或在配置中填写完整路径。Windows PowerShell 5.1 为系统自带；脚本无需管理员权限。
2. 将 `deployment/windows/` 整个目录复制至例如 `D:\ButvanBackend`，或下载流水线的 `windows-launcher.zip` 并解压到固定目录。不要放到 Git 仓库、临时目录或业务数据目录里。
3. 复制 `backend-settings.example.json` 为 `backend-settings.json`，复制 `backend.env.example` 为 `backend.env`。两个真实文件均受 Git 忽略。配置 `userHome` 为 Windows 运行账户的主目录，例如 `C:\Users\yourname`；所有业务数据由此定位到 `.butvan-agent/`。
4. 在 `backend.env` 填写原业务 `BUTVAN_API_TOKEN`（至少 32 字符）、受限 CORS 和其他实际需要的环境变量。格式是原始 `KEY=value`，不解析引号、变量替换或命令；不要直接照搬 Docker 容器路径。脚本强制 Token，Java 参数固定监听 `127.0.0.1:8081`，沿用宿主机上的 HTTPS 反向代理。下载凭据不放在该文件，避免传给业务子进程。
5. 使用单独的只读下载身份配置别名 `recordagent-download`。`mc alias set` 的形式见[官方文档](https://minio.github.io/mc/)，在自己的终端输入凭据；不要粘贴执行记录或配置文件。该账户的 `%USERPROFILE%\.mc\config.json` 包含凭据，应限制为本人可读。可使用 `mc alias import` 从受保护文件导入以避免密钥留在命令历史。

配置中的 `minioAlias` 默认是 `recordagent-download`，`bucket` 默认 `recordagent`，`prefix` 默认 `backend`，`channel` 默认 `develop`。`javaExe` 与 `mcExe` 支持完整路径和 PATH 命令名。启动器仅管理本目录的 `app\butvan-backend.jar`；请勿在同目录另行手动启动 Java。

发布包只含程序与无密钥模板，不含 Java、MinIO Client 或用户数据。后续更新只替换 JAR；启动器自身变化需单独下载并人工核对后替换，保留真实配置。

## 从 Docker 迁移

先沿用 `DOCKER.md` 的停机备份方式保存整个 `user-data`、原 `.env` 和反向代理配置。**必须先停止旧容器，禁用它的自动重启，然后再启动原生后端**；容器停止不是删除，回退前也先停止原生后端。

在原部署目录，用实际 Compose 文件列表执行：

```powershell
docker compose -f .\compose.yaml -f .\compose.acr.yaml stop backend
if ($LASTEXITCODE -ne 0) { throw 'Stop failed; do not continue.' }
$backendContainer = docker compose -f .\compose.yaml -f .\compose.acr.yaml ps -aq backend
if ($LASTEXITCODE -ne 0 -or -not $backendContainer) { throw 'Cannot identify old backend.' }
docker update --restart=no $backendContainer
if ($LASTEXITCODE -ne 0) { throw 'Cannot disable old backend restart.' }
```

同时关闭旧计划任务或自动更新入口；再次执行 Compose `up` 可能恢复原 restart 策略。确认端口空闲后，将停机备份中的整个 `user-data` 内容复制到配置的 `<userHome>\.butvan-agent\`。若目标已有数据，先独立备份并人工合并，禁止盲目覆盖。不要只复制 SQLite；会话、图片、附件、模型、画像和渠道账本需要一起迁移。没有迁移就启动会创建一份空数据。

容器到 Windows 的路径不相同：项目根目录、工作树、SQLite 覆盖路径、附件旧路径、TLS 证书和外部工作区等须逐项核对；脚本不批量改写数据库或用户配置。确认模型、历史会话、资料附件、档案图片、自动任务和邮件绑定存在后，才算迁移成功。

## 日常入口

| BAT | 行为 |
| --- | --- |
| `update-and-start.bat` | 拉取最新清单和 JAR、核对大小与 SHA-256、停旧进程、备份整个数据目录、保留旧 JAR、替换并后台启动；最后等待健康检查 |
| `start-backend.bat` | 只启动已安装 JAR；已运行且健康时不重复启动 |
| `stop-backend.bat` | 核对 PID、创建时间和启动命令后发送 Ctrl+C；等待正常退出，超时不强杀 |
| `view-logs.bat` | 查看应用日志最后 80 行并跟随新内容；滚动或重启后自动重新打开文件，关闭窗口不停止后端 |

更新与启动完成后可关闭窗口。后端在当前普通用户权限下的独立隐藏控制台中运行，不是 Windows 服务；注销或重启系统后不承诺继续运行，需再次双击启动。本次不自动创建开机任务。

运行日志在 `logs/backend.log`，按天／单文件 10MB 滚动，最多保留 14 天、总上限 512MB；启动初期标准输出与错误记录于 `logs/boot.out.log`、`logs/boot.err.log`，每次启动保留上一份。日志可能包含个人业务信息，发布问题时先脱敏。

## 更新失败与回退

- 下载、格式或哈希校验失败：不停止旧服务。
- PID 身份不一致、端口被占用、无法正常关闭、备份失败：停止更新，不覆盖旧 JAR；查看提示与日志。
- 新程序健康检查失败：保留新 JAR、旧 JAR、备份和现场，不自动切回旧版，避免旧程序读取已迁移数据库。
- 成功更新前的 JAR 和版本清单保存在 `backups/<时间>/`；业务备份也在该目录，含凭据，须限制访问并自行清理旧备份和 staging 文件，避免磁盘耗尽。
- 手动回退：先 `stop-backend.bat`，核对数据库兼容性；必要时恢复整份停机数据备份，再替换 JAR 与对应 `installed.json`，最后启动。不要仅回退 JAR 就假定数据库已回退。

## 本地验证

```bash
python3 -m unittest discover -s agent-backend/scripts -p 'test_publish_backend.py'
mvn -f agent-backend/pom.xml -pl server-agents -am test -Dtest=CommandShellTest,BashToolTest,PromptBuilderTest,AgentSecurityTest -Dsurefire.failIfNoSpecifiedTests=false
```

若本机有 PowerShell，可解析全部 PS1 并运行 `deployment/windows/test-backend.ps1`。测试用临时目录与模拟下载／进程，覆盖损坏清单、校验失败、停止与备份失败等更新边界；真实 Windows 的后台启动、Ctrl+C 与日志窗口另按开头验收清单确认。

# ButvanAgent 后端容器部署

## 本地构建

从仓库根目录打包桌面 Agent 后端并构建镜像：

```sh
mvn -f agent-backend/pom.xml -pl server-network -am package -DskipTests
docker build -t butvan-agent:local agent-backend
```

镜像只包含 Java 21 JRE 和 `server-network` 可执行 JAR。API Token、TLS 证书、模型配置、SQLite 数据库与用户文件均在容器运行时从外部注入或挂载，不写入镜像。入口脚本会在持久化用户目录内创建 SQLite 原生库临时目录，并继续调用基础镜像的 CA 证书入口脚本。

生产部署必须设置 `BUTVAN_API_REQUIRE_TOKEN=true`、通过受保护的环境配置提供至少 32 字符的 `BUTVAN_API_TOKEN`，并将 `BUTVAN_CORS_ALLOWED_ORIGINS` 限定为实际桌面客户端来源；HTTPS 证书只读挂载，业务数据目录持久挂载至 `/home/butvanagent/.butvan-agent`。端口和 TLS 参数由部署环境覆盖。当前业务数据库是单用户本地 SQLite，不得暴露到不可信网络或作为多用户服务。

容器默认以 UID/GID `10001:10001` 运行。已有数据目录使用其他属主时，通过运行参数 `--user <UID>:<GID>` 匹配目录权限；不得通过将数据目录开放给所有用户解决权限问题。容器以 Java 21 启动内置 JAR，保留基础镜像的 CA 证书入口脚本；部署参数可覆盖默认 HTTP 端口。

## GitHub Actions 自动发布到阿里云 ACR

工作流：`.github/workflows/backend-image.yml`。发布目标：

```text
crpi-46o5657v3drrpfcl.cn-hangzhou.personal.cr.aliyuncs.com/butvan/record-agent-backend
```

- 自动触发：推送 `develop` 且修改桌面后端源码（含测试）、Maven 配置、Dockerfile、`.dockerignore`、容器入口脚本或镜像工作流。
- 不触发：PR、其他分支、桌面 Release tag、仅前端 / iOS / 文档 / Compose 覆盖文件变化。
- 手动触发：GitHub 仓库 → Actions → Publish Backend Image → Run workflow，选择 `develop`。工作流文件须先出现在仓库默认分支，手动按钮才可用；其他分支会跳过发布。
- 使用 Java 21、Maven 构建可执行 JAR，跳过测试；集成测试仍在本地合入 `main` 时执行。
- 仅构建 `linux/amd64`，对应 Windows Docker Desktop 的 Linux x86_64 容器；不构建桌面安装包。
- 每次发布 `sha-<完整40位提交号>`，并在任务摘要输出镜像 digest；需要严格固定产物时使用 digest。重跑同一提交可能因基础镜像或依赖变化而产生不同 digest。
- 发布串行执行；仅当任务提交仍是 `develop` 最新提交时更新跟随标签 `develop`，避免旧任务重跑覆盖最新镜像。如果构建期间又推送了新提交，本次只保留提交标签；若新提交不触发构建（例如仅改前端），需手动在最新 `develop` 运行一次工作流来更新跟随标签。
- 工作流只发布镜像，不登录 Windows、不重启容器、不读写生产数据。后端发布与桌面 SemVer Release 相互独立。

### 首次配置仓库凭据

1. 阿里云 ACR 控制台切换至已有个人版实例所在地域（杭州），进入该实例的「访问凭证」或仓库「基本信息」中的登录指引。以控制台提供的 `docker login` 命令确认登录用户名，并设置 / 获取镜像仓库专用密码。它不是业务 `BUTVAN_API_TOKEN`，也不是阿里云 AccessKey。
2. 确认 `butvan/record-agent-backend` 仓库可见性为**私有**。如果使用 RAM 身份，应只授予目标仓库所需权限；CI 需要推送和拉取权限，服务器身份优先仅拉取权限，具体能力以个人版控制台为准。
3. GitHub 仓库 → Settings → Secrets and variables → Actions → Secrets → New repository secret，分别创建：

   | Secret 名称 | 内容 |
   | --- | --- |
   | `ACR_USERNAME` | ACR 登录指引中的用户名 |
   | `ACR_PASSWORD` | ACR 镜像仓库登录密码 |

4. 凭据仅由 GitHub Secrets 和 Docker 凭据存储保存，不提交到仓库，不发到聊天中；不把模型 Key、业务 Token、SQLite 或个人配置放入镜像。GitHub Actions 无需生产业务 Token。
5. 将功能分支按规范合入 `main` 本地验证，再合入并推送 `develop`。该工作流本身的变更会触发首次构建；在 Actions 中确认任务成功、任务摘要存在镜像地址和摘要，再进行服务器切换。

## Windows 从现有离线镜像切换到 ACR

适用于已经运行的部署包：Compose 项目名 `butvan-agent`、服务名 `backend`，原镜像 `butvan-agent:linux-amd64` 且设置 `pull_policy: never`；数据已绑定到 `./user-data`，业务 Token 位于现有 `.env`。

首次切换只需新增 `compose.acr.yaml`，不替换原文件，不重跑要求离线镜像的旧版 `prepare.ps1`。覆盖文件只修改镜像地址、平台和拉取策略，保留原有 `127.0.0.1:8081` 绑定、认证、反向代理、健康检查、内存限制、非 root 用户、日志策略和 SQLite 可执行 tmpfs。不要将服务直接开放至公网。

### 首次切换

1. 将仓库中的 `agent-backend/compose.acr.yaml` 复制到 Windows **现有部署目录**，与原 `compose.yaml`、`.env`、`user-data` 同级。若原文件名为 `compose.yml`，以下命令统一改为该名字；不要另建部署目录或改动 Compose 项目名，否则可能误启动第二套调度器。
2. 在该目录打开 PowerShell，确认 Docker Desktop 为 Linux 容器模式，然后登录 ACR（`--username` 后替换为控制台提供的用户名，密码按提示输入，不写入命令）：

   ```powershell
   docker login crpi-46o5657v3drrpfcl.cn-hangzhou.personal.cr.aliyuncs.com --username YOUR_ACR_USERNAME
   ```

3. 确认数据已迁移且 `.env`、`user-data` 完整。校验并先拉取镜像；拉取失败时不停止正在运行的旧容器：

   ```powershell
   docker compose -f .\compose.yaml -f .\compose.acr.yaml config --quiet
   if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid; do not continue.' }
   docker compose -f .\compose.yaml -f .\compose.acr.yaml pull backend
   if ($LASTEXITCODE -ne 0) { throw 'Image pull failed; the existing container was not changed.' }
   ```

   使用 `config --quiet`，不要直接把展开后的 `docker compose config` 输出贴到聊天或日志，里面可能包含业务 Token。

4. 更新前停机备份**整个** `user-data`（SQLite 包含可能存在的 WAL 文件），保护 `.env` 的独立备份；不要只在运行时复制单个数据库文件。备份成功后重新创建容器（需要支持 `--wait` 的新版 Docker Compose）：

   ```powershell
   docker compose -f .\compose.yaml -f .\compose.acr.yaml stop backend
   if ($LASTEXITCODE -ne 0) { throw 'Stop failed; check the container before backing up.' }
   $backup = "user-data-backup-$(Get-Date -Format yyyyMMdd-HHmmss)"
   Copy-Item -Recurse -LiteralPath .\user-data -Destination $backup -ErrorAction Stop
   docker compose -f .\compose.yaml -f .\compose.acr.yaml up -d --no-build --wait --wait-timeout 180 backend
   if ($LASTEXITCODE -ne 0) { throw 'Backend did not become healthy; inspect it before continuing.' }
   docker compose -f .\compose.yaml -f .\compose.acr.yaml ps
   ```

   镜像替换存在短暂停机；备份失败应先排查，不要继续升级。备份目录包含个人数据，应保存在受限位置。旧部署包的 `check-health.ps1` 可继续用于健康检查；还应从桌面端确认模型配置、历史会话和业务数据都存在。

### 日常更新

Windows 可将 `agent-backend/scripts/update-backend.bat` 单独复制到现有部署目录（与主 Compose 文件、`compose.acr.yaml`、`.env`、`user-data` 同级）并双击运行。需要 Docker Compose 支持 `pull --policy` 与 `up --wait`。脚本强制跟随远端最新 `develop`（本次覆盖已有标签环境变量），拉取成功后停机备份 `user-data`，使用已拉取镜像重建并等待最多 240 秒；失败保留数据与备份，不自动回滚。它不会修复历史模型配置，缺少自定义 API 地址时仍需单独修复；备份含敏感数据，应限制访问。

复制 BAT 时请直接复制原文件，保留 UTF-8 无 BOM 和 CRLF 换行，不要从聊天或终端输出重新拼接内容。若看到中文注释被当成命令执行，先替换脚本；这与容器 `unhealthy` 是不同问题。`unhealthy` 表示服务未通过健康检查，需要按脚本提示查看最新容器日志；若日志仍为“自定义模型必须填写 API 基础地址”，说明启动时读取的模型配置缺少地址，不能靠重新拉镜像修复，也不应关闭健康检查。主 Compose 的 `pull_policy: never` 会被覆盖文件及更新命令的拉取策略处理，不要因此删除安全配置或数据挂载。

GitHub Actions 发布成功后，也可在原部署目录重复上面的校验、`pull`（更新时显式添加 `--policy always`）、停机备份及 `up` 步骤即可；不再需要本地构建 JAR / 镜像、`docker save`、手动传输或 `docker load`。两次使用的 `-f` 文件列表必须相同。

不要运行 `docker compose down -v`、删除 `user-data` 或覆盖 `.env`。也不要执行 `docker compose build` 或仅 `restart`：服务器只需使用已构建镜像重新创建容器。现有反向代理 / TLS 和业务 Token 不变。

### 固定版本和回滚

默认跟随 `develop`。若需固定到某次提交，先在 Actions 任务摘要找到实际完整标签，再在 PowerShell 当前会话中设置：

```powershell
$env:BUTVAN_IMAGE_TAG = 'sha-替换为完整40位提交号'
docker compose -f .\compose.yaml -f .\compose.acr.yaml pull backend
if ($LASTEXITCODE -ne 0) { throw 'Image pull failed; do not recreate the container.' }
```

随后同样停机备份并执行上述 `up`。环境变量只对当前 PowerShell 会话生效；需要跨窗口固定时，在受保护的现有 `.env` 中添加 `BUTVAN_IMAGE_TAG=sha-实际完整提交号`，保留其他内容。恢复跟随 `develop` 时删除该行，并在设置过进程变量的 PowerShell 中执行 `Remove-Item Env:BUTVAN_IMAGE_TAG` 后重新拉取和创建容器。

镜像回退不等于数据库回退；涉及不兼容数据库迁移时需先确认旧版本能否读取新数据，必要时按恢复方案使用停机备份。不要在未备份的情况下盲目降级，也不要假定旧数据卷会自动获得新数据。

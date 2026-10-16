# 后端 sidecar 产物目录

本目录存放 Tauri `externalBin` 引用的后端 sidecar 产物：

| 文件 | 说明 | 是否入库 |
| --- | --- | --- |
| `butvan-backend-<target-triple>` | 启动器：macOS/Linux 为 sh 脚本（入库），Windows 为 Rust 编译的 exe（不入库，CI 生成） | 视平台 |
| `butvan-backend.jar` | Spring Boot 可执行 fat jar | 否（gitignore） |
| `butvan-backend-runtime/` | jlink 生成的最小 JRE | 否（gitignore） |

生成方式：

```bash
./agent-backend/scripts/package-sidecar.sh
```

Linux `.deb` 打包会将 `/usr/bin` 中的主程序和 sidecar 移至 `/opt/ButvanAgent/`，并留下兼容符号链接；启动器会从 Debian 资源目录 `/usr/lib/<package>/` 查找 fat jar 和最小 JRE。最小 JRE 在后端 JAR、JDK 或目标架构变化时重建，其余打包会复用。

macOS 需要在对应 CPU 架构的设备上生成 sidecar：Intel 为 `x86_64-apple-darwin`，Apple Silicon 为 `aarch64-apple-darwin`。sidecar 的 JRE 与应用必须同架构，当前不支持跨架构或 Universal Binary 打包。

产物布局遵循 Tauri externalBin 约定：文件名必须带
`-<target-triple>` 后缀（例如 `butvan-backend-aarch64-apple-darwin`、
`butvan-backend-x86_64-pc-windows-msvc.exe`），打包时 Tauri 会自动去掉后缀并放入应用资源目录。

注意：
- 修改 macOS/Linux 的 sh 启动器脚本后，请同步更新
  `agent-backend/scripts/package-sidecar.sh` 中的生成逻辑，保持两者一致。
- Windows 原生启动器源码位于 `scripts/backend-launcher/`，由打包脚本在 Windows 上编译生成。

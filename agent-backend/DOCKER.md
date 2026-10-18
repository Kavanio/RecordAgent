# ButvanAgent 后端容器部署

从仓库根目录打包桌面 Agent 后端并构建镜像：

```sh
mvn -f agent-backend/pom.xml -pl server-network -am package -DskipTests
docker build -t butvan-agent:local agent-backend
```

镜像只包含 Java 21 JRE 和 `server-network` 可执行 JAR。API Token、TLS 证书、模型配置、SQLite 数据库与用户文件均在容器运行时从外部注入或挂载，不写入镜像。入口脚本会在持久化用户目录内创建 SQLite 原生库临时目录，并继续调用基础镜像的 CA 证书入口脚本。

生产部署必须设置 `BUTVAN_API_REQUIRE_TOKEN=true`、通过受保护的环境配置提供至少 32 字符的 `BUTVAN_API_TOKEN`，并将 `BUTVAN_CORS_ALLOWED_ORIGINS` 限定为实际桌面客户端来源；HTTPS 证书只读挂载，业务数据目录持久挂载至 `/home/butvanagent/.butvan-agent`。端口和 TLS 参数由部署环境覆盖。当前业务数据库是单用户本地 SQLite，不得暴露到不可信网络或作为多用户服务。

容器默认以 UID/GID `10001:10001` 运行。已有数据目录使用其他属主时，通过运行参数 `--user <UID>:<GID>` 匹配目录权限；不得通过将数据目录开放给所有用户解决权限问题。容器以 Java 21 启动内置 JAR，保留基础镜像的 CA 证书入口脚本；部署参数可覆盖默认 HTTP 端口。

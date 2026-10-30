# 先在仓库根目录运行：mvn -f agent-backend/pom.xml -pl server-network -am package -DskipTests
# 再从仓库根目录运行：docker build -t butvan-agent:local agent-backend
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 容器默认使用非 root 用户；部署时可按持久化目录属主覆盖 UID/GID。
RUN groupadd --system --gid 10001 butvanagent \
    && useradd --system --uid 10001 --gid butvanagent --home-dir /home/butvanagent --create-home butvanagent
ENV HOME=/home/butvanagent
# 仅容器默认使用北京时间；部署时可通过 TZ 覆盖。
ENV TZ=Asia/Shanghai

COPY --chown=butvanagent:butvanagent \
    server-network/target/server-network-0.0.1-SNAPSHOT.jar /app/app.jar
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 755 /usr/local/bin/docker-entrypoint.sh

USER butvanagent
EXPOSE 8081
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["--server.port=8081", "--server.address=0.0.0.0"]

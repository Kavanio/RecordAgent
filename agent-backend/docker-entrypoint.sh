#!/bin/sh
set -eu

# 统一容器命令与 JVM 的时区；未设置或空值均回退到北京时间。
export TZ="${TZ:-Asia/Shanghai}"

SQLITE_TMP_DIR="${HOME}/.butvan-agent/tmp"
mkdir -p "$SQLITE_TMP_DIR"

exec /__cacert_entrypoint.sh java \
    -XX:MaxRAMPercentage=75.0 \
    -Duser.home=/home/butvanagent \
    -Duser.timezone="$TZ" \
    -Dorg.sqlite.tmpdir="$SQLITE_TMP_DIR" \
    -jar /app/app.jar \
    "$@"

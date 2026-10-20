#!/bin/sh
set -eu

SQLITE_TMP_DIR="${HOME}/.butvan-agent/tmp"
mkdir -p "$SQLITE_TMP_DIR"

exec /__cacert_entrypoint.sh java \
    -XX:MaxRAMPercentage=75.0 \
    -Duser.home=/home/butvanagent \
    -Dorg.sqlite.tmpdir="$SQLITE_TMP_DIR" \
    -jar /app/app.jar \
    "$@"

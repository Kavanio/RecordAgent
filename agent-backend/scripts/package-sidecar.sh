#!/usr/bin/env bash
#
# 构建后端 sidecar 产物：
#   1. Maven 打包可执行 fat jar
#   2. jdeps + jlink 生成最小 JRE 运行时
#   3. 组装启动器脚本，输出到 agent-frontend/src-tauri/binaries/
#
# 用法：
#   ./package-sidecar.sh [target-triple]
# 未指定 target-triple 时自动使用 rustc --print host-tuple。
# 需要向 Maven 传参时（如临时覆盖 Lombok 版本），可设置环境变量 MAVEN_ARGS。
#
# 说明：
#   - 产物为 Tauri externalBin 所需的 butvan-backend-<triple> 启动器
#     + butvan-backend.jar + butvan-backend-runtime/（最小 JRE）。
#   - 启动器通过动态端口拉起后端，Tauri 侧等待 /api/health 就绪后暴露给前端。
#   - Windows 暂未支持（需 jpackage 产出原生 exe，后续按需补充）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$BACKEND_DIR/.." && pwd)"
BIN_DIR="$(cd "$BACKEND_DIR/../agent-frontend/src-tauri" && pwd)/binaries"
LAUNCHER_SRC_DIR="$ROOT_DIR/scripts/backend-launcher"

TARGET_TRIPLE="${1:-}"
if [ -z "$TARGET_TRIPLE" ]; then
  TARGET_TRIPLE="${BUTVAN_TARGET_TRIPLE:-}"
fi
if [ -z "$TARGET_TRIPLE" ]; then
  TARGET_TRIPLE="$(rustc --print host-tuple 2>/dev/null || true)"
fi
if [ -z "$TARGET_TRIPLE" ]; then
  echo "无法确定 Rust 目标三元组，请手动传入：$0 <target-triple>" >&2
  exit 1
fi
HOST_TRIPLE="$(rustc --print host-tuple 2>/dev/null || true)"
if [ -n "$HOST_TRIPLE" ] && [ "$HOST_TRIPLE" != "$TARGET_TRIPLE" ]; then
  echo "sidecar 依赖与目标架构一致的本机 jlink，目前不支持交叉打包：$HOST_TRIPLE -> $TARGET_TRIPLE" >&2
  exit 1
fi

# Windows 目标需要 .exe 后缀（Tauri externalBin 在 Windows 上要求原生可执行文件）
EXT=""
case "$TARGET_TRIPLE" in
  *windows*) EXT=".exe" ;;
esac

# 定位 JDK（macOS 优先 /usr/libexec/java_home，其次 JAVA_HOME）
if [ -x /usr/libexec/java_home ]; then
  JDK_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
else
  JDK_HOME="${JAVA_HOME:-}"
fi
# Linux 等环境未设置 JAVA_HOME 时，根据 javac 定位 JDK 根目录
if [ -z "$JDK_HOME" ] || [ ! -x "$JDK_HOME/bin/jlink" ]; then
  JAVAC_PATH="$(command -v javac 2>/dev/null || true)"
  if [ -n "$JAVAC_PATH" ]; then
    if command -v readlink >/dev/null 2>&1 && readlink -f "$JAVAC_PATH" >/dev/null 2>&1; then
      JAVAC_PATH="$(readlink -f "$JAVAC_PATH")"
    fi
    JDK_HOME="$(cd "$(dirname "$JAVAC_PATH")/.." && pwd)"
  fi
fi
if [ -z "$JDK_HOME" ] || [ ! -x "$JDK_HOME/bin/jlink" ]; then
  echo "未找到 JDK（需要 jlink/jdeps），请设置 JAVA_HOME。" >&2
  exit 1
fi
JAVA_SETTINGS="$("$JDK_HOME/bin/java" -XshowSettings:properties -version 2>&1)"
JDK_ARCH="$(printf '%s\n' "$JAVA_SETTINGS" | sed -n 's/^[[:space:]]*os.arch = //p' | head -n 1)"
case "$TARGET_TRIPLE:$JDK_ARCH" in
  *aarch64*:aarch64|*aarch64*:arm64|*x86_64*:x86_64|*x86_64*:amd64|*x86_64*:x64) ;;
  *)
    echo "JDK 架构与应用目标不匹配（$JDK_ARCH / $TARGET_TRIPLE），请切换到对应架构的 JDK。" >&2
    exit 1
    ;;
esac

echo "==> 1/4 Maven 打包（server-network + 依赖模块）"
mvn -q -f "$BACKEND_DIR/pom.xml" -pl server-network -am package -DskipTests ${MAVEN_ARGS:-}

JAR="$BACKEND_DIR/server-network/target/server-network-0.0.1-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "未找到打包产物：$JAR" >&2
  exit 1
fi

mkdir -p "$BIN_DIR"
RUNTIME_DIR="$BIN_DIR/butvan-backend-runtime"
LAUNCHER="$BIN_DIR/butvan-backend-$TARGET_TRIPLE$EXT"

echo "==> 2/4 计算依赖模块并生成最小 JRE（jlink）"
if command -v sha256sum >/dev/null 2>&1; then
  JAR_FINGERPRINT="$(sha256sum "$JAR" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  JAR_FINGERPRINT="$(shasum -a 256 "$JAR" | awk '{print $1}')"
else
  JAR_FINGERPRINT="$(cksum "$JAR" | awk '{print $1 ":" $2}')"
fi
JLINK_VERSION="$("$JDK_HOME/bin/jlink" --version 2>&1 | head -n 1)"
RUNTIME_KEY="runtime-v1|$TARGET_TRIPLE|$JAR_FINGERPRINT|$JLINK_VERSION"
RUNTIME_KEY_FILE="$BIN_DIR/.butvan-backend-runtime-build-key"
if [ -x "$RUNTIME_DIR/bin/java" ] && [ -f "$RUNTIME_KEY_FILE" ] && [ "$(cat "$RUNTIME_KEY_FILE")" = "$RUNTIME_KEY" ]; then
  echo "    fat jar、目标架构和 JDK 均未变化，复用现有最小 JRE"
else
  MODULES="$("$JDK_HOME/bin/jdeps" --ignore-missing-deps -q --print-module-deps "$JAR")"
  # jdeps 无法静态发现 Spring Boot / Tomcat 的反射依赖，补充常用模块集
  for EXTRA in \
    java.desktop \
    java.logging \
    java.management \
    java.naming \
    java.instrument \
    java.net.http \
    java.prefs \
    java.rmi \
    java.security.jgss \
    java.security.sasl \
    java.sql \
    java.transaction.xa \
    jdk.management \
    jdk.crypto.ec \
    jdk.unsupported \
    jdk.zipfs; do
    case ",$MODULES," in
      *",$EXTRA,"*) ;;
      *) MODULES="$MODULES,$EXTRA" ;;
    esac
  done
  rm -rf "$RUNTIME_DIR"
  "$JDK_HOME/bin/jlink" \
    --add-modules "$MODULES" \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress=2 \
    --output "$RUNTIME_DIR"
  # jlink 产物大量为只读（444），tauri-build 复制到 target 后仍是只读，
  # 下次构建覆盖时会报 Permission denied (os error 13)，这里改为属主可写
  chmod -R u+w "$RUNTIME_DIR"
  printf '%s' "$RUNTIME_KEY" > "$RUNTIME_KEY_FILE"
fi

echo "==> 3/4 拷贝 fat jar 并生成启动器"
cp "$JAR" "$BIN_DIR/butvan-backend.jar"

if [ "$EXT" = ".exe" ]; then
  echo "    目标为 Windows，编译原生 exe 启动器（Rust）"
  cargo build --release --manifest-path "$LAUNCHER_SRC_DIR/Cargo.toml"
  cp "$LAUNCHER_SRC_DIR/target/release/butvan-backend-launcher$EXT" "$LAUNCHER"
else
  cat > "$LAUNCHER" <<'EOF'
#!/usr/bin/env sh
# ButvanAgent 后端 sidecar 启动器（由 package-sidecar.sh 生成，勿手改）
BASE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

JAR=""
RUNTIME=""
for CAND in "$BASE_DIR" "$BASE_DIR/../Resources" "$BASE_DIR/../Resources/binaries"; do
  if [ -f "$CAND/butvan-backend.jar" ] && [ -x "$CAND/butvan-backend-runtime/bin/java" ]; then
    JAR="$CAND/butvan-backend.jar"
    RUNTIME="$CAND/butvan-backend-runtime"
    break
  fi
done

# Linux Debian 包将 Tauri resources 放在 /usr/lib/<package-name>，
# 而 sidecar 启动器仍由 /usr/bin 的兼容链接启动。
if [ -z "$JAR" ]; then
  for CAND in "$BASE_DIR"/../lib/* "$BASE_DIR"/../../usr/lib/* /usr/lib/*; do
    if [ -f "$CAND/butvan-backend.jar" ] && [ -x "$CAND/butvan-backend-runtime/bin/java" ]; then
      JAR="$CAND/butvan-backend.jar"
      RUNTIME="$CAND/butvan-backend-runtime"
      break
    fi
  done
fi

if [ -z "$JAR" ]; then
  echo "未找到 butvan-backend.jar 或 butvan-backend-runtime，请先执行 agent-backend/scripts/package-sidecar.sh" >&2
  exit 1
fi

exec "$RUNTIME/bin/java" -jar "$JAR" "$@"
EOF
  chmod +x "$LAUNCHER"
fi

echo "==> 4/4 完成"
echo "  JAR     : $BIN_DIR/butvan-backend.jar"
echo "  RUNTIME : $RUNTIME_DIR"
echo "  LAUNCHER: $LAUNCHER"
echo ""
echo "下一步：cd agent-frontend && pnpm tauri dev（调试）或 pnpm tauri build（打包）"

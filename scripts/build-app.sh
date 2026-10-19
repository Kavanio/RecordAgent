#!/usr/bin/env bash
#
# ButvanAgent 一键打包脚本
# 运行后产出桌面端最终安装包（macOS: .app/.dmg；Linux: AppImage/.deb；Windows: .msi/.exe）
#
# 用法:
#   ./scripts/build-app.sh                     # release 打包（默认）
#   ./scripts/build-app.sh --debug             # debug 构建（快速验证用）
#   ./scripts/build-app.sh --bundles dmg,app   # 指定安装包格式
#   ./scripts/build-app.sh --bundles deb        # Linux：只生成 deb
#   ./scripts/build-app.sh --install           # macOS：构建 .app 并安装/更新
#   ./scripts/build-app.sh --open              # macOS：构建、安装并启动
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/agent-frontend"

DEBUG_MODE=0
BUNDLES=""
BUNDLES_SET=0
INSTALL_APP=0
OPEN_APP=0
HOST_TRIPLE=""
HOST_ARCH=""

usage() {
  cat <<'EOF'
用法：./scripts/build-app.sh [--debug] [--bundles <格式>] [--install | --open]
  默认          仅生成安装包，不安装、不启动
  --debug       使用 debug 配置
  --bundles     指定格式，如 Linux 的 deb 或 appimage；macOS 的 dmg,app
  --install     macOS：构建并安装到 /Applications，无需打开 DMG
  --open        包含 --install，安装成功后启动应用
  --help        显示帮助
安装模式默认只生成 .app；需要 DMG 时加 --bundles dmg,app。
Linux 未指定 --bundles 时会在交互终端显示格式选择；非交互环境默认构建 deb,appimage。
更新前请从菜单栏彻底退出应用，关闭主窗口可能仍在后台运行。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug)
      DEBUG_MODE=1
      shift
      ;;
    --bundles)
      if [[ $# -lt 2 || -z "${2:-}" || "${2:-}" == -* ]]; then
        echo "--bundles 需要一个参数（如 dmg,app）" >&2
        exit 1
      fi
      BUNDLES="$2"
      BUNDLES_SET=1
      shift 2
      ;;
    --install)
      INSTALL_APP=1
      shift
      ;;
    --open)
      INSTALL_APP=1
      OPEN_APP=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1（运行 --help 查看用法）" >&2
      exit 1
      ;;
  esac
done

PLATFORM="$(uname -s)"
if [[ "$PLATFORM" == Linux && "$BUNDLES_SET" = 0 && -t 0 ]]; then
  echo "选择 Linux 安装包格式："
  echo "  1) deb"
  echo "  2) appimage"
  echo "  3) deb + appimage（默认）"
  echo "  4) all（包含 Tauri 支持的全部格式）"
  read -r -p "请选择 [1-4，默认 3]：" BUNDLE_CHOICE
  case "${BUNDLE_CHOICE:-3}" in
    1) BUNDLES=deb ;;
    2) BUNDLES=appimage ;;
    3) BUNDLES=deb,appimage ;;
    4) BUNDLES=all ;;
    *) echo "无效选择：$BUNDLE_CHOICE" >&2; exit 1 ;;
  esac
  BUNDLES_SET=1
elif [[ "$PLATFORM" == Linux && "$BUNDLES_SET" = 0 ]]; then
  BUNDLES=deb,appimage
  BUNDLES_SET=1
fi

if [[ "$INSTALL_APP" = 1 ]]; then
  if [[ "$PLATFORM" != Darwin ]]; then
    echo "--install / --open 目前仅支持 macOS。" >&2
    exit 1
  fi
  if [[ -z "$BUNDLES" ]]; then
    BUNDLES=app
  elif [[ ",$BUNDLES," != *,app,* && "$BUNDLES" != all ]]; then
    BUNDLES="$BUNDLES,app"
  fi
fi

if [[ "$PLATFORM" == Linux && ( "$BUNDLES" == all || "$BUNDLES" == *deb* ) ]] && ! command -v dpkg-deb >/dev/null 2>&1; then
  echo "生成并整理 deb 包需要 dpkg-deb，请先安装 dpkg 工具。" >&2
  exit 1
fi

if [[ -n "$BUNDLES" && "$BUNDLES" != all ]]; then
  case "$PLATFORM" in
    Linux) VALID_BUNDLES="deb appimage rpm" ;;
    Darwin) VALID_BUNDLES="app dmg" ;;
    MINGW*|MSYS*|CYGWIN*) VALID_BUNDLES="msi nsis" ;;
    *) VALID_BUNDLES="" ;;
  esac
  IFS=',' read -r -a REQUESTED_BUNDLES <<< "$BUNDLES"
  for REQUESTED_BUNDLE in "${REQUESTED_BUNDLES[@]}"; do
    if [[ " $VALID_BUNDLES " != *" $REQUESTED_BUNDLE "* ]]; then
      echo "当前平台 $PLATFORM 不支持安装包格式 '$REQUESTED_BUNDLE'。" >&2
      exit 1
    fi
  done
fi

# ---- 前置检查 ----
for CMD in node pnpm cargo rustc java mvn; do
  if ! command -v "$CMD" >/dev/null 2>&1; then
    echo "缺少依赖: $CMD（请先安装并确保在 PATH 中）" >&2
    exit 1
  fi
done
echo "==> 0/4 前置检查通过（node / pnpm / cargo / rustc / java / mvn）"

echo "==> 1/4 校验版本一致性"
node "$ROOT_DIR/scripts/sync-version.mjs" --check

cd "$FRONTEND_DIR"

PRODUCT_NAME="$(node -p "require('./src-tauri/tauri.conf.json').productName")"
BUNDLE_ID="$(node -p "require('./src-tauri/tauri.conf.json').identifier")"
INSTALL_PATH="/Applications/$PRODUCT_NAME.app"
if [[ "$INSTALL_APP" = 1 && ! -w /Applications ]]; then
  echo "没有 /Applications 写入权限，请手动安装生成的 .app。" >&2
  exit 1
fi

if [[ "$PLATFORM" == Darwin ]]; then
  HOST_TRIPLE="$(rustc --print host-tuple)"
  HOST_ARCH="$(uname -m)"
  if [[ -z "$HOST_TRIPLE" ]]; then
    echo "无法读取 Rust 主机架构，请检查 rustc 工具链配置。" >&2
    exit 1
  fi
  case "$HOST_ARCH:$HOST_TRIPLE" in
    arm64:aarch64-apple-darwin|aarch64:aarch64-apple-darwin|x86_64:x86_64-apple-darwin) ;;
    *)
      echo "无法匹配当前 Mac 架构（${HOST_ARCH} / ${HOST_TRIPLE}），请使用对应架构的 Rust 工具链和 JDK。" >&2
      exit 1
      ;;
  esac
  echo "macOS 原生架构打包：${HOST_TRIPLE}（Intel 与 Apple Silicon 请分别在对应架构设备构建）"
fi

if [ ! -d node_modules ]; then
  echo "==> 2/4 安装前端依赖"
  pnpm install
else
  echo "==> 2/4 前端依赖已就绪"
fi

# ---- 组装 tauri build 参数 ----
TAURI_ARGS=()
if [ "$DEBUG_MODE" = "1" ]; then
  TAURI_ARGS+=(--debug)
  echo "==> 3/4 开始 debug 打包（快速验证用，不推荐分发）"
else
  echo "==> 3/4 开始 release 打包（自动先生成后端 sidecar）"
fi
if [ -n "$BUNDLES" ]; then
  TAURI_ARGS+=(--bundles "$BUNDLES")
  echo "    指定安装包格式: $BUNDLES"
fi

# beforeBuildCommand 会自动执行后端 sidecar 打包 + 前端构建
echo "==> 4/4 执行 pnpm tauri build"
if [ ${#TAURI_ARGS[@]} -gt 0 ]; then
  pnpm tauri build "${TAURI_ARGS[@]}"
else
  pnpm tauri build
fi

# ---- 输出产物位置 ----
PROFILE_DIR="$FRONTEND_DIR/src-tauri/target"
if [ "$DEBUG_MODE" = "1" ]; then
  PROFILE_DIR="$PROFILE_DIR/debug"
else
  PROFILE_DIR="$PROFILE_DIR/release"
fi

# Tauri 将 Debian 包中的可执行文件默认放在 /usr/bin；应用本体与 sidecar
# 移入 /opt/<产品名>，/usr/bin 保留符号链接以兼容桌面入口和 sidecar 查找。
if [[ "$PLATFORM" == Linux && ( "$BUNDLES" == all || ",$BUNDLES," == *,deb,* ) ]]; then
  while IFS= read -r -d '' DEB_FILE; do
    "$ROOT_DIR/scripts/relocate-deb-to-opt.sh" "$DEB_FILE" "$PRODUCT_NAME"
  done < <(find "$PROFILE_DIR/bundle/deb" -maxdepth 1 -type f -name '*.deb' -print0 2>/dev/null)
fi

APP_SOURCE="$PROFILE_DIR/bundle/macos/$PRODUCT_NAME.app"
if [[ "$PLATFORM" = Darwin && -d "$APP_SOURCE" ]]; then
  # 通知服务校验代码签名身份；链接器临时签名不能代替完整 App 签名。
  codesign --verify --deep --strict "$APP_SOURCE"
  SIGNATURE_ID="$(codesign -dv "$APP_SOURCE" 2>&1 | sed -n 's/^Identifier=//p')"
  if [[ "$SIGNATURE_ID" != "$BUNDLE_ID" ]]; then
    echo "应用签名标识与 bundle ID 不一致，系统通知授权将失败：$SIGNATURE_ID / $BUNDLE_ID" >&2
    exit 1
  fi
  echo "macOS 应用签名校验通过：$SIGNATURE_ID"
fi

echo ""
echo "打包完成，产物："
find "$PROFILE_DIR/bundle" \
  \( -type f \( -name '*.dmg' -o -name '*.deb' -o -name '*.AppImage' -o -name '*.msi' -o -name '*.exe' \) \
  -o -type d -name '*.app' \) 2>/dev/null | sed 's/^/  /'
echo "目录：$PROFILE_DIR/bundle/"

if [[ "$INSTALL_APP" != 1 ]]; then
  echo "本次仅构建，未安装或更新 /Applications 中的应用。"
  echo "macOS 一键安装：./scripts/build-app.sh --install；安装并启动：加 --open。"
  exit 0
fi

PLIST="$APP_SOURCE/Contents/Info.plist"
if [[ ! -f "$PLIST" ]] || [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$PLIST")" != "$BUNDLE_ID" ]]; then
  echo "未找到本次构建的有效 .app：$APP_SOURCE，未执行安装。" >&2
  exit 1
fi
if [[ -L "$INSTALL_PATH" ]] || { [[ -e "$INSTALL_PATH" ]] && [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$INSTALL_PATH/Contents/Info.plist")" != "$BUNDLE_ID" ]]; }; then
  echo "安装目标是符号链接或其他应用，无法自动覆盖：$INSTALL_PATH" >&2
  exit 1
fi
EXECUTABLE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$PLIST")"
# 同名进程也保守视为占用，避免替换正在运行的桌面端及其 sidecar。
if pgrep -x "$EXECUTABLE" >/dev/null; then
  echo "应用仍在运行，请从菜单栏选择“退出（停止所有任务）”后重新执行。" >&2
  exit 1
fi

# 在目标卷暂存完整应用；替换失败时恢复旧版，避免半复制状态。
INSTALL_STAGE="$(mktemp -d /Applications/.butvan-install.XXXXXX)"
cleanup_install() {
  if [[ -d "$INSTALL_STAGE/previous.app" && ! -e "$INSTALL_PATH" ]]; then
    if ! mv "$INSTALL_STAGE/previous.app" "$INSTALL_PATH"; then
      echo "恢复旧版失败，旧应用保留于：$INSTALL_STAGE/previous.app" >&2
      return
    fi
  fi
  rm -rf "$INSTALL_STAGE"
}
trap 'INSTALL_EXIT_CODE=$?; cleanup_install; exit "$INSTALL_EXIT_CODE"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ditto "$APP_SOURCE" "$INSTALL_STAGE/new.app"
if pgrep -x "$EXECUTABLE" >/dev/null; then
  echo "复制期间应用已启动，取消替换，请退出应用后重试。" >&2
  exit 1
fi
if [[ -d "$INSTALL_PATH" ]]; then
  mv "$INSTALL_PATH" "$INSTALL_STAGE/previous.app"
fi
mv "$INSTALL_STAGE/new.app" "$INSTALL_PATH"
echo "已安装：${INSTALL_PATH}（版本 $(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$PLIST")）"
if [[ "$OPEN_APP" = 1 ]]; then
  open "$INSTALL_PATH"
else
  echo "启动方式：open \"$INSTALL_PATH\""
fi

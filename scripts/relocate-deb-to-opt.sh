#!/usr/bin/env bash
# 将 Tauri .deb 中的主程序和 sidecar 从 /usr/bin 移到 /opt/<产品名>。
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "用法：$0 <deb 文件> <产品名>" >&2
  exit 2
fi

DEB_FILE="$1"
PRODUCT_NAME="$2"
if [[ ! -f "$DEB_FILE" ]]; then
  echo "deb 文件不存在：$DEB_FILE" >&2
  exit 1
fi
if [[ ! "$PRODUCT_NAME" =~ ^[A-Za-z0-9._+-]+$ ]]; then
  echo "产品名包含不支持的路径字符：$PRODUCT_NAME" >&2
  exit 1
fi
if ! command -v dpkg-deb >/dev/null 2>&1; then
  echo "缺少 dpkg-deb，无法整理 deb 安装目录。" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
OUTPUT_DIR="$(mktemp -d "$(dirname "$DEB_FILE")/.deb-relocate.XXXXXX")"
cleanup() {
  rm -rf "$WORK_DIR"
  rm -rf "$OUTPUT_DIR"
}
trap cleanup EXIT

dpkg-deb --extract "$DEB_FILE" "$WORK_DIR"
dpkg-deb --control "$DEB_FILE" "$WORK_DIR/DEBIAN"

BIN_DIR="$WORK_DIR/usr/bin"
OPT_DIR="$WORK_DIR/opt/$PRODUCT_NAME"
mkdir -p "$BIN_DIR" "$OPT_DIR"

MOVED=0
while IFS= read -r -d '' BINARY; do
  BINARY_NAME="$(basename "$BINARY")"
  OPT_BINARY="$OPT_DIR/$BINARY_NAME"
  if [[ -e "$OPT_BINARY" ]]; then
    echo "目标文件重复，无法整理 deb：$OPT_BINARY" >&2
    exit 1
  fi
  mv "$BINARY" "$OPT_BINARY"
  ln -s "/opt/$PRODUCT_NAME/$BINARY_NAME" "$BIN_DIR/$BINARY_NAME"
  MOVED=$((MOVED + 1))
done < <(find "$BIN_DIR" -maxdepth 1 -type f -print0)

if [[ "$MOVED" -eq 0 ]]; then
  # 已整理过的包只有指向 /opt 的符号链接；保持重复执行安全。
  if find "$BIN_DIR" -maxdepth 1 -type l -print -quit | grep -q .; then
    echo "deb 已位于 /opt/$PRODUCT_NAME，跳过重复整理。"
    exit 0
  fi
  echo "deb 内未找到 /usr/bin 下的可执行文件：$DEB_FILE" >&2
  exit 1
fi

if [[ -d "$WORK_DIR/DEBIAN" ]]; then
  (cd "$WORK_DIR" && find opt usr -type f -print0 | sort -z | xargs -0 md5sum > DEBIAN/md5sums)
fi

REBUILT="$OUTPUT_DIR/$(basename "$DEB_FILE")"
dpkg-deb --build --root-owner-group "$WORK_DIR" "$REBUILT" >/dev/null
mv "$REBUILT" "$DEB_FILE"
echo "deb 安装目录已调整：/opt/$PRODUCT_NAME（/usr/bin 保留启动符号链接）"

#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE_NAME="${1:-iPhone 17}"
BUNDLE_ID="com.butvan.recordagent"
BUILD_DIR="/tmp/RecordAgentBuild"

# 1. 校验设备是否存在
UDID=$(xcrun simctl list devices available | grep -E "${DEVICE_NAME} \(" | head -n 1 | grep -E -o '[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}' || true)
if [ -z "${UDID}" ]; then
  echo "❌ 未找到可用模拟器: ${DEVICE_NAME}"
  echo "💡 可用的 iPhone 模拟器列表："
  xcrun simctl list devices available | grep "iPhone"
  exit 1
fi

echo "📱 [1/4] 启动 iOS 模拟器 (${DEVICE_NAME} - ${UDID})..."
open -a DeviceHub 2>/dev/null || open -a Simulator 2>/dev/null || true
xcrun simctl bootstatus "${UDID}" -b

echo "🔨 [2/4] 编译 RecordAgent 项目..."
xcodebuild build \
  -project "${PROJECT_DIR}/RecordAgent.xcodeproj" \
  -scheme RecordAgent \
  -destination "platform=iOS Simulator,id=${UDID}" \
  -derivedDataPath "${BUILD_DIR}" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  -quiet

APP_PATH="${BUILD_DIR}/Build/Products/Debug-iphonesimulator/RecordAgent.app"

echo "📦 [3/4] 安装 App 到模拟器..."
xcrun simctl install "${UDID}" "${APP_PATH}"

echo "🚀 [4/4] 唤起 App..."
xcrun simctl launch --terminate-running-process "${UDID}" "${BUNDLE_ID}"

echo "✅ 运行成功！"

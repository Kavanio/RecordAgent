#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE_NAME="${1:-iPhone 17}"
BUNDLE_ID="com.butvan.recordagent"
BUILD_DIR="/tmp/RecordAgentBuild"

echo "📱 [1/4] 启动 iOS 模拟器 (${DEVICE_NAME})..."
open -a Simulator
xcrun simctl boot "${DEVICE_NAME}" 2>/dev/null || true

echo "🔨 [2/4] 编译 RecordAgent 项目..."
xcodebuild build \
  -project "${PROJECT_DIR}/RecordAgent.xcodeproj" \
  -scheme RecordAgent \
  -destination "platform=iOS Simulator,name=${DEVICE_NAME}" \
  -derivedDataPath "${BUILD_DIR}" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  -quiet

APP_PATH="${BUILD_DIR}/Build/Products/Debug-iphonesimulator/RecordAgent.app"

echo "📦 [3/4] 安装 App 到模拟器..."
xcrun simctl install booted "${APP_PATH}"

echo "🚀 [4/4] 唤起 App..."
xcrun simctl launch booted "${BUNDLE_ID}"

echo "✅ 运行成功！"

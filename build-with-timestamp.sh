#!/usr/bin/env bash
#
# build-with-timestamp.sh
# 构建 AudioSuitZulu 的 Release APK，并复制到项目根目录（文件名带时间戳后缀）。
#
# 用法:
#   ./build-with-timestamp.sh
#
# 环境变量（可选）:
#   BUILD_JAVA_HOME  覆盖默认 JDK 路径。本工程 Gradle 8.7 + AGP 8.5.1 要求 JDK 17，
#                    默认使用 Android Studio 自带的 JBR 17。
#                    注意：不直接读取环境中的 JAVA_HOME，因为它可能指向旧版 JDK 8
#                    （如 shell profile 的全局设置），会导致 AGP 8.x 构建失败。

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_NAME="AudioSuitZulu"
GRADLEW="$PROJECT_DIR/gradlew"
APK_SRC="$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"

# 默认使用 JBR 17（仅通过专用变量 BUILD_JAVA_HOME 覆盖，忽略环境中的 JAVA_HOME）
DEFAULT_JAVA_HOME="/Users/workspace/Library/Java/JavaVirtualMachines/jbr-17.0.8.1/Contents/Home"
export JAVA_HOME="${BUILD_JAVA_HOME:-$DEFAULT_JAVA_HOME}"

if [ ! -x "$GRADLEW" ]; then
  echo "错误: 找不到可执行的 gradlew: $GRADLEW" >&2
  exit 1
fi
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "错误: JAVA_HOME 无效: $JAVA_HOME" >&2
  exit 1
fi

echo "== JAVA_HOME = $JAVA_HOME"
echo "== 开始构建 Release =="
cd "$PROJECT_DIR"
"$GRADLEW" :app:assembleRelease

# 构建成功后必须能找到唯一权威产物
if [ ! -f "$APK_SRC" ]; then
  echo "错误: 构建结束但未找到产物: $APK_SRC" >&2
  exit 1
fi

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
DEST="$PROJECT_DIR/${APP_NAME}-release-unsigned_${TIMESTAMP}.apk"
cp "$APK_SRC" "$DEST"

echo ""
echo "== 构建并归档完成 =="
echo "源产物: $APK_SRC"
echo "归档文件: $DEST"
ls -lh "$DEST" | awk '{print "大小: "$5}'

#!/bin/bash
# AndroidResAdapter 本机构建脚本
# 无需 Android Studio，直接编译出 APK

set -e

ANDROID_HOME="$HOME/android-sdk"
ANDROID_SDK_ROOT="$ANDROID_HOME"
JAVA_HOME="$ANDROID_HOME/jdk/jdk-17.0.9+9"
export ANDROID_HOME ANDROID_SDK_ROOT JAVA_HOME

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "================================"
echo "AndroidResAdapter APK 构建"
echo "================================"
echo "JAVA_HOME: $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"
echo ""

if [ ! -d "$JAVA_HOME" ]; then
    echo "错误: JDK 未安装"
    echo "请安装 JDK 17 到 $JAVA_HOME"
    exit 1
fi

if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
    echo "错误: Android SDK platform 34 未安装"
    exit 1
fi

echo "开始构建..."
./gradlew assembleDebug --no-daemon

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    cp "$APK" AndroidResAdapter-debug.apk
    echo ""
    echo "================================"
    echo "✓ 构建成功!"
    echo "================================"
    echo "APK: $SCRIPT_DIR/AndroidResAdapter-debug.apk"
    ls -lh "$SCRIPT_DIR/AndroidResAdapter-debug.apk"
else
    echo "构建失败"
    exit 1
fi

#!/usr/bin/env bash
# 本地打包参考命令（需要已安装 JDK17 + Android SDK）
set -e
cd "$(dirname "$0")"
echo "ANDROID_HOME=$ANDROID_HOME"
if [ -z "$ANDROID_HOME" ]; then
  echo "请先设置 ANDROID_HOME 指向 Android SDK 目录"
  exit 1
fi
gradle assembleDebug --no-daemon
ls -lh app/build/outputs/apk/debug/

#!/bin/bash
# ──────────────────────────────────────────────────────
# native-daemon/build.sh
# 交叉编译 screen-visiond 守护进程 (需要 Android NDK r26+)
# ──────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [ -z "$NDK_HOME" ]; then
    for dir in "$HOME/Library/Android/sdk/ndk" "$HOME/Android/Sdk/ndk" /usr/local/lib/android/sdk/ndk "$ANDROID_HOME/ndk" "$ANDROID_SDK_ROOT/ndk"; do
        if [ -d "$dir" ]; then
            NDK_HOME=$(ls -d "$dir"/*/ 2>/dev/null | sort -V | tail -1)
            [ -n "$NDK_HOME" ] && break
        fi
    done
fi

if [ -z "$NDK_HOME" ]; then
    echo "ERROR: Android NDK not found. Set ANDROID_NDK_HOME"
    exit 1
fi
echo "NDK: $NDK_HOME"

BUILD_DIR="$SCRIPT_DIR/build"
rm -rf "$BUILD_DIR" && mkdir -p "$BUILD_DIR"

cmake -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DCMAKE_BUILD_TYPE=Release \
    "$SCRIPT_DIR"

cmake --build "$BUILD_DIR" -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"

OUTPUT_DIR="$PROJECT_ROOT/android-app/app/src/main/res/raw"
mkdir -p "$OUTPUT_DIR"
cp "$BUILD_DIR/screen-visiond" "$OUTPUT_DIR/screen_visiond"
echo "Built: $OUTPUT_DIR/screen_visiond ($(du -h "$OUTPUT_DIR/screen_visiond" | cut -f1))"

if adb devices | grep -q "device$"; then
    adb push "$BUILD_DIR/screen-visiond" /data/local/tmp/
    adb shell chmod 755 /data/local/tmp/screen-visiond
    echo "Pushed to device."
    echo "Start: adb shell su -c /data/local/tmp/screen-visiond &"
fi
# Android.mk — NDK build for the screen-visiond daemon
# Build:
#   cd native-daemon && ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk
# Output: libs/arm64-v8a/screen-visiond

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE    := screen-visiond
LOCAL_SRC_FILES := main.c screencap.c socket_server.c frame_protocol.c control_protocol.c control_server.c input_inject.c
LOCAL_LDLIBS    := -llog -landroid -ldl
LOCAL_CFLAGS    := -Wall -Wextra -O2

include $(BUILD_EXECUTABLE)
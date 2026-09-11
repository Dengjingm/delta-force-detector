/*
 * screencap.c — SurfaceFlinger 截图实现
 *
 * 通过 Android SurfaceFlinger service 获取屏幕帧。
 * 需要在 root 权限下运行。使用 ScreenshotClient API
 * (Android 10+, 对应 libgui 中的 SurfaceFlinger 接口)。
 *
 * 编译目标: aarch64-linux-android (API 28+)
 */

#include "screencap.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

#define LOG_TAG "sv-screencap"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * 实现策略:
 *
 * 方案 A (推荐): 通过 dlopen 动态链接 libgui.so,
 * 调用 ScreenshotClient 来获取屏幕帧。
 *
 * 方案 B (备选): 如果 ScreenshotClient API 在目标 Android
 * 版本上不可用, 退化为调用 system("screencap -p /tmp/frame.png")
 * 并读取文件。速度较慢 (~80ms) 但兼容性更好。
 *
 * 默认使用方案 A, 编译时定义 USE_SCREENCAP_FALLBACK 切换方案 B。
 */

#ifdef USE_SCREENCAP_FALLBACK

/* ── 方案 B: 通过 screencap 命令 ────────────────────── */

static FrameBuffer s_fb = {0};
static uint8_t *s_buffer = NULL;
static int s_buffer_size = 0;

int screencap_init(void) {
    LOGI("Using screencap fallback");
    s_buffer_size = 1920 * 1080 * 4;  /* 足够容纳 1080p */
    s_buffer = (uint8_t *)malloc(s_buffer_size);
    if (!s_buffer) {
        LOGE("Failed to allocate buffer");
        return -1;
    }
    s_fb.pixels = s_buffer;
    s_fb.width = 0;
    s_fb.height = 0;
    s_fb.stride = 0;
    return 0;
}

const FrameBuffer *screencap_capture(void) {
    /* 使用 screencap 命令截屏到临时文件 */
    int ret = system("screencap -p /data/local/tmp/sv_frame.raw");
    if (ret != 0) {
        LOGE("screencap command failed: %d", ret);
        return NULL;
    }

    /* 读取 raw 文件前四个字节是宽高信息 */
    FILE *f = fopen("/data/local/tmp/sv_frame.raw", "rb");
    if (!f) {
        LOGE("Failed to open screencap output");
        return NULL;
    }

    /* screencap raw 格式: uint32_t width, uint32_t height, then RGBA pixels */
    uint32_t header[2];
    if (fread(header, sizeof(uint32_t), 2, f) != 2) {
        LOGE("Failed to read screencap header");
        fclose(f);
        return NULL;
    }

    s_fb.width = (int)header[0];
    s_fb.height = (int)header[1];
    s_fb.stride = s_fb.width * 4;

    int pixel_size = s_fb.width * s_fb.height * 4;
    if (pixel_size > s_buffer_size) {
        /* 重新分配更大的缓冲区 */
        s_buffer = (uint8_t *)realloc(s_buffer, pixel_size);
        s_buffer_size = pixel_size;
        s_fb.pixels = s_buffer;
    }

    size_t read_bytes = fread(s_fb.pixels, 1, pixel_size, f);
    fclose(f);

    if ((int)read_bytes != pixel_size) {
        LOGE("Incomplete pixel data: %zu/%d", read_bytes, pixel_size);
        return NULL;
    }

    LOGI("Captured frame: %dx%d (%d bytes)",
         s_fb.width, s_fb.height, pixel_size);
    return &s_fb;
}

void screencap_release(void) {
    free(s_buffer);
    s_buffer = NULL;
    s_fb.pixels = NULL;
    LOGI("Screencap released");
}

#else  /* !USE_SCREENCAP_FALLBACK */

/* ── 方案 A: ScreenshotClient API (需 root + libgui) ── */
/* 注意: 本代码需要在 Android 设备上用 NDK 编译并链接 libgui */

/* Typedef the ScreenshotClient we'll dynamically load */
typedef void* (*ScreenshotClient_create_t)();
typedef void  (*ScreenshotClient_destroy_t)(void*);
typedef int   (*ScreenshotClient_update_t)(void*, int* width, int* height, int* stride, int* format, int displayId);
typedef void* (*ScreenshotClient_getPixels_t)(void*);

static void *s_libgui = NULL;
static void *s_client = NULL;

static ScreenshotClient_create_t ScreenshotClient_create = NULL;
static ScreenshotClient_destroy_t ScreenshotClient_destroy = NULL;
static ScreenshotClient_update_t ScreenshotClient_update = NULL;
static ScreenshotClient_getPixels_t ScreenshotClient_getPixels = NULL;

static FrameBuffer s_fb = {0};

int screencap_init(void) {
    LOGI("Initializing ScreenshotClient via libgui");

    s_libgui = dlopen("libgui.so", RTLD_NOW | RTLD_LOCAL);
    if (!s_libgui) {
        LOGE("Failed to load libgui.so: %s", dlerror());
        goto fail;
    }

    /* 解析 ScreenshotClient 函数指针 */
    ScreenshotClient_create = (ScreenshotClient_create_t)dlsym(s_libgui, "_ZN16ScreenshotClientC1Ev");
    ScreenshotClient_destroy = (ScreenshotClient_destroy_t)dlsym(s_libgui, "_ZN16ScreenshotClientD1Ev");
    ScreenshotClient_update = (ScreenshotClient_update_t)dlsym(s_libgui, "_ZN16ScreenshotClient6updateEv");

    if (!ScreenshotClient_create || !ScreenshotClient_destroy || !ScreenshotClient_update) {
        LOGE("Failed to resolve ScreenshotClient symbols: %s", dlerror());
        goto fail;
    }

    s_client = ScreenshotClient_create();
    if (!s_client) {
        LOGE("Failed to create ScreenshotClient instance");
        goto fail;
    }

    LOGI("ScreenshotClient initialized successfully");
    return 0;

fail:
    if (s_libgui) { dlclose(s_libgui); s_libgui = NULL; }
    return -1;
}

const FrameBuffer *screencap_capture(void) {
    if (!s_client) {
        LOGE("ScreenshotClient not initialized");
        return NULL;
    }

    int status = ScreenshotClient_update(s_client, &s_fb.width, &s_fb.height,
                                          &s_fb.stride, NULL, 0);
    if (status != 0) {
        LOGE("ScreenshotClient update failed: %d", status);
        return NULL;
    }

    s_fb.pixels = (uint8_t*)ScreenshotClient_getPixels(s_client);
    if (!s_fb.pixels) {
        LOGE("ScreenshotClient getPixels returned NULL");
        return NULL;
    }

    return &s_fb;
}

void screencap_release(void) {
    if (s_client && ScreenshotClient_destroy) {
        ScreenshotClient_destroy(s_client);
        s_client = NULL;
    }
    if (s_libgui) {
        dlclose(s_libgui);
        s_libgui = NULL;
    }
    s_fb.pixels = NULL;
    LOGI("ScreenshotClient released");
}

#endif /* USE_SCREENCAP_FALLBACK */
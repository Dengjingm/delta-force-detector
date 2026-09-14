/*
 * screencap.c — 屏幕截图实现（root 调用 screencap 输出 raw 像素流）
 *
 * 通过 root 权限调用 /system/bin/screencap（不带 -p、不带文件名），
 * 其向 stdout 输出 raw 像素流:
 *   [uint32 width][uint32 height][uint32 format][raw pixels]
 * 读取 12 字节头后按 format 确定每像素字节数，读取像素并统一转换为
 * 紧密排列的 RGBA8888，供上层按 width*height*4 消费。
 *
 * 编译目标: aarch64-linux-android (API 28+)
 */

#include "screencap.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "sv-screencap"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Android HAL_PIXEL_FORMAT 常量 (system/core/include/system/graphics.h) */
#define HAL_PIXEL_FORMAT_RGBA_8888   1
#define HAL_PIXEL_FORMAT_RGBX_8888   2
#define HAL_PIXEL_FORMAT_RGB_888     3
#define HAL_PIXEL_FORMAT_RGB_565     4
#define HAL_PIXEL_FORMAT_BGRA_8888   5

#define SCREENCAP_BIN  "/system/bin/screencap"
#define HEADER_WORDS   3   /* width, height, format */
#define RGBA_BPP       4

static FrameBuffer s_fb = {0};
static uint8_t *s_raw = NULL;    /* 原始像素缓冲 */
static size_t s_raw_size = 0;
static uint8_t *s_rgba = NULL;   /* 转换后的 RGBA8888 缓冲 */
static size_t s_rgba_size = 0;

int screencap_init(void) {
    LOGI("screencap backend: raw screencap via popen");
    return 0;
}

static int format_bpp(uint32_t format) {
    switch (format) {
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
        case HAL_PIXEL_FORMAT_BGRA_8888:
            return 4;
        case HAL_PIXEL_FORMAT_RGB_888:
            return 3;
        case HAL_PIXEL_FORMAT_RGB_565:
            return 2;
        default:
            return -1;
    }
}

/* 将 raw 像素转换为紧密 RGBA8888；src 每行按 width*bpp 紧密排列。 */
static void convert_to_rgba(const uint8_t *src, uint8_t *dst,
                            int width, int height, uint32_t format, int bpp) {
    int n = width * height;
    for (int i = 0; i < n; i++) {
        const uint8_t *p = src + (size_t)i * bpp;
        uint8_t *q = dst + (size_t)i * RGBA_BPP;
        switch (format) {
            case HAL_PIXEL_FORMAT_RGBA_8888:
                q[0] = p[0]; q[1] = p[1]; q[2] = p[2]; q[3] = p[3];
                break;
            case HAL_PIXEL_FORMAT_BGRA_8888:
                q[0] = p[2]; q[1] = p[1]; q[2] = p[0]; q[3] = p[3];
                break;
            case HAL_PIXEL_FORMAT_RGBX_8888:
                q[0] = p[0]; q[1] = p[1]; q[2] = p[2]; q[3] = 255;
                break;
            case HAL_PIXEL_FORMAT_RGB_888:
                q[0] = p[0]; q[1] = p[1]; q[2] = p[2]; q[3] = 255;
                break;
            case HAL_PIXEL_FORMAT_RGB_565: {
                /* 小端 uint16: [rrrrrggg][gggbbbbb] */
                uint16_t v = (uint16_t)((p[1] << 8) | p[0]);
                q[0] = (uint8_t)(((v >> 11) & 0x1F) * 255 / 31);
                q[1] = (uint8_t)(((v >> 5) & 0x3F) * 255 / 63);
                q[2] = (uint8_t)((v & 0x1F) * 255 / 31);
                q[3] = 255;
                break;
            }
            default:
                break;
        }
    }
}

const FrameBuffer *screencap_capture(void) {
    FILE *pipe = popen(SCREENCAP_BIN, "rb");
    if (!pipe) {
        LOGE("popen screencap failed");
        return NULL;
    }

    uint32_t header[HEADER_WORDS];
    if (fread(header, sizeof(uint32_t), HEADER_WORDS, pipe) != HEADER_WORDS) {
        LOGE("failed to read screencap header");
        pclose(pipe);
        return NULL;
    }

    int width = (int)header[0];
    int height = (int)header[1];
    uint32_t format = header[2];

    int bpp = format_bpp(format);
    if (bpp < 0) {
        LOGE("unsupported pixel format: %u", format);
        pclose(pipe);
        return NULL;
    }
    if (width <= 0 || height <= 0) {
        LOGE("invalid dimensions: %dx%d", width, height);
        pclose(pipe);
        return NULL;
    }

    size_t raw_size = (size_t)width * (size_t)height * (size_t)bpp;
    if (raw_size > s_raw_size) {
        uint8_t *nb = (uint8_t *)realloc(s_raw, raw_size);
        if (!nb) {
            LOGE("realloc raw buffer failed (%zu bytes)", raw_size);
            pclose(pipe);
            return NULL;
        }
        s_raw = nb;
        s_raw_size = raw_size;
    }

    size_t got = fread(s_raw, 1, raw_size, pipe);
    pclose(pipe);
    if (got != raw_size) {
        LOGE("incomplete pixel data: %zu/%zu", got, raw_size);
        return NULL;
    }

    size_t rgba_size = (size_t)width * (size_t)height * RGBA_BPP;
    if (rgba_size > s_rgba_size) {
        uint8_t *nb = (uint8_t *)realloc(s_rgba, rgba_size);
        if (!nb) {
            LOGE("realloc rgba buffer failed (%zu bytes)", rgba_size);
            return NULL;
        }
        s_rgba = nb;
        s_rgba_size = rgba_size;
    }

    convert_to_rgba(s_raw, s_rgba, width, height, format, bpp);

    s_fb.pixels = s_rgba;
    s_fb.width = width;
    s_fb.height = height;
    s_fb.stride = width * RGBA_BPP;

    return &s_fb;
}

void screencap_release(void) {
    free(s_raw);
    s_raw = NULL;
    s_raw_size = 0;
    free(s_rgba);
    s_rgba = NULL;
    s_rgba_size = 0;
    s_fb.pixels = NULL;
    LOGI("screencap released");
}

/*
 * screen-visiond — Screen Vision Native Daemon
 *
 * Root 守护进程, 负责:
 * 1. 通过 SurfaceFlinger 以 ~30fps 截取屏幕
 * 2. 通过 Unix Socket 将帧推送到 Java 层
 *
 * 启动方式 (root):
 *   su -c /data/local/tmp/screen-visiond &
 *
 * 停止:
 *   killall screen-visiond && rm -f /data/local/tmp/screen-vision.sock
 */

#include "screencap.h"
#include "socket_server.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <time.h>

#define LOG_TAG "screen-visiond"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SOCKET_PATH  "/data/local/tmp/screen-vision.sock"
#define TARGET_FPS   30
#define FRAME_NS    (1000000000LL / TARGET_FPS)  /* ~33ms per frame */

static volatile int g_running = 1;

static void handle_signal(int sig) {
    (void)sig;
    g_running = 0;
}

/* 忙等待直到下一帧的 deadline */
static void wait_for_next_frame(struct timespec *last_frame) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);

    long long elapsed = (now.tv_sec - last_frame->tv_sec) * 1000000000LL
                      + (now.tv_nsec - last_frame->tv_nsec);

    if (elapsed < FRAME_NS) {
        long long sleep_ns = FRAME_NS - elapsed;
        struct timespec ts = {
            .tv_sec = 0,
            .tv_nsec = sleep_ns,
        };
        nanosleep(&ts, NULL);
    }

    clock_gettime(CLOCK_MONOTONIC, last_frame);
}

int main(void) {
    LOGI("screen-visiond daemon starting...");

    /* ── 信号处理: 优雅退出 ─────────────────────── */
    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);

    /* ── 初始化截图模块 ─────────────────────────── */
    if (screencap_init() != 0) {
        LOGE("Failed to initialize screencap");
        return 1;
    }
    LOGI("Screencap initialized");

    /* ── 初始化 Socket Server ───────────────────── */
    int server_fd = socket_server_init(SOCKET_PATH);
    if (server_fd < 0) {
        LOGE("Failed to create socket server");
        screencap_release();
        return 1;
    }
    LOGI("Socket server listening");

    /* ── 等待客户端连接 ─────────────────────────── */
    LOGI("Waiting for Android app to connect...");
    int client_fd = socket_server_accept(server_fd);
    if (client_fd < 0) {
        LOGE("No client connected");
        goto cleanup;
    }
    LOGI("Client connected, starting capture loop");

    /* ── 主循环: 截屏 → 发送 ────────────────────── */
    struct timespec last_frame;
    clock_gettime(CLOCK_MONOTONIC, &last_frame);

    int frame_count = 0;
    long long total_capture_us = 0;
    long long total_send_us = 0;

    while (g_running) {
        /* 截屏 (~8ms) */
        struct timespec t1, t2;
        clock_gettime(CLOCK_MONOTONIC, &t1);

        const FrameBuffer *fb = screencap_capture();
        if (!fb) {
            LOGE("Frame capture failed, retrying...");
            wait_for_next_frame(&last_frame);
            continue;
        }

        clock_gettime(CLOCK_MONOTONIC, &t2);
        long long capture_us = (t2.tv_sec - t1.tv_sec) * 1000000LL
                             + (t2.tv_nsec - t1.tv_nsec) / 1000;

        /* 发送帧到客户端 (~2ms) */
        if (socket_server_send_frame(client_fd, fb) != 0) {
            LOGE("Send failed, client disconnected? Reconnecting...");
            socket_server_close(client_fd);
            client_fd = socket_server_accept(server_fd);
            if (client_fd < 0) {
                LOGE("Reconnect failed, exiting");
                break;
            }
            LOGI("Client reconnected");
        }

        clock_gettime(CLOCK_MONOTONIC, &t1);
        long long send_us = (t1.tv_sec - t2.tv_sec) * 1000000LL
                          + (t1.tv_nsec - t2.tv_nsec) / 1000;

        /* 帧率统计 (每 300 帧打印一次) */
        frame_count++;
        total_capture_us += capture_us;
        total_send_us += send_us;

        if (frame_count % 300 == 0) {
            LOGI("[STATS] %d frames | capture avg: %lldus | send avg: %lldus | fps: %d",
                 frame_count,
                 total_capture_us / frame_count,
                 total_send_us / frame_count,
                 frame_count * 1000000 / (total_capture_us + total_send_us));
        }

        /* 等待保持 30fps */
        wait_for_next_frame(&last_frame);
    }

    LOGI("Shutting down (frames sent: %d)", frame_count);

cleanup:
    socket_server_close(client_fd);
    socket_server_close(server_fd);
    unlink(SOCKET_PATH);
    screencap_release();
    LOGI("screen-visiond daemon exited cleanly");
    return 0;
}
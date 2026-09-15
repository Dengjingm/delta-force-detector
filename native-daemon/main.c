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
#include "control_server.h"
#include "control_protocol.h"
#include "input_inject.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <time.h>
#include <pthread.h>
#include <poll.h>
#include <errno.h>
#include <sys/socket.h>

#define LOG_TAG "screen-visiond"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SOCKET_PATH      "/data/local/tmp/screen-vision.sock"
#define CTRL_SOCKET_PATH "/data/local/tmp/screen-vision-ctrl.sock"
#define TARGET_FPS   30
#define FRAME_NS    (1000000000LL / TARGET_FPS)  /* ~33ms per frame */

static volatile int g_running = 1;
static int g_ctrl_server_fd = -1;
static pthread_t g_ctrl_thread;
static int g_ctrl_thread_started = 0;

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

/* 当前 BOOTTIME 纳秒。C01 时间戳要求 CLOCK_BOOTTIME(含挂起时间)。 */
static uint64_t now_boottime_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

/* 每次 accept 生成非零 stream_id; 仅用于连接内等值比较, 不要求密码学随机。 */
static uint64_t generate_stream_id(void) {
    uint64_t v = now_boottime_ns() ^ ((uint64_t)getpid() << 32);
    return v ? v : 1;
}

/* 控制命令 → input motionevent 动作映射。 */
static void dispatch_control(const control_cmd_t *cmd) {
    const char *action;
    switch (cmd->type) {
    case SVC2_CTRL_ACQUIRE: action = "DOWN"; break;
    case SVC2_CTRL_MOVE:    action = "MOVE"; break;
    case SVC2_CTRL_RELEASE: action = "UP";   break;
    default: return;
    }
    input_inject_motionevent(action, cmd->x, cmd->y);
}

/*
 * 控制线程: 接受 App 控制连接, 顺序读命令并同步注入。
 * 用 poll(≤200ms) 检查 g_running, 避免永久阻塞在 accept/read (C01)。
 */
static void *control_thread_main(void *arg) {
    (void)arg;

    while (g_running) {
        struct pollfd pfd = { .fd = g_ctrl_server_fd, .events = POLLIN };
        int pr = poll(&pfd, 1, 200);
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pr == 0) continue; /* 超时, 重新检查 g_running */
        if (!(pfd.revents & POLLIN)) continue;

        int cfd = accept(g_ctrl_server_fd, NULL, NULL);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            break;
        }

        while (g_running) {
            struct pollfd cp = { .fd = cfd, .events = POLLIN };
            int cr = poll(&cp, 1, 200);
            if (cr < 0) {
                if (errno == EINTR) continue;
                break;
            }
            if (cr == 0) continue;
            if (cp.revents & (POLLHUP | POLLERR | POLLNVAL)) break;
            if (!(cp.revents & POLLIN)) continue;

            control_cmd_t cmd;
            if (control_server_read_command(cfd, &cmd) != 0) break;
            dispatch_control(&cmd);
        }

        control_server_close(cfd);
    }

    return NULL;
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
    (void)input_inject_init();

    /* ── 初始化 Socket Server ───────────────────── */
    int server_fd = socket_server_init(SOCKET_PATH);
    if (server_fd < 0) {
        LOGE("Failed to create socket server");
        input_inject_release();
        screencap_release();
        return 1;
    }
    LOGI("Socket server listening");

    /* ── 初始化反向控制通道 (App→daemon) ─────────── */
    g_ctrl_server_fd = control_server_init(CTRL_SOCKET_PATH);
    if (g_ctrl_server_fd < 0) {
        LOGE("Failed to create control socket (aim injection disabled)");
    } else if (pthread_create(&g_ctrl_thread, NULL, control_thread_main, NULL) != 0) {
        LOGE("Failed to start control thread (aim injection disabled)");
        control_server_close(g_ctrl_server_fd);
        g_ctrl_server_fd = -1;
    } else {
        g_ctrl_thread_started = 1;
        LOGI("Control channel listening on %s", CTRL_SOCKET_PATH);
    }

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

    uint64_t frame_id = 0;
    uint64_t stream_id = generate_stream_id();

    while (g_running) {
        /* 截屏, 记录 BOOTTIME 时间戳用于帧头 */
        uint64_t capture_start_ns = now_boottime_ns();
        const FrameBuffer *fb = screencap_capture();
        if (!fb) {
            LOGE("Frame capture failed, retrying...");
            wait_for_next_frame(&last_frame);
            continue;
        }
        uint64_t capture_end_ns = now_boottime_ns();
        frame_id++;

        /* 发送帧到客户端 */
        uint64_t send_start_ns = now_boottime_ns();
        int send_rc = socket_server_send_frame(client_fd, fb, frame_id,
                                               capture_start_ns, capture_end_ns,
                                               stream_id);
        uint64_t send_end_ns = now_boottime_ns();

        long long capture_us = (long long)((capture_end_ns - capture_start_ns) / 1000);
        long long send_us = (long long)((send_end_ns - send_start_ns) / 1000);

        if (send_rc != 0) {
            LOGE("Send failed, client disconnected? Reconnecting...");
            socket_server_close(client_fd);
            client_fd = socket_server_accept(server_fd);
            if (client_fd < 0) {
                LOGE("Reconnect failed, exiting");
                break;
            }
            stream_id = generate_stream_id();
            LOGI("Client reconnected");
        }

        /* 帧率统计 (每 300 帧打印一次) */
        frame_count++;
        total_capture_us += capture_us;
        total_send_us += send_us;

        if (frame_count % 300 == 0) {
            long long fps = (long long)frame_count * 1000000LL
                          / (total_capture_us + total_send_us);
            LOGI("[STATS] %d frames | capture avg: %lldus | send avg: %lldus | fps: %lld",
                 frame_count,
                 total_capture_us / frame_count,
                 total_send_us / frame_count,
                 fps);
        }

        /* 等待保持 30fps */
        wait_for_next_frame(&last_frame);
    }

    LOGI("Shutting down (frames sent: %d)", frame_count);

cleanup:
    socket_server_close(client_fd);
    socket_server_close(server_fd);
    unlink(SOCKET_PATH);

    /* 停止控制线程并回收: 置 g_running=0 让其 200ms 内退出, 再 join。 */
    if (g_ctrl_thread_started) {
        g_running = 0;
        pthread_join(g_ctrl_thread, NULL);
        g_ctrl_thread_started = 0;
    }
    control_server_close(g_ctrl_server_fd);
    unlink(CTRL_SOCKET_PATH);

    input_inject_release();
    screencap_release();
    LOGI("screen-visiond daemon exited cleanly");
    return 0;
}
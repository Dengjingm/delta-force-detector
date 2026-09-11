/*
 * socket_server.c — Unix Socket Server for frame delivery
 *
 * 创建一个 Unix domain socket (SOCK_STREAM), 监听客户端连接。
 * 收到帧后通过 sendmsg 零拷贝发送到 Java 层。
 *
 * 协议:
 *   客户端连接后, 服务端每帧发送:
 *   [4 bytes: width][4 bytes: height][width*height*4 bytes: RGBA pixels]
 *
 *   客户端可以发送 "PING" 保活, 服务端回复 "PONG"。
 */

#include "socket_server.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/uio.h>
#include <errno.h>

#define LOG_TAG "sv-socket"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

int socket_server_init(const char *path) {
    /* 移除已存在的 socket 文件 */
    unlink(path);

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("bind() failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    /* 设置权限使 Java 进程可访问 */
    chmod(path, 0777);

    if (listen(fd, 1) < 0) {
        LOGE("listen() failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    LOGI("Socket server listening on %s", path);
    return fd;
}

int socket_server_accept(int server_fd) {
    struct sockaddr_un client_addr;
    socklen_t addr_len = sizeof(client_addr);

    int client_fd = accept(server_fd,
                           (struct sockaddr *)&client_addr,
                           &addr_len);
    if (client_fd < 0) {
        LOGE("accept() failed: %s", strerror(errno));
        return -1;
    }

    LOGI("Client connected (fd=%d)", client_fd);
    return client_fd;
}

int socket_server_send_frame(int client_fd, const FrameBuffer *fb) {
    if (client_fd < 0 || !fb || !fb->pixels) {
        return -1;
    }

    /* 构造头部: width (4 bytes) + height (4 bytes) */
    uint32_t header[2];
    header[0] = (uint32_t)fb->width;
    header[1] = (uint32_t)fb->height;

    size_t pixel_size = (size_t)fb->width * fb->height * 4;
    size_t total_size = sizeof(header) + pixel_size;

    /* 使用 writev 一次调用发送头部和数据 */
    struct iovec iov[2];
    iov[0].iov_base = header;
    iov[0].iov_len = sizeof(header);
    iov[1].iov_base = fb->pixels;
    iov[1].iov_len = pixel_size;

    ssize_t sent = writev(client_fd, iov, 2);
    if (sent < 0) {
        LOGE("writev() failed: %s", strerror(errno));
        return -1;
    }

    if ((size_t)sent != total_size) {
        LOGE("Partial write: %zu/%zu bytes", sent, total_size);
        return -1;
    }

    return 0;
}

void socket_server_close(int fd) {
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
}
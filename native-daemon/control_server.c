#include "control_server.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <errno.h>

#define LOG_TAG "sv-ctrl"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

int control_server_init(const char *path) {
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

    chmod(path, 0777);

    if (listen(fd, 1) < 0) {
        LOGE("listen() failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    LOGI("Control socket listening on %s", path);
    return fd;
}

int control_server_accept(int server_fd) {
    struct sockaddr_un client_addr;
    socklen_t addr_len = sizeof(client_addr);

    int client_fd = accept(server_fd, (struct sockaddr *)&client_addr, &addr_len);
    if (client_fd < 0) {
        LOGE("accept() failed: %s", strerror(errno));
        return -1;
    }

    LOGI("Control client connected (fd=%d)", client_fd);
    return client_fd;
}

static int read_fully(int fd, uint8_t *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = read(fd, buf + off, len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1; /* EOF */
        off += (size_t)n;
    }
    return 0;
}

static uint32_t le_u32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

int control_server_read_command(int client_fd, control_cmd_t *cmd) {
    uint8_t msg[SVC2_CTRL_MAX_MESSAGE];

    if (read_fully(client_fd, msg, SVC2_CTRL_HEADER_BYTES) != 0) {
        return -1;
    }

    uint32_t payload_len = le_u32(msg + 8);
    if (payload_len > SVC2_CTRL_MAX_PAYLOAD) {
        LOGE("Oversized control payload: %u", payload_len);
        return -1;
    }

    if (read_fully(client_fd, msg + SVC2_CTRL_HEADER_BYTES, payload_len) != 0) {
        return -1;
    }

    control_error_t err =
        control_cmd_decode(msg, SVC2_CTRL_HEADER_BYTES + payload_len, cmd);
    if (err != CTRL_OK) {
        LOGE("Control decode failed: %s", control_error_str(err));
        return -1;
    }
    return 0;
}

void control_server_close(int fd) {
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
}

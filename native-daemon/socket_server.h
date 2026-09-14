#ifndef SOCKET_SERVER_H
#define SOCKET_SERVER_H

#include <stdint.h>

#include "screencap.h"

/*
 * 初始化 Unix Socket Server
 * path: socket 文件路径, 如 "/data/local/tmp/screen-vision.sock"
 * 返回文件描述符, -1 失败
 */
int socket_server_init(const char *path);

/*
 * 等待客户端连接
 * server_fd: socket_server_init 返回的描述符
 * 返回客户端连接 fd, -1 失败
 */
int socket_server_accept(int server_fd);

/*
 * 发送一帧到客户端
 * 格式: [64-byte v2 header (LE)][RGBA pixels:width*height*4]
 * frame_id / capture_*_ns / stream_id 由调用方(采集循环)提供, 见 CONTRACTS C01。
 * 返回 0 成功, -1 失败
 */
int socket_server_send_frame(int client_fd, const FrameBuffer *fb,
                             uint64_t frame_id, uint64_t capture_start_ns,
                             uint64_t capture_end_ns, uint64_t stream_id);

/*
 * 关闭连接
 */
void socket_server_close(int fd);

#endif /* SOCKET_SERVER_H */
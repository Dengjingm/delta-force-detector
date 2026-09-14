#ifndef SV_CONTROL_SERVER_H
#define SV_CONTROL_SERVER_H

#include "control_protocol.h"

/*
 * App -> daemon 控制 socket (AF_UNIX / SOCK_STREAM)。
 * 与数据 socket 分离, 只承载反向控制命令。
 */

/* 初始化并监听控制 socket, 返回 server fd, -1 失败。 */
int control_server_init(const char *path);

/* 阻塞等待控制客户端连接, 返回 client fd, -1 失败。 */
int control_server_accept(int server_fd);

/*
 * 从 client_fd 分帧阻塞读取一条完整命令 (头+负载), 短读续读。
 * 返回 0 成功, -1 错误/EOF/协议非法。
 */
int control_server_read_command(int client_fd, control_cmd_t *cmd);

/* 关闭连接 (shutdown + close)。 */
void control_server_close(int fd);

#endif /* SV_CONTROL_SERVER_H */

#ifndef SV_CONTROL_PROTOCOL_H
#define SV_CONTROL_PROTOCOL_H

/*
 * App -> daemon 反向控制协议 (v1)。
 *
 * 独立的 AF_UNIX 控制 socket (C01 禁止在像素流中混入控制文本)。
 * 每条命令: [magic "SVC2"][version u16=1][type u16][payloadLen u32][payload]。
 * 全部 little-endian, 手工逐字段编解码 (禁止裸 C struct 落盘)。
 *
 * type: 1=ACQUIRE(按下), 2=MOVE(移动), 3=RELEASE(抬起)。
 * 三类 payload 均为 i32 x, i32 y (绝对显示坐标), 与 `input motionevent` 参数一致。
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SVC2_CTRL_HEADER_BYTES 12u
#define SVC2_CTRL_VERSION 1u
#define SVC2_CTRL_MAX_PAYLOAD 8u
#define SVC2_CTRL_MAX_MESSAGE (SVC2_CTRL_HEADER_BYTES + SVC2_CTRL_MAX_PAYLOAD)

typedef enum {
    SVC2_CTRL_ACQUIRE = 1,
    SVC2_CTRL_MOVE = 2,
    SVC2_CTRL_RELEASE = 3,
} control_cmd_type_t;

typedef struct {
    uint32_t type; /* control_cmd_type_t */
    int32_t x;
    int32_t y;
} control_cmd_t;

typedef enum {
    CTRL_OK = 0,
    CTRL_ERR_MAGIC = -1,
    CTRL_ERR_VERSION = -2,
    CTRL_ERR_TYPE = -3,
    CTRL_ERR_LEN = -4,
    CTRL_ERR_NULL = -5,
    CTRL_ERR_INTERNAL = -6,
} control_error_t;

/*
 * 编码 cmd 到 out (容量 out_cap), 实际写入长度经 out_len 返回。
 * 三类均写 8 字节 i32 x,y payload。
 */
control_error_t control_cmd_encode(const control_cmd_t *cmd,
                                   uint8_t *out, size_t out_cap,
                                   size_t *out_len);

/*
 * 解码 in[0..in_len)。拒绝坏 magic/version/type 及 payloadLen 与类型不符。
 * 解码前不信任字段, 越界视为 CTRL_ERR_LEN。
 */
control_error_t control_cmd_decode(const uint8_t *in, size_t in_len,
                                   control_cmd_t *cmd);

/* 人类可读错误信息, 永不 NULL。 */
const char *control_error_str(control_error_t err);

#ifdef __cplusplus
}
#endif

#endif /* SV_CONTROL_PROTOCOL_H */

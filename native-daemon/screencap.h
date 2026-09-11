#ifndef SCREENCAP_H
#define SCREENCAP_H

#include <stdint.h>

/* 帧信息结构体 */
typedef struct {
    uint8_t *pixels;   /* RGBA 像素数据 */
    int width;
    int height;
    int stride;        /* 每行字节数 */
} FrameBuffer;

/*
 * 初始化 SurfaceFlinger 截图会话
 * 需要在有 root 权限的进程中调用
 * 返回 0 成功, -1 失败
 */
int screencap_init(void);

/*
 * 截取一帧屏幕
 * 返回的 FrameBuffer 在下次调用前有效
 * 返回 NULL 表示失败
 */
const FrameBuffer *screencap_capture(void);

/*
 * 释放资源
 */
void screencap_release(void);

#endif /* HOK_SCREENCAP_H */
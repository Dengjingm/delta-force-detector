#ifndef SV_INPUT_INJECT_H
#define SV_INPUT_INJECT_H

#include <stdint.h>

/*
 * 注入一次 `input motionevent <action> <x> <y>` 并等待其完成。
 * action: "DOWN" / "MOVE" / "UP"。返回 0 成功, -1 失败。
 *
 * 由 daemon 以 root 直接 exec `input` 二进制 (不再经 su/sh), 消除每事件
 * 的多层进程 spawn。顺序同步注入以保持手势顺序并形成天然背压。
 */
int input_inject_motionevent(const char *action, int32_t x, int32_t y);

#endif /* SV_INPUT_INJECT_H */

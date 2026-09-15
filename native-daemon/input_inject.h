#ifndef SV_INPUT_INJECT_H
#define SV_INPUT_INJECT_H

#include <stdint.h>

/*
 * 优先在 /dev/uinput 上创建虚拟触控并写入 DOWN/MOVE/UP。
 * 打开或写入失败时回退为 exec `/system/bin/input motionevent`。
 * 返回 0 成功, -1 失败。
 */
int input_inject_init(void);
void input_inject_release(void);
int input_inject_motionevent(const char *action, int32_t x, int32_t y);

#endif /* SV_INPUT_INJECT_H */

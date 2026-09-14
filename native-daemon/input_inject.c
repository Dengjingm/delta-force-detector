#include "input_inject.h"

#include <stdio.h>
#include <unistd.h>
#include <sys/wait.h>
#include <errno.h>
#include <string.h>

#define LOG_TAG "sv-inject"
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

int input_inject_motionevent(const char *action, int32_t x, int32_t y) {
    char xbuf[16];
    char ybuf[16];
    snprintf(xbuf, sizeof(xbuf), "%d", (int)x);
    snprintf(ybuf, sizeof(ybuf), "%d", (int)y);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        return -1;
    }
    if (pid == 0) {
        /* 子进程: 仅调用 async-signal-safe 的 execl/_exit。 */
        execl("/system/bin/input", "input", "motionevent",
              action, xbuf, ybuf, (char *)NULL);
        _exit(127);
    }

    int status = 0;
    if (waitpid(pid, &status, 0) < 0) {
        LOGE("waitpid() failed");
        return -1;
    }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        return 0;
    }
    LOGE("input motionevent %s (%d,%d) failed (status=0x%x)",
         action, x, y, status);
    return -1;
}

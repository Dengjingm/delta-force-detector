#include "input_inject.h"

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>

#include <linux/input.h>
#include <linux/uinput.h>

#define LOG_TAG "sv-inject"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int g_uinput_fd = -1;
static int g_use_uinput = 0;
static int32_t g_width = 3200;
static int32_t g_height = 1440;
static int g_holding = 0;
static int g_tracking_id = 1;

static int emit(int fd, uint16_t type, uint16_t code, int32_t value)
{
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) {
        return -1;
    }
    return 0;
}

static int32_t clamp_i32(int32_t v, int32_t lo, int32_t hi)
{
    if (v < lo) {
        return lo;
    }
    if (v > hi) {
        return hi;
    }
    return v;
}

static int probe_display_size(int32_t *w, int32_t *h)
{
    FILE *f = fopen("/sys/class/graphics/fb0/virtual_size", "r");
    if (f == NULL) {
        return -1;
    }
    int rw = 0;
    int rh = 0;
    int n = fscanf(f, "%d,%d", &rw, &rh);
    fclose(f);
    if (n != 2 || rw < 100 || rh < 100 || rw > 8192 || rh > 8192) {
        return -1;
    }
    *w = rw;
    *h = rh;
    return 0;
}

static void set_abs(struct uinput_user_dev *dev, int code, int32_t max_v)
{
    dev->absmin[code] = 0;
    dev->absmax[code] = max_v;
    dev->absfuzz[code] = 0;
    dev->absflat[code] = 0;
}

int input_inject_init(void)
{
    if (probe_display_size(&g_width, &g_height) == 0) {
        LOGI("display size %dx%d", (int)g_width, (int)g_height);
    } else {
        LOGI("display size probe failed, default %dx%d", (int)g_width, (int)g_height);
    }

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    }
    if (fd < 0) {
        LOGI("uinput unavailable (%s), fallback to /system/bin/input", strerror(errno));
        g_use_uinput = 0;
        return 0;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER) < 0) {
        LOGE("uinput evbit failed: %s", strerror(errno));
        close(fd);
        g_use_uinput = 0;
        return 0;
    }
#ifdef INPUT_PROP_DIRECT
    (void)ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
#endif

    const int abs_codes[] = {
        ABS_X,
        ABS_Y,
        ABS_MT_SLOT,
        ABS_MT_TRACKING_ID,
        ABS_MT_POSITION_X,
        ABS_MT_POSITION_Y,
        ABS_MT_PRESSURE,
    };
    for (size_t i = 0; i < sizeof(abs_codes) / sizeof(abs_codes[0]); i++) {
        if (ioctl(fd, UI_SET_ABSBIT, abs_codes[i]) < 0) {
            LOGE("UI_SET_ABSBIT %d failed: %s", abs_codes[i], strerror(errno));
            close(fd);
            g_use_uinput = 0;
            return 0;
        }
    }

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "sv-touch");
    uidev.id.bustype = BUS_USB;
    uidev.id.vendor = 0x0001;
    uidev.id.product = 0x0001;
    uidev.id.version = 1;
    set_abs(&uidev, ABS_X, g_width - 1);
    set_abs(&uidev, ABS_Y, g_height - 1);
    set_abs(&uidev, ABS_MT_SLOT, 1);
    set_abs(&uidev, ABS_MT_TRACKING_ID, 65535);
    set_abs(&uidev, ABS_MT_POSITION_X, g_width - 1);
    set_abs(&uidev, ABS_MT_POSITION_Y, g_height - 1);
    set_abs(&uidev, ABS_MT_PRESSURE, 255);

    if (write(fd, &uidev, sizeof(uidev)) != (ssize_t)sizeof(uidev)) {
        LOGE("uinput_user_dev write failed: %s", strerror(errno));
        close(fd);
        g_use_uinput = 0;
        return 0;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        g_use_uinput = 0;
        return 0;
    }

    g_uinput_fd = fd;
    g_use_uinput = 1;
    g_holding = 0;
    LOGI("uinput device created");
    return 0;
}

void input_inject_release(void)
{
    if (g_uinput_fd >= 0) {
        (void)ioctl(g_uinput_fd, UI_DEV_DESTROY);
        close(g_uinput_fd);
        g_uinput_fd = -1;
    }
    g_use_uinput = 0;
    g_holding = 0;
}

static int uinput_down(int32_t x, int32_t y)
{
    int fd = g_uinput_fd;
    x = clamp_i32(x, 0, g_width - 1);
    y = clamp_i32(y, 0, g_height - 1);
    int id = g_tracking_id++;
    if (g_tracking_id <= 0) {
        g_tracking_id = 1;
    }
    if (emit(fd, EV_ABS, ABS_MT_SLOT, 0) < 0 ||
        emit(fd, EV_ABS, ABS_MT_TRACKING_ID, id) < 0 ||
        emit(fd, EV_ABS, ABS_MT_POSITION_X, x) < 0 ||
        emit(fd, EV_ABS, ABS_MT_POSITION_Y, y) < 0 ||
        emit(fd, EV_ABS, ABS_MT_PRESSURE, 50) < 0 ||
        emit(fd, EV_ABS, ABS_X, x) < 0 ||
        emit(fd, EV_ABS, ABS_Y, y) < 0 ||
        emit(fd, EV_KEY, BTN_TOUCH, 1) < 0 ||
        emit(fd, EV_KEY, BTN_TOOL_FINGER, 1) < 0 ||
        emit(fd, EV_SYN, SYN_REPORT, 0) < 0) {
        return -1;
    }
    g_holding = 1;
    return 0;
}

static int uinput_move(int32_t x, int32_t y)
{
    if (!g_holding) {
        return uinput_down(x, y);
    }
    int fd = g_uinput_fd;
    x = clamp_i32(x, 0, g_width - 1);
    y = clamp_i32(y, 0, g_height - 1);
    if (emit(fd, EV_ABS, ABS_MT_SLOT, 0) < 0 ||
        emit(fd, EV_ABS, ABS_MT_POSITION_X, x) < 0 ||
        emit(fd, EV_ABS, ABS_MT_POSITION_Y, y) < 0 ||
        emit(fd, EV_ABS, ABS_X, x) < 0 ||
        emit(fd, EV_ABS, ABS_Y, y) < 0 ||
        emit(fd, EV_SYN, SYN_REPORT, 0) < 0) {
        return -1;
    }
    return 0;
}

static int uinput_up(void)
{
    int fd = g_uinput_fd;
    if (emit(fd, EV_ABS, ABS_MT_SLOT, 0) < 0 ||
        emit(fd, EV_ABS, ABS_MT_TRACKING_ID, -1) < 0 ||
        emit(fd, EV_KEY, BTN_TOUCH, 0) < 0 ||
        emit(fd, EV_KEY, BTN_TOOL_FINGER, 0) < 0 ||
        emit(fd, EV_SYN, SYN_REPORT, 0) < 0) {
        return -1;
    }
    g_holding = 0;
    return 0;
}

static int exec_input(const char *action, int32_t x, int32_t y)
{
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

int input_inject_motionevent(const char *action, int32_t x, int32_t y)
{
    if (action == NULL) {
        return -1;
    }
    if (g_use_uinput && g_uinput_fd >= 0) {
        int rc = -1;
        if (strcmp(action, "DOWN") == 0) {
            rc = uinput_down(x, y);
        } else if (strcmp(action, "MOVE") == 0) {
            rc = uinput_move(x, y);
        } else if (strcmp(action, "UP") == 0) {
            rc = uinput_up();
        }
        if (rc == 0) {
            return 0;
        }
        LOGE("uinput inject failed, fallback to exec");
        g_use_uinput = 0;
    }
    return exec_input(action, x, y);
}

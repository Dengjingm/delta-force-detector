/*
 * Host test for the SVC2 reverse-control codec. Asserts golden bytes for
 * ACQUIRE(100,200) and independent rejection paths, mirroring
 * test_frame_protocol.c.
 *
 * Compile (from repo root):
 *   gcc -std=c11 -Wall -Wextra -Werror -I native-daemon \
 *       native-daemon/control_protocol.c native-daemon/tests/test_control_protocol.c \
 *       -o native-daemon/build-host/test_control_protocol
 * Run:
 *   ./native-daemon/build-host/test_control_protocol
 */

#include "control_protocol.h"

#include <stdio.h>
#include <string.h>

static int g_failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);  \
            ++g_failures;                                                    \
        }                                                                    \
    } while (0)

static void check_err(control_error_t got, control_error_t want, const char *what) {
    if (got != want) {
        fprintf(stderr, "FAIL %s: got %s (%d), want %s (%d)\n", what,
                control_error_str(got), (int)got, control_error_str(want),
                (int)want);
        ++g_failures;
    }
}

/* Golden ACQUIRE(100,200): 53564332 0100 0100 08000000 64000000 c8000000 */
static const uint8_t k_golden_acquire[20] = {
    0x53, 0x56, 0x43, 0x32, /* "SVC2" */
    0x01, 0x00,             /* version = 1 */
    0x01, 0x00,             /* type = ACQUIRE */
    0x08, 0x00, 0x00, 0x00, /* payloadLen = 8 */
    0x64, 0x00, 0x00, 0x00, /* x = 100 */
    0xc8, 0x00, 0x00, 0x00, /* y = 200 */
};

static void test_golden_roundtrip(void) {
    control_cmd_t cmd = { .type = SVC2_CTRL_ACQUIRE, .x = 100, .y = 200 };
    uint8_t buf[SVC2_CTRL_MAX_MESSAGE];
    size_t len = 0;

    check_err(control_cmd_encode(&cmd, buf, sizeof(buf), &len), CTRL_OK,
              "encode golden");
    CHECK(len == 20);
    CHECK(memcmp(buf, k_golden_acquire, 20) == 0);

    control_cmd_t d;
    check_err(control_cmd_decode(buf, len, &d), CTRL_OK, "decode golden");
    CHECK(d.type == SVC2_CTRL_ACQUIRE);
    CHECK(d.x == 100);
    CHECK(d.y == 200);
}

static void test_roundtrip_negative(void) {
    control_cmd_t cmd = { .type = SVC2_CTRL_MOVE, .x = -1, .y = -32768 };
    uint8_t buf[SVC2_CTRL_MAX_MESSAGE];
    size_t len = 0;

    check_err(control_cmd_encode(&cmd, buf, sizeof(buf), &len), CTRL_OK,
              "encode negative");
    CHECK(len == 20);

    control_cmd_t d;
    check_err(control_cmd_decode(buf, len, &d), CTRL_OK, "decode negative");
    CHECK(d.type == SVC2_CTRL_MOVE);
    CHECK(d.x == -1);
    CHECK(d.y == -32768);
}

static void test_decode_rejects(void) {
    control_cmd_t cmd = { .type = SVC2_CTRL_ACQUIRE, .x = 1, .y = 2 };
    uint8_t buf[SVC2_CTRL_MAX_MESSAGE];
    size_t len = 0;
    control_cmd_t out;

    check_err(control_cmd_encode(&cmd, buf, sizeof(buf), &len), CTRL_OK,
              "encode base");
    check_err(control_cmd_decode(buf, len, &out), CTRL_OK, "decode base");

    buf[0] = 'X'; /* break magic */
    check_err(control_cmd_decode(buf, len, &out), CTRL_ERR_MAGIC, "reject magic");
    buf[0] = 'S';

    buf[4] = 0x02; /* version = 2 (LE low byte) */
    check_err(control_cmd_decode(buf, len, &out), CTRL_ERR_VERSION, "reject version");
    buf[4] = 0x01;

    buf[6] = 0x00; /* type = 0 (LE low byte) */
    buf[7] = 0x00;
    check_err(control_cmd_decode(buf, len, &out), CTRL_ERR_TYPE, "reject type");
    buf[6] = 0x01;
    buf[7] = 0x00;

    buf[8] = 0x04; /* payloadLen = 4 (LE low byte) */
    check_err(control_cmd_decode(buf, len, &out), CTRL_ERR_LEN, "reject len");
    buf[8] = 0x08;

    /* short input */
    check_err(control_cmd_decode(buf, 11, &out), CTRL_ERR_LEN, "reject short");

    /* null args */
    check_err(control_cmd_decode(NULL, len, &out), CTRL_ERR_NULL, "reject null in");
    check_err(control_cmd_decode(buf, len, NULL), CTRL_ERR_NULL, "reject null out");
}

static void test_encode_rejects(void) {
    uint8_t buf[SVC2_CTRL_MAX_MESSAGE];
    size_t len = 0;

    control_cmd_t bad_type = { .type = 99, .x = 0, .y = 0 };
    check_err(control_cmd_encode(&bad_type, buf, sizeof(buf), &len), CTRL_ERR_TYPE,
              "reject bad type");

    control_cmd_t ok = { .type = SVC2_CTRL_ACQUIRE, .x = 0, .y = 0 };
    check_err(control_cmd_encode(&ok, buf, 19, &len), CTRL_ERR_LEN,
              "reject small cap");

    check_err(control_cmd_encode(NULL, buf, sizeof(buf), &len), CTRL_ERR_NULL,
              "reject null cmd");
    check_err(control_cmd_encode(&ok, NULL, sizeof(buf), &len), CTRL_ERR_NULL,
              "reject null out");
    check_err(control_cmd_encode(&ok, buf, sizeof(buf), NULL), CTRL_ERR_NULL,
              "reject null len");
}

int main(void) {
    test_golden_roundtrip();
    test_roundtrip_negative();
    test_decode_rejects();
    test_encode_rejects();

    if (g_failures == 0) {
        printf("OK: all control_protocol checks passed\n");
        return 0;
    }
    fprintf(stderr, "%d failure(s)\n", g_failures);
    return 1;
}

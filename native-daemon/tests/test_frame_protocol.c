/*
 * Host test for the v2 frame codec. Builds against contracts/fixtures golden
 * bytes and independently asserts field values + rejection paths.
 *
 * Compile (from repo root):
 *   gcc -std=c11 -Wall -Wextra -Werror -I native-daemon \
 *       native-daemon/frame_protocol.c native-daemon/tests/test_frame_protocol.c \
 *       -o native-daemon/build-host/test_frame_protocol
 * Run (fixture path is argv[1], defaults to repo-root relative):
 *   ./native-daemon/build-host/test_frame_protocol contracts/fixtures/frame_v2_rg_2x1.bin
 */

#include "frame_protocol.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);  \
            ++g_failures;                                                    \
        }                                                                    \
    } while (0)

static void check_err(frame_error_t got, frame_error_t want, const char *what) {
    if (got != want) {
        fprintf(stderr, "FAIL %s: got %s (%d), want %s (%d)\n", what,
                frame_error_str(got), (int)got, frame_error_str(want),
                (int)want);
        ++g_failures;
    }
}

static frame_header_t make_golden_header(void) {
    frame_header_t h;
    h.payload_bytes = 8;
    h.width = 2;
    h.height = 1;
    h.row_stride_bytes = 8;
    h.pixel_format = SVF2_PIXEL_FORMAT_RGBA8888;
    h.rotation_degrees = 0;
    h.frame_id = 1;
    h.capture_start_ns = 1000;
    h.capture_end_ns = 2000;
    h.stream_id = 1;
    return h;
}

static void test_golden_roundtrip(const char *fixture_path) {
    uint8_t fixture[SVF2_HEADER_BYTES + 8]; /* golden frame is exactly 72 bytes */
    FILE *f = fopen(fixture_path, "rb");
    if (f == NULL) {
        fprintf(stderr, "FAIL: cannot open fixture %s\n", fixture_path);
        ++g_failures;
        return;
    }
    size_t n = fread(fixture, 1, sizeof(fixture), f);
    fclose(f);
    CHECK(n == 72);

    /* Header must be exactly the 64-byte golden bytes. */
    uint8_t encoded[SVF2_HEADER_BYTES];
    frame_header_t h = make_golden_header();
    check_err(frame_header_encode(&h, encoded), FRAME_OK, "encode golden");
    CHECK(memcmp(encoded, fixture, SVF2_HEADER_BYTES) == 0);

    /* Decode the fixture back and verify every field. */
    frame_header_t d;
    check_err(frame_header_decode(fixture, &d), FRAME_OK, "decode golden");
    check_err(frame_header_validate(&d), FRAME_OK, "validate golden");
    CHECK(d.payload_bytes == 8);
    CHECK(d.width == 2);
    CHECK(d.height == 1);
    CHECK(d.row_stride_bytes == 8);
    CHECK(d.pixel_format == SVF2_PIXEL_FORMAT_RGBA8888);
    CHECK(d.rotation_degrees == 0);
    CHECK(d.frame_id == 1);
    CHECK(d.capture_start_ns == 1000);
    CHECK(d.capture_end_ns == 2000);
    CHECK(d.stream_id == 1);

    /* Payload bytes are red then green (RGBA8888). */
    CHECK(memcmp(fixture + 64, (uint8_t[]){255, 0, 0, 255, 0, 255, 0, 255}, 8) == 0);
}

static void test_decode_rejects(void) {
    frame_header_t h = make_golden_header();
    uint8_t buf[SVF2_HEADER_BYTES];
    frame_header_t out;

    check_err(frame_header_encode(&h, buf), FRAME_OK, "encode base");
    check_err(frame_header_decode(buf, &out), FRAME_OK, "decode base");

    buf[0] = 'X'; /* break magic */
    check_err(frame_header_decode(buf, &out), FRAME_ERR_MAGIC, "reject bad magic");
    buf[0] = 'S';

    buf[4] = 0x03; /* version = 3 (LE low byte) */
    check_err(frame_header_decode(buf, &out), FRAME_ERR_VERSION, "reject version");
    buf[4] = 0x02;

    buf[6] = 0x20; /* header_bytes = 32 (LE low byte) */
    check_err(frame_header_decode(buf, &out), FRAME_ERR_HEADER_BYTES, "reject header bytes");
    buf[6] = 0x40;

    check_err(frame_header_decode(NULL, &out), FRAME_ERR_NULL, "reject null in");
    check_err(frame_header_decode(buf, NULL), FRAME_ERR_NULL, "reject null out");
}

static void test_validate_rejects(void) {
    frame_header_t h;

    /* width out of range */
    h = make_golden_header();
    h.width = 0;
    check_err(frame_header_validate(&h), FRAME_ERR_WIDTH, "reject width 0");
    h = make_golden_header();
    h.width = 4097;
    check_err(frame_header_validate(&h), FRAME_ERR_WIDTH, "reject width 4097");

    /* height out of range */
    h = make_golden_header();
    h.height = 0;
    check_err(frame_header_validate(&h), FRAME_ERR_HEIGHT, "reject height 0");

    /* stride mismatch */
    h = make_golden_header();
    h.row_stride_bytes = 16;
    check_err(frame_header_validate(&h), FRAME_ERR_STRIDE, "reject stride");

    /* pixel format */
    h = make_golden_header();
    h.pixel_format = 2;
    check_err(frame_header_validate(&h), FRAME_ERR_PIXEL_FORMAT, "reject format");

    /* rotation */
    h = make_golden_header();
    h.rotation_degrees = 45;
    check_err(frame_header_validate(&h), FRAME_ERR_ROTATION, "reject rotation");

    /* payload mismatch (would need width*height*4) */
    h = make_golden_header();
    h.payload_bytes = 7;
    check_err(frame_header_validate(&h), FRAME_ERR_PAYLOAD, "reject payload");

    /* time order */
    h = make_golden_header();
    h.capture_end_ns = 999;
    check_err(frame_header_validate(&h), FRAME_ERR_TIME_ORDER, "reject time order");

    /* zero ids */
    h = make_golden_header();
    h.frame_id = 0;
    check_err(frame_header_validate(&h), FRAME_ERR_FRAME_ID, "reject frame id 0");
    h = make_golden_header();
    h.stream_id = 0;
    check_err(frame_header_validate(&h), FRAME_ERR_STREAM_ID, "reject stream id 0");

    /* null */
    check_err(frame_header_validate(NULL), FRAME_ERR_NULL, "reject null header");
}

static void test_payload_math(void) {
    CHECK(frame_payload_bytes_for(2, 1) == 8);
    CHECK(frame_payload_bytes_for(4096, 4096) == SVF2_MAX_PAYLOAD_BYTES);
    CHECK(frame_payload_bytes_for(4096, 4097) == 0); /* exceeds cap */
    CHECK(frame_payload_bytes_for(0, 1) == 0);
    /* 3200x1440 == 18,432,000 */
    CHECK(frame_payload_bytes_for(3200, 1440) == 18432000u);
}

int main(int argc, char **argv) {
    const char *fixture_path =
        (argc > 1) ? argv[1] : "contracts/fixtures/frame_v2_rg_2x1.bin";

    test_golden_roundtrip(fixture_path);
    test_decode_rejects();
    test_validate_rejects();
    test_payload_math();

    if (g_failures == 0) {
        printf("OK: all frame_protocol checks passed\n");
        return 0;
    }
    fprintf(stderr, "%d failure(s)\n", g_failures);
    return 1;
}

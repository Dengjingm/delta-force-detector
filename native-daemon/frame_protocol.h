#ifndef SV_FRAME_PROTOCOL_H
#define SV_FRAME_PROTOCOL_H

/*
 * v2 frame-protocol codec. Field offsets, units and validation rules are
 * authoritative in CONTRACTS C01; this header only exposes the C API.
 *
 * The wire header is a fixed 64 bytes with an explicit little-endian layout.
 * encode/decode go field-by-field (never a raw C struct cast), so the codec is
 * independent of host endianness and compiler padding.
 */

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SVF2_HEADER_BYTES 64u
#define SVF2_VERSION 2u
#define SVF2_PIXEL_FORMAT_RGBA8888 1u
#define SVF2_MAX_DIM 4096u
#define SVF2_MAX_PAYLOAD_BYTES (64u * 1024u * 1024u) /* 64 MiB */
#define SVF2_MAX_FRAME_BYTES (SVF2_HEADER_BYTES + SVF2_MAX_PAYLOAD_BYTES)

/* Logical header; wire offsets (C01) are irrelevant to callers, but values are
 * decoded/encoded in the exact order and width below. */
typedef struct {
    uint32_t payload_bytes;
    uint32_t width;
    uint32_t height;
    uint32_t row_stride_bytes;
    uint32_t pixel_format;
    uint32_t rotation_degrees;
    uint64_t frame_id;
    uint64_t capture_start_ns;
    uint64_t capture_end_ns;
    uint64_t stream_id;
} frame_header_t;

/* 0 on success; negative frame_error_t otherwise. */
typedef enum {
    FRAME_OK = 0,
    FRAME_ERR_MAGIC = -1,       /* first 4 bytes != "SVF2" */
    FRAME_ERR_VERSION = -2,     /* version field != 2 */
    FRAME_ERR_HEADER_BYTES = -3,/* header_bytes field != 64 */
    FRAME_ERR_WIDTH = -4,       /* width not in [1, 4096] */
    FRAME_ERR_HEIGHT = -5,      /* height not in [1, 4096] */
    FRAME_ERR_STRIDE = -6,      /* row_stride_bytes != width * 4 */
    FRAME_ERR_PIXEL_FORMAT = -7,/* pixel_format != RGBA8888 */
    FRAME_ERR_ROTATION = -8,    /* rotation not in {0,90,180,270} */
    FRAME_ERR_PAYLOAD = -9,     /* payload != width*height*4 or > 64 MiB */
    FRAME_ERR_TIME_ORDER = -10, /* capture_end_ns < capture_start_ns */
    FRAME_ERR_FRAME_ID = -11,   /* frame_id == 0 */
    FRAME_ERR_STREAM_ID = -12,  /* stream_id == 0 */
    FRAME_ERR_NULL = -13,       /* null buffer/header argument */
    FRAME_ERR_INTERNAL = -14,   /* arithmetic overflow */
} frame_error_t;

/*
 * Encode h into exactly SVF2_HEADER_BYTES bytes at out, little-endian,
 * magic "SVF2", version 2, header_bytes 64. Returns FRAME_OK or FRAME_ERR_NULL.
 */
frame_error_t frame_header_encode(const frame_header_t *h,
                                  uint8_t out[SVF2_HEADER_BYTES]);

/*
 * Decode SVF2_HEADER_BYTES bytes from in into h. Rejects bad magic, version or
 * header_bytes (FRAME_ERR_MAGIC/VERSION/HEADER_BYTES) before trusting the rest.
 * Semantic field checks are a separate frame_header_validate() call.
 */
frame_error_t frame_header_decode(const uint8_t in[SVF2_HEADER_BYTES],
                                  frame_header_t *h);

/*
 * Full semantic validation of a decoded header: dimension bounds, stride,
 * payload consistency (64-bit width*height*4 == payload_bytes, <= 64 MiB),
 * pixel format, rotation, time order and non-zero IDs.
 */
frame_error_t frame_header_validate(const frame_header_t *h);

/*
 * width*height*4 computed in 64 bits. Returns 0 on overflow. Provided so both
 * the validator and callers use one overflow-safe computation.
 */
uint64_t frame_payload_bytes_for(uint32_t width, uint32_t height);

/* Human-readable message for an error code; never NULL. */
const char *frame_error_str(frame_error_t err);

#ifdef __cplusplus
}
#endif

#endif /* SV_FRAME_PROTOCOL_H */

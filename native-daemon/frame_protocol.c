#include "frame_protocol.h"

#include <stddef.h>

/* "SVF2" as the exact four header bytes (C01 offset 0). */
static const uint8_t k_magic[4] = {'S', 'V', 'F', '2'};

/* Little-endian byte writers/readers: endianness-independent by construction. */

static void put_u16_le(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v & 0xffu);
    p[1] = (uint8_t)((v >> 8) & 0xffu);
}

static void put_u32_le(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xffu);
    p[1] = (uint8_t)((v >> 8) & 0xffu);
    p[2] = (uint8_t)((v >> 16) & 0xffu);
    p[3] = (uint8_t)((v >> 24) & 0xffu);
}

static void put_u64_le(uint8_t *p, uint64_t v) {
    put_u32_le(p, (uint32_t)(v & 0xffffffffu));
    put_u32_le(p + 4, (uint32_t)(v >> 32));
}

static uint16_t get_u16_le(const uint8_t *p) {
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static uint32_t get_u32_le(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint64_t get_u64_le(const uint8_t *p) {
    return (uint64_t)get_u32_le(p) | ((uint64_t)get_u32_le(p + 4) << 32);
}

frame_error_t frame_header_encode(const frame_header_t *h,
                                  uint8_t out[SVF2_HEADER_BYTES]) {
    if (h == NULL || out == NULL) {
        return FRAME_ERR_NULL;
    }

    for (size_t i = 0; i < sizeof(k_magic); ++i) {
        out[i] = k_magic[i];
    }
    put_u16_le(out + 4, SVF2_VERSION);
    put_u16_le(out + 6, SVF2_HEADER_BYTES);
    put_u32_le(out + 8, h->payload_bytes);
    put_u32_le(out + 12, h->width);
    put_u32_le(out + 16, h->height);
    put_u32_le(out + 20, h->row_stride_bytes);
    put_u32_le(out + 24, h->pixel_format);
    put_u32_le(out + 28, h->rotation_degrees);
    put_u64_le(out + 32, h->frame_id);
    put_u64_le(out + 40, h->capture_start_ns);
    put_u64_le(out + 48, h->capture_end_ns);
    put_u64_le(out + 56, h->stream_id);
    return FRAME_OK;
}

frame_error_t frame_header_decode(const uint8_t in[SVF2_HEADER_BYTES],
                                  frame_header_t *h) {
    if (in == NULL || h == NULL) {
        return FRAME_ERR_NULL;
    }

    for (size_t i = 0; i < sizeof(k_magic); ++i) {
        if (in[i] != k_magic[i]) {
            return FRAME_ERR_MAGIC;
        }
    }
    if (get_u16_le(in + 4) != SVF2_VERSION) {
        return FRAME_ERR_VERSION;
    }
    if (get_u16_le(in + 6) != SVF2_HEADER_BYTES) {
        return FRAME_ERR_HEADER_BYTES;
    }

    h->payload_bytes = get_u32_le(in + 8);
    h->width = get_u32_le(in + 12);
    h->height = get_u32_le(in + 16);
    h->row_stride_bytes = get_u32_le(in + 20);
    h->pixel_format = get_u32_le(in + 24);
    h->rotation_degrees = get_u32_le(in + 28);
    h->frame_id = get_u64_le(in + 32);
    h->capture_start_ns = get_u64_le(in + 40);
    h->capture_end_ns = get_u64_le(in + 48);
    h->stream_id = get_u64_le(in + 56);
    return FRAME_OK;
}

uint64_t frame_payload_bytes_for(uint32_t width, uint32_t height) {
    uint64_t pixels = (uint64_t)width * (uint64_t)height;
    if (pixels > SVF2_MAX_PAYLOAD_BYTES / 4u) {
        return 0; /* width*height*4 would exceed the 64 MiB cap */
    }
    return pixels * 4u;
}

frame_error_t frame_header_validate(const frame_header_t *h) {
    if (h == NULL) {
        return FRAME_ERR_NULL;
    }

    if (h->width < 1u || h->width > SVF2_MAX_DIM) {
        return FRAME_ERR_WIDTH;
    }
    if (h->height < 1u || h->height > SVF2_MAX_DIM) {
        return FRAME_ERR_HEIGHT;
    }
    if (h->row_stride_bytes != h->width * 4u) {
        return FRAME_ERR_STRIDE;
    }
    if (h->pixel_format != SVF2_PIXEL_FORMAT_RGBA8888) {
        return FRAME_ERR_PIXEL_FORMAT;
    }
    if (h->rotation_degrees != 0u && h->rotation_degrees != 90u &&
        h->rotation_degrees != 180u && h->rotation_degrees != 270u) {
        return FRAME_ERR_ROTATION;
    }

    uint64_t expected = frame_payload_bytes_for(h->width, h->height);
    if (expected == 0 || h->payload_bytes != expected) {
        return FRAME_ERR_PAYLOAD;
    }

    if (h->capture_end_ns < h->capture_start_ns) {
        return FRAME_ERR_TIME_ORDER;
    }
    if (h->frame_id == 0u) {
        return FRAME_ERR_FRAME_ID;
    }
    if (h->stream_id == 0u) {
        return FRAME_ERR_STREAM_ID;
    }
    return FRAME_OK;
}

const char *frame_error_str(frame_error_t err) {
    switch (err) {
    case FRAME_OK: return "ok";
    case FRAME_ERR_MAGIC: return "bad magic";
    case FRAME_ERR_VERSION: return "unsupported version";
    case FRAME_ERR_HEADER_BYTES: return "unsupported header size";
    case FRAME_ERR_WIDTH: return "width out of range";
    case FRAME_ERR_HEIGHT: return "height out of range";
    case FRAME_ERR_STRIDE: return "row stride != width*4";
    case FRAME_ERR_PIXEL_FORMAT: return "unsupported pixel format";
    case FRAME_ERR_ROTATION: return "invalid rotation";
    case FRAME_ERR_PAYLOAD: return "payload size mismatch or too large";
    case FRAME_ERR_TIME_ORDER: return "capture_end < capture_start";
    case FRAME_ERR_FRAME_ID: return "zero frame id";
    case FRAME_ERR_STREAM_ID: return "zero stream id";
    case FRAME_ERR_NULL: return "null argument";
    case FRAME_ERR_INTERNAL: return "internal error";
    }
    return "unknown error";
}

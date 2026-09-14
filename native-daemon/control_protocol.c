#include "control_protocol.h"

#include <stddef.h>
#include <string.h>

/* "SVC2" as exact four header bytes. */
static const uint8_t k_magic[4] = {'S', 'V', 'C', '2'};

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

static void put_i32_le(uint8_t *p, int32_t v) {
    put_u32_le(p, (uint32_t)v);
}

static uint16_t get_u16_le(const uint8_t *p) {
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static uint32_t get_u32_le(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static int32_t get_i32_le(const uint8_t *p) {
    return (int32_t)get_u32_le(p);
}

static size_t payload_len_for(uint32_t type) {
    (void)type;
    return 8u; /* 三类命令均携带 i32 x,y */
}

control_error_t control_cmd_encode(const control_cmd_t *cmd,
                                   uint8_t *out, size_t out_cap,
                                   size_t *out_len) {
    if (cmd == NULL || out == NULL || out_len == NULL) {
        return CTRL_ERR_NULL;
    }
    if (cmd->type != SVC2_CTRL_ACQUIRE && cmd->type != SVC2_CTRL_MOVE &&
        cmd->type != SVC2_CTRL_RELEASE) {
        return CTRL_ERR_TYPE;
    }

    size_t payload = payload_len_for(cmd->type);
    size_t total = SVC2_CTRL_HEADER_BYTES + payload;
    if (out_cap < total) {
        return CTRL_ERR_LEN;
    }

    memcpy(out, k_magic, sizeof(k_magic));
    put_u16_le(out + 4, SVC2_CTRL_VERSION);
    put_u16_le(out + 6, (uint16_t)cmd->type);
    put_u32_le(out + 8, (uint32_t)payload);
    put_i32_le(out + SVC2_CTRL_HEADER_BYTES, cmd->x);
    put_i32_le(out + SVC2_CTRL_HEADER_BYTES + 4, cmd->y);

    *out_len = total;
    return CTRL_OK;
}

control_error_t control_cmd_decode(const uint8_t *in, size_t in_len,
                                   control_cmd_t *cmd) {
    if (in == NULL || cmd == NULL) {
        return CTRL_ERR_NULL;
    }
    if (in_len < SVC2_CTRL_HEADER_BYTES) {
        return CTRL_ERR_LEN;
    }

    if (memcmp(in, k_magic, sizeof(k_magic)) != 0) {
        return CTRL_ERR_MAGIC;
    }
    if (get_u16_le(in + 4) != SVC2_CTRL_VERSION) {
        return CTRL_ERR_VERSION;
    }

    uint32_t type = get_u16_le(in + 6);
    if (type != SVC2_CTRL_ACQUIRE && type != SVC2_CTRL_MOVE &&
        type != SVC2_CTRL_RELEASE) {
        return CTRL_ERR_TYPE;
    }

    uint32_t payload_len = get_u32_le(in + 8);
    if (payload_len != payload_len_for(type)) {
        return CTRL_ERR_LEN;
    }
    if (in_len < SVC2_CTRL_HEADER_BYTES + payload_len) {
        return CTRL_ERR_LEN;
    }

    cmd->type = type;
    cmd->x = get_i32_le(in + SVC2_CTRL_HEADER_BYTES);
    cmd->y = get_i32_le(in + SVC2_CTRL_HEADER_BYTES + 4);
    return CTRL_OK;
}

const char *control_error_str(control_error_t err) {
    switch (err) {
    case CTRL_OK: return "ok";
    case CTRL_ERR_MAGIC: return "bad magic";
    case CTRL_ERR_VERSION: return "unsupported version";
    case CTRL_ERR_TYPE: return "unsupported command type";
    case CTRL_ERR_LEN: return "payload length mismatch";
    case CTRL_ERR_NULL: return "null argument";
    case CTRL_ERR_INTERNAL: return "internal error";
    }
    return "unknown error";
}

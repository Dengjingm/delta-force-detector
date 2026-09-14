#!/usr/bin/env python3
"""Generate byte-exact v2 frame-protocol fixtures from the frozen CONTRACTS C01.

This script is the *source* of the golden bytes. It does NOT import any
production codec; the header is packed field-by-field with an explicit
little-endian struct format, and an independently hand-derived hex string is
asserted equal so a future codec bug cannot silently rewrite the golden.

Usage (from repo root, or anywhere; paths are resolved relative to this file):

    python3 contracts/fixtures/generate_fixtures.py

Outputs (next to this script):
    frame_v2_rg_2x1.bin   -- the 72-byte golden frame
    manifest.json         -- sizes + SHA-256 + field expectations
"""

from __future__ import annotations

import hashlib
import json
import struct
from pathlib import Path

HERE = Path(__file__).resolve().parent

# --- Frozen values from CONTRACTS C01 "黄金帧样例" ---------------------------
MAGIC = b"SVF2"                 # ASCII, little-endian irrelevant
VERSION = 2                     # u16
HEADER_BYTES = 64               # u16
PAYLOAD_BYTES = 8               # u32
WIDTH = 2                       # u32
HEIGHT = 1                      # u32
ROW_STRIDE_BYTES = 8            # u32 == width*4
PIXEL_FORMAT = 1                # u32 == RGBA8888
ROTATION_DEGREES = 0            # u32
FRAME_ID = 1                    # u64
CAPTURE_START_NS = 1000         # u64
CAPTURE_END_NS = 2000           # u64
STREAM_ID = 1                   # u64

# Payload: red (255,0,0,255) then green (0,255,0,255), tight RGBA8888.
PAYLOAD = bytes([255, 0, 0, 255, 0, 255, 0, 255])

# Field-by-field, explicit little-endian. Order mirrors CONTRACTS offsets.
_HEADER_FMT = "<4sHHIIIIIIQQQQ"
_HEADER_VALUES = (
    MAGIC,
    VERSION,
    HEADER_BYTES,
    PAYLOAD_BYTES,
    WIDTH,
    HEIGHT,
    ROW_STRIDE_BYTES,
    PIXEL_FORMAT,
    ROTATION_DEGREES,
    FRAME_ID,
    CAPTURE_START_NS,
    CAPTURE_END_NS,
    STREAM_ID,
)

# Independently hand-derived hex (offset-by-offset from CONTRACTS table).
# This must NOT be produced by struct.pack; it is the cross-check.
EXPECTED_HEX = (
    "53564632"              # magic SVF2
    "0200"                  # version = 2
    "4000"                  # headerBytes = 64
    "08000000"              # payloadBytes = 8
    "02000000"              # width = 2
    "01000000"              # height = 1
    "08000000"              # rowStrideBytes = 8
    "01000000"              # pixelFormat = 1
    "00000000"              # rotationDegrees = 0
    "0100000000000000"      # frameId = 1
    "e803000000000000"      # captureStartNs = 1000
    "d007000000000000"      # captureEndNs = 2000
    "0100000000000000"      # streamId = 1
    "ff0000ff"              # red   (255,0,0,255)
    "00ff00ff"              # green (0,255,0,255)
)


def build_frame() -> bytes:
    header = struct.pack(_HEADER_FMT, *_HEADER_VALUES)
    if len(header) != HEADER_BYTES:
        raise AssertionError(f"header is {len(header)} bytes, expected {HEADER_BYTES}")
    if len(PAYLOAD) != PAYLOAD_BYTES:
        raise AssertionError(f"payload is {len(PAYLOAD)} bytes, expected {PAYLOAD_BYTES}")
    return header + PAYLOAD


def main() -> None:
    frame = build_frame()

    # Independent cross-check: struct-derived bytes must equal the hand hex.
    actual_hex = frame.hex()
    if actual_hex != EXPECTED_HEX:
        raise AssertionError(
            "struct-derived bytes diverged from hand-derived golden hex:\n"
            f"  struct: {actual_hex}\n"
            f"  golden: {EXPECTED_HEX}"
        )

    if len(frame) != 72:
        raise AssertionError(f"total frame is {len(frame)} bytes, expected 72")

    sha256 = hashlib.sha256(frame).hexdigest()

    bin_path = HERE / "frame_v2_rg_2x1.bin"
    bin_path.write_bytes(frame)

    manifest = {
        "schemaVersion": 1,
        "protocolVersion": 2,
        "generatedBy": "generate_fixtures.py",
        "fixtures": [
            {
                "name": "frame_v2_rg_2x1",
                "file": "frame_v2_rg_2x1.bin",
                "kind": "golden_normal",
                "sizeBytes": len(frame),
                "sha256": sha256,
                "hex": actual_hex,
                "description": (
                    "v2 golden frame: 2x1 RGBA8888, stride 8, rotation 0, "
                    "frameId 1, captureStartNs 1000, captureEndNs 2000, "
                    "streamId 1. Payload is red (255,0,0,255) then green "
                    "(0,255,0,255)."
                ),
                "header": {
                    "magic": MAGIC.decode("ascii"),
                    "version": VERSION,
                    "headerBytes": HEADER_BYTES,
                    "payloadBytes": PAYLOAD_BYTES,
                    "width": WIDTH,
                    "height": HEIGHT,
                    "rowStrideBytes": ROW_STRIDE_BYTES,
                    "pixelFormat": PIXEL_FORMAT,
                    "rotationDegrees": ROTATION_DEGREES,
                    "frameId": FRAME_ID,
                    "captureStartNs": CAPTURE_START_NS,
                    "captureEndNs": CAPTURE_END_NS,
                    "streamId": STREAM_ID,
                },
                "payload": {
                    "pixelOrder": "RGBA8888",
                    "pixels": [
                        {"x": 0, "y": 0, "rgba": [255, 0, 0, 255]},
                        {"x": 1, "y": 0, "rgba": [0, 255, 0, 255]},
                    ],
                },
            }
        ],
    }

    manifest_path = HERE / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")

    print(f"wrote {bin_path.name} ({len(frame)} bytes, sha256={sha256})")
    print(f"wrote {manifest_path.name}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Create one no-clobber, byte-stable synthetic RGB PNG, using only stdlib."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import zlib

WIDTH, HEIGHT = 480, 320


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def fixture_bytes():
    row = b"\0" + b"\xff\0\0" * 160 + b"\0\xff\0" * 160 + b"\0\0\xff" * 160
    pixels = row * HEIGHT
    # Stored DEFLATE blocks avoid compressor-version-dependent output. The
    # zlib wrapper and PNG checksums implement their fixed format algorithms.
    stream = bytearray(b"\x78\x01")
    for start in range(0, len(pixels), 65535):
        block = pixels[start:start + 65535]
        stream += bytes([int(start + len(block) == len(pixels))])
        stream += struct.pack("<HH", len(block), 65535 - len(block)) + block
    stream += struct.pack(">I", zlib.adler32(pixels))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", bytes(stream)) + chunk(b"IEND", b""))


def write_fixture(output):
    path = Path(output)
    if not path.is_absolute():
        raise ValueError("absolute_output_required")
    data = fixture_bytes()
    with path.open("xb") as target:
        target.write(data)
        target.flush()
        os.fsync(target.fileno())
    return {"fixture": "three-color-rgb", "width": WIDTH, "height": HEIGHT,
            "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        result = write_fixture(args.output)
    except FileExistsError:
        parser.exit(1, "fixture_exists\n")
    except (OSError, ValueError):
        parser.exit(1, "fixture_write_failed\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()

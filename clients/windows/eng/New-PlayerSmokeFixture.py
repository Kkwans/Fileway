"""Create a private, deterministic, indexed BI_RGB AVI using only the stdlib.

The central fiducials and binary frame counter are actual video pixels. They let
the native smoke test distinguish rendering/progress from a changing UI label.
No codecs, downloaded media, audio, timestamps, or user data are included.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import tempfile
from pathlib import Path

WIDTH = 160
HEIGHT = 90
FPS = 12
DEFAULT_SECONDS = 180
MAX_SECONDS = 600
FIXTURE_ID = "fileway-player-smoke-v1"
STRIDE = (WIDTH * 3 + 3) & ~3
FRAME_BYTES = STRIDE * HEIGHT
BACKGROUND = (16, 20, 28)
BIT_OFF = (24, 24, 24)
BIT_ON = (240, 240, 240)
MARKER = (232, 64, 232)
FIDUCIALS = (
    (12, 32, 28, 43, (240, 48, 48)),
    (132, 32, 148, 43, (48, 232, 72)),
    (12, 47, 28, 58, (56, 80, 240)),
    (132, 47, 148, 58, (240, 224, 48)),
)


def chunk(kind: bytes, data: bytes) -> bytes:
    return kind + struct.pack("<I", len(data)) + data + (b"\0" if len(data) & 1 else b"")


def avi_headers(frame_count: int) -> bytes:
    """Minimal AVI 1.0: one uncompressed, bottom-up 24-bit DIB video stream."""
    main = struct.pack("<14I", round(1_000_000 / FPS), FRAME_BYTES * FPS, 0,
                       0x10, frame_count, 0, 1, FRAME_BYTES, WIDTH, HEIGHT, 0, 0, 0, 0)
    stream = struct.pack("<4s4sIHH8I4h", b"vids", b"DIB ", 0, 0, 0,
                         0, 1, FPS, 0, frame_count, FRAME_BYTES, 0xFFFFFFFF, 0,
                         0, 0, WIDTH, HEIGHT)
    bitmap = struct.pack("<IiiHHIIiiII", 40, WIDTH, HEIGHT, 1, 24, 0,
                         FRAME_BYTES, 0, 0, 0, 0)
    return chunk(b"LIST", b"hdrl" + chunk(b"avih", main)
                 + chunk(b"LIST", b"strl" + chunk(b"strh", stream) + chunk(b"strf", bitmap)))


def paint(frame: bytearray, left: int, top: int, right: int, bottom: int,
          rgb: tuple[int, int, int]) -> None:
    row = bytes(reversed(rgb)) * (right - left)
    for y in range(top, bottom):
        offset = (HEIGHT - 1 - y) * STRIDE + left * 3
        frame[offset:offset + len(row)] = row


def base_frame() -> bytes:
    frame = bytearray(bytes(reversed(BACKGROUND)) * WIDTH * HEIGHT)
    for left, top, right, bottom, rgb in FIDUCIALS:
        paint(frame, left, top, right, bottom, rgb)
    return bytes(frame)


BASE_FRAME = base_frame()


def render_frame(index: int) -> bytes:
    if not 0 <= index < 65536:
        raise ValueError("Frame counter must fit 16 bits")
    frame = bytearray(BASE_FRAME)
    # Little-endian binary counter, sixteen 4x18 pixel cells at x=48..112.
    for bit in range(16):
        paint(frame, 48 + 4 * bit, 36, 52 + 4 * bit, 54,
              BIT_ON if index & (1 << bit) else BIT_OFF)
    # A separate visible moving marker also validates the decoded frame number.
    left = 12 + index % 128
    paint(frame, left, 62, left + 8, 68, MARKER)
    return bytes(frame)


def fixture_size(frame_count: int) -> int:
    return 12 + len(avi_headers(frame_count)) + 12 + frame_count * (8 + FRAME_BYTES) + 8 + frame_count * 16


def write_avi(stream, frame_count: int) -> None:
    stream.write(b"RIFF" + struct.pack("<I", fixture_size(frame_count) - 8) + b"AVI ")
    stream.write(avi_headers(frame_count))
    stream.write(b"LIST" + struct.pack("<I", 4 + frame_count * (8 + FRAME_BYTES)) + b"movi")
    for index in range(frame_count):
        stream.write(b"00db" + struct.pack("<I", FRAME_BYTES))
        stream.write(render_frame(index))
    stream.write(b"idx1" + struct.pack("<I", frame_count * 16))
    for index in range(frame_count):
        # AVIOLDINDEX offsets are relative to the movi FOURCC (first chunk = 4).
        stream.write(struct.pack("<4sIII", b"00db", 0x10, 4 + index * (8 + FRAME_BYTES), FRAME_BYTES))


def checked_destination(value: Path) -> Path:
    path = Path(os.path.abspath(value))
    if path.suffix.lower() != ".avi":
        raise ValueError("The fixture destination must end in .avi")
    for ancestor in (path, *path.parents):
        if os.name == "nt" and not hasattr(ancestor, "is_junction"):
            raise RuntimeError("Windows fixture paths require Python 3.12+ junction checks")
        if ancestor.is_symlink() or (hasattr(ancestor, "is_junction") and ancestor.is_junction()):
            raise ValueError("Fixture paths cannot contain symlinks or junctions")
        marker = ancestor / ".git"
        if marker.is_file() or (marker / "HEAD").is_file():
            raise ValueError("Private media must be created outside Git")
    return path


def activate(temporary: Path, destination: Path) -> None:
    # A same-volume hard link is atomic and refuses a late-arriving destination.
    # Unsupported filesystems fail safely; never fall back to overwrite/replace.
    os.link(temporary, destination)
    temporary.unlink()


def create_fixture(destination: Path, seconds: int = DEFAULT_SECONDS) -> dict:
    if isinstance(seconds, bool) or not isinstance(seconds, int) or not 1 <= seconds <= MAX_SECONDS:
        raise ValueError("Duration must be an integer from 1 to 600 seconds")
    path = checked_destination(destination)
    manifest_path = path.with_name(path.name + ".fixture.json")
    if path.exists() or manifest_path.exists() or manifest_path.is_symlink():
        raise FileExistsError("Fixture or companion manifest already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    # Recheck newly created ancestors before opening any output.
    checked_destination(path)
    frame_count = seconds * FPS
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".player-smoke-", suffix=".partial", delete=False) as stream:
        temporary = Path(stream.name)
        write_avi(stream, frame_count)
    digest = hashlib.sha256()
    with temporary.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    manifest = {"schemaVersion": 1, "fixtureId": FIXTURE_ID, "width": WIDTH,
                "height": HEIGHT, "framesPerSecond": FPS, "frameCount": frame_count,
                "byteLength": fixture_size(frame_count), "sha256": digest.hexdigest()}
    activate(temporary, path)
    # This file contains no paths or host-specific data. Failure retains the AVI.
    with manifest_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(manifest, stream, sort_keys=True, indent=2)
        stream.write("\n")
    return manifest


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="New .avi file outside Git")
    parser.add_argument("--duration-seconds", type=int, default=DEFAULT_SECONDS)
    args = parser.parse_args(argv)
    manifest = create_fixture(args.output, args.duration_seconds)
    print(json.dumps(manifest, sort_keys=True))


if __name__ == "__main__":
    main()

"""Create deterministic front-metadata YUV4MPEG2 video outside any checkout.

This intentionally sequential, index-free container isolates owned-prefix reads
from tail-index availability. It proves only this supported container, not that
arbitrary incomplete MP4/AVI files can start playback. No third-party media or
codec tools are used. Native smoke evidence is decoder-level, not visible pixels.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path

FIXTURE_ID = "fileway-progressive-y4m-v1"
WIDTH, HEIGHT, FPS, SECONDS = 160, 90, 12, 40
HEADER = f"YUV4MPEG2 W{WIDTH} H{HEIGHT} F{FPS}:1 Ip A1:1 C420jpeg\n".encode("ascii")
FRAME_HEADER = b"FRAME\n"
FRAME_BYTES = WIDTH * HEIGHT * 3 // 2
FRAME_RECORD_BYTES = len(FRAME_HEADER) + FRAME_BYTES
FRAME_COUNT = FPS * SECONDS


def render_frame(index: int) -> bytes:
    if not 0 <= index < FRAME_COUNT:
        raise ValueError("Frame number is outside the fixed synthetic fixture")
    # A changing grayscale field, a moving bar, and a binary frame counter are
    # genuine luma samples. Chroma is neutral, with no audio or external content.
    luma = bytearray([32 + index % 128]) * (WIDTH * HEIGHT)
    left = index % (WIDTH - 8)
    for y in range(HEIGHT):
        luma[y * WIDTH + left:y * WIDTH + left + 8] = b"\xeb" * 8
    for bit in range(16):
        value = 235 if index & (1 << bit) else 16
        for y in range(36, 54):
            luma[y * WIDTH + 48 + 4 * bit:y * WIDTH + 52 + 4 * bit] = bytes([value]) * 4
    return bytes(luma) + b"\x80" * (WIDTH * HEIGHT // 2)


def prefix_bytes(seconds: int) -> int:
    return len(HEADER) + seconds * FPS * FRAME_RECORD_BYTES


def write_fixture(stream) -> None:
    stream.write(HEADER)
    for index in range(FRAME_COUNT):
        stream.write(FRAME_HEADER)
        stream.write(render_frame(index))


def checked_destination(value: Path) -> Path:
    path = Path(os.path.abspath(value))
    if path.suffix.lower() != ".y4m":
        raise ValueError("Fixture output must end in .y4m")
    for ancestor in (path, *path.parents):
        if os.name == "nt" and not hasattr(ancestor, "is_junction"):
            raise RuntimeError("Windows fixture paths require Python 3.12+ junction checks")
        if ancestor.is_symlink() or (hasattr(ancestor, "is_junction") and ancestor.is_junction()):
            raise ValueError("Fixture paths cannot contain symlinks or junctions")
        marker = ancestor / ".git"
        if marker.is_file() or (marker / "HEAD").is_file():
            raise ValueError("Synthetic media must be created outside Git")
    return path


def activate(temporary: Path, destination: Path) -> None:
    os.link(temporary, destination)  # Atomic create, never overwrite a late arrival.
    temporary.unlink()


def create_fixture(destination: Path) -> dict:
    path = checked_destination(destination)
    manifest_path = path.with_name(path.name + ".fixture.json")
    if path.exists() or manifest_path.exists() or manifest_path.is_symlink():
        raise FileExistsError("Fixture or manifest already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    checked_destination(path)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".progressive-smoke-", suffix=".partial", delete=False) as stream:
        temporary = Path(stream.name)
        write_fixture(stream)
    with temporary.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    receipt = {"schemaVersion": 1, "fixtureId": FIXTURE_ID, "container": "yuv4mpegpipe",
               "width": WIDTH, "height": HEIGHT, "framesPerSecond": FPS, "frameCount": FRAME_COUNT,
               "byteLength": prefix_bytes(SECONDS), "headerBytes": len(HEADER),
               "frameRecordBytes": FRAME_RECORD_BYTES, "initialPrefixBytes": prefix_bytes(8),
               "extendedPrefixBytes": prefix_bytes(16), "sha256": digest}
    activate(temporary, path)
    with manifest_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(receipt, stream, sort_keys=True, indent=2)
        stream.write("\n")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="New .y4m path outside Git")
    print(json.dumps(create_fixture(parser.parse_args().output), sort_keys=True))


if __name__ == "__main__":
    main()

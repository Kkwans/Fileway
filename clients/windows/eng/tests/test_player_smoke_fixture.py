"""Offline AVI contract tests; no external decoder, packages, or media download."""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
import struct
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "New-PlayerSmokeFixture.py"
SPEC = importlib.util.spec_from_file_location("player_smoke_fixture", SCRIPT)
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)


def chunks(data: bytes, start: int, end: int):
    while start < end:
        kind, size = struct.unpack_from("<4sI", data, start)
        payload = start + 8
        if payload + size > end:
            raise ValueError("Truncated RIFF chunk")
        yield kind, payload, size
        start = payload + size + (size & 1)
    if start != end:
        raise ValueError("Misaligned RIFF boundary")


def pixel(frame: bytes, x: int, y: int) -> tuple:
    offset = (fixture.HEIGHT - 1 - y) * fixture.STRIDE + x * 3
    return tuple(reversed(frame[offset:offset + 3]))


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="Fileway Smoke Fixture ", dir=os.environ.get("FILEWAY_MPV_CHECK_TMP"))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def avi(self, count=24):
        stream = io.BytesIO()
        fixture.write_avi(stream, count)
        return stream.getvalue()

    def test_deterministic_complete_riff(self):
        data = self.avi()
        self.assertEqual(data, self.avi())
        self.assertEqual(data[:4], b"RIFF")
        self.assertEqual(data[8:12], b"AVI ")
        self.assertEqual(struct.unpack_from("<I", data, 4)[0], len(data) - 8)
        self.assertEqual(len(data), fixture.fixture_size(24))
        self.assertEqual([kind for kind, _, _ in chunks(data, 12, len(data))], [b"LIST", b"LIST", b"idx1"])

    def test_headers_have_one_rgb24_stream_and_exact_timing(self):
        data = self.avi()
        _, header, size = next(chunks(data, 12, len(data)))
        self.assertEqual(data[header:header + 4], b"hdrl")
        main_chunk, stream_chunk = list(chunks(data, header + 4, header + size))
        kind, offset, size = main_chunk
        self.assertEqual((kind, size), (b"avih", 56))
        main = struct.unpack_from("<14I", data, offset)
        self.assertEqual((main[0], main[3], main[4], main[6], main[8], main[9]), (83333, 16, 24, 1, 160, 90))
        _, offset, size = stream_chunk
        self.assertEqual(data[offset:offset + 4], b"strl")
        stream, bitmap = list(chunks(data, offset + 4, offset + size))
        self.assertEqual((stream[0], stream[2]), (b"strh", 56))
        fields = struct.unpack_from("<4s4sIHH8I4h", data, stream[1])
        self.assertEqual((fields[0], fields[1], fields[6], fields[7], fields[9]), (b"vids", b"DIB ", 1, 12, 24))
        self.assertEqual(fields[-4:], (0, 0, 160, 90))
        self.assertEqual((bitmap[0], bitmap[2]), (b"strf", 40))
        fields = struct.unpack_from("<IiiHHIIiiII", data, bitmap[1])
        self.assertEqual(fields[:7], (40, 160, 90, 1, 24, 0, 43200))

    def test_every_index_points_to_a_full_uncompressed_keyframe(self):
        data = self.avi()
        _, movi, size = list(chunks(data, 12, len(data)))[1]
        self.assertEqual(data[movi:movi + 4], b"movi")
        frames = list(chunks(data, movi + 4, movi + size))
        kind, index, size = list(chunks(data, 12, len(data)))[2]
        self.assertEqual((kind, size, len(frames)), (b"idx1", 24 * 16, 24))
        for number, (kind, offset, size) in enumerate(frames):
            chunk_id, flags, relative, indexed_size = struct.unpack_from("<4sIII", data, index + number * 16)
            self.assertEqual((chunk_id, kind, flags, indexed_size, size), (b"00db", b"00db", 16, 43200, 43200))
            self.assertEqual(movi + relative, offset - 8)
            self.assertEqual(data[offset:offset + size], fixture.render_frame(number))

    def test_real_pixels_encode_count_orientation_and_moving_marker(self):
        for number in (0, 1, 12, 127, 128, 255, 256, 1439, 2159, 65535):
            frame = fixture.render_frame(number)
            for left, top, right, bottom, color in fixture.FIDUCIALS:
                self.assertEqual(pixel(frame, (left + right) // 2, (top + bottom) // 2), color)
            decoded = 0
            for bit in range(16):
                color = pixel(frame, 50 + bit * 4, 45)
                self.assertIn(color, (fixture.BIT_ON, fixture.BIT_OFF))
                decoded |= (color == fixture.BIT_ON) << bit
            self.assertEqual(decoded, number)
            self.assertEqual(pixel(frame, 16 + number % 128, 65), fixture.MARKER)
            self.assertEqual(pixel(frame, 0, 0), fixture.BACKGROUND)
        self.assertNotEqual(fixture.render_frame(0), fixture.render_frame(1))

    def test_manifest_hash_and_bytes_are_deterministic_without_private_paths(self):
        first, second = self.root / "first file.avi", self.root / "Second File.AVI"
        receipt = fixture.create_fixture(first, 1)
        self.assertEqual(receipt, fixture.create_fixture(second, 1))
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(receipt["sha256"], hashlib.sha256(first.read_bytes()).hexdigest())
        self.assertEqual(receipt["byteLength"], first.stat().st_size)
        self.assertEqual(receipt["frameCount"], 12)
        manifest = first.with_name(first.name + ".fixture.json").read_text()
        self.assertEqual(json.loads(manifest), receipt)
        self.assertNotIn(str(self.root), manifest)

    def test_duration_and_counter_limits(self):
        for duration in (0, -1, 601, 1.5, True):
            with self.subTest(duration=duration), self.assertRaises(ValueError):
                fixture.create_fixture(self.root / "fixture.avi", duration)
        for index in (-1, 65536):
            with self.assertRaises(ValueError):
                fixture.render_frame(index)
        self.assertLess(fixture.fixture_size(600 * 12), 512 * 1024 * 1024)

    def test_existing_media_or_manifest_is_preserved(self):
        for target in (self.root / "fixture.avi", self.root / "fixture.avi.fixture.json"):
            target.write_bytes(b"existing")
            with self.assertRaises(FileExistsError):
                fixture.create_fixture(self.root / "fixture.avi", 1)
            self.assertEqual(target.read_bytes(), b"existing")
            target.unlink()

    def test_late_destination_is_never_overwritten(self):
        destination = self.root / "late fixture.avi"
        original = fixture.activate
        def late(temporary, path):
            path.write_bytes(b"late writer")
            original(temporary, path)
        with mock.patch.object(fixture, "activate", side_effect=late), self.assertRaises(FileExistsError):
            fixture.create_fixture(destination, 1)
        self.assertEqual(destination.read_bytes(), b"late writer")
        self.assertEqual(len(list(self.root.glob(".player-smoke-*.partial"))), 1)

    def test_git_output_and_wrong_extension_rejected(self):
        (self.root / ".git").write_text("gitdir: elsewhere")
        with self.assertRaises(ValueError):
            fixture.create_fixture(self.root / "fixture.avi", 1)
        with self.assertRaises(ValueError):
            fixture.checked_destination(self.root / "fixture.mp4")

    def test_symlink_output_rejected(self):
        linked = self.root / "linked"
        other = self.root / "other"
        other.mkdir()
        try:
            linked.symlink_to(other, target_is_directory=True)
        except OSError:
            self.skipTest("This session cannot create directory symlinks")
        with self.assertRaises(ValueError):
            fixture.create_fixture(linked / "fixture.avi", 1)
        self.assertEqual(list(other.iterdir()), [])


if __name__ == "__main__":
    unittest.main()

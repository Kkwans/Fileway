"""Offline deterministic media and harness contracts; never claim native playback."""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "New-ProgressivePlaybackFixture.py"
SPEC = importlib.util.spec_from_file_location("progressive_fixture", SCRIPT)
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)
EXPECTED_SHA256 = "c027db76c4358ca75888c711fe07de1ec5f195428908bdc4a8012346442481a4"


class ProgressiveFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="Fileway Progressive Fixture ", dir=os.environ.get("FILEWAY_MPV_CHECK_TMP"))
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_fixed_front_header_frame_records_and_hash(self):
        stream = io.BytesIO()
        fixture.write_fixture(stream)
        data = stream.getvalue()
        self.assertEqual(data[:42], b"YUV4MPEG2 W160 H90 F12:1 Ip A1:1 C420jpeg\n")
        self.assertEqual(len(data), 10370922)
        self.assertEqual(hashlib.sha256(data).hexdigest(), EXPECTED_SHA256)
        for index in range(fixture.FRAME_COUNT):
            start = len(fixture.HEADER) + index * fixture.FRAME_RECORD_BYTES
            self.assertEqual(data[start:start + 6], b"FRAME\n")
            self.assertEqual(data[start + 6:start + fixture.FRAME_RECORD_BYTES], fixture.render_frame(index))
        # Both admission prefixes end on complete video-frame boundaries, and
        # neither includes the tail or all source bytes.
        self.assertEqual(fixture.prefix_bytes(8), 2074218)
        self.assertEqual(fixture.prefix_bytes(16), 4148394)
        self.assertLess(fixture.prefix_bytes(8), fixture.prefix_bytes(16))
        self.assertLess(fixture.prefix_bytes(16), len(data))

    def test_actual_luma_frames_change_and_encode_their_index(self):
        for index in (0, 1, 12, 95, 96, 191, 192, 479):
            frame = fixture.render_frame(index)
            self.assertEqual(len(frame), 21600)
            self.assertEqual(frame[14400:], b"\x80" * 7200)
            number = 0
            for bit in range(16):
                value = frame[45 * fixture.WIDTH + 50 + 4 * bit]
                self.assertIn(value, (16, 235))
                number |= (value == 235) << bit
            self.assertEqual(number, index)
        self.assertNotEqual(fixture.render_frame(0), fixture.render_frame(1))
        for invalid in (-1, 480):
            with self.assertRaises(ValueError):
                fixture.render_frame(invalid)

    def test_new_external_file_manifest_contains_only_synthetic_metadata(self):
        destination = self.root / "sample video.y4m"
        manifest = fixture.create_fixture(destination)
        self.assertEqual(manifest["sha256"], EXPECTED_SHA256)
        self.assertEqual(manifest["byteLength"], destination.stat().st_size)
        text = destination.with_name(destination.name + ".fixture.json").read_text()
        self.assertEqual(json.loads(text), manifest)
        self.assertNotIn(str(self.root), text)
        self.assertEqual(manifest["container"], "yuv4mpegpipe")

    def test_existing_file_manifest_or_late_arrival_are_preserved(self):
        for name in ("sample.y4m", "sample.y4m.fixture.json"):
            existing = self.root / name
            existing.write_bytes(b"preserve")
            with self.assertRaises(FileExistsError):
                fixture.create_fixture(self.root / "sample.y4m")
            self.assertEqual(existing.read_bytes(), b"preserve")
            existing.unlink()
        activate = fixture.activate
        def late_arrival(temporary, destination):
            destination.write_bytes(b"late-writer")
            activate(temporary, destination)
        with mock.patch.object(fixture, "activate", side_effect=late_arrival), self.assertRaises(FileExistsError):
            fixture.create_fixture(self.root / "late.y4m")
        self.assertEqual((self.root / "late.y4m").read_bytes(), b"late-writer")

    def test_git_checkout_and_wrong_extension_fail_closed(self):
        (self.root / ".git").write_text("gitdir: external")
        with self.assertRaises(ValueError):
            fixture.create_fixture(self.root / "sample.y4m")
        (self.root / ".git").unlink()
        with self.assertRaises(ValueError):
            fixture.create_fixture(self.root / "sample.avi")

    def test_symlink_path_is_rejected(self):
        directory = self.root / "real"
        directory.mkdir()
        link = self.root / "link"
        try:
            link.symlink_to(directory, target_is_directory=True)
        except OSError:
            self.skipTest("Directory symlinks are unavailable to this test account")
        with self.assertRaises(ValueError):
            fixture.create_fixture(link / "sample.y4m")

    def test_native_harness_uses_fixed_fixture_and_real_production_components(self):
        windows = SCRIPT.parent.parent
        sources = windows / "tests" / "Fileway.ProgressivePlaybackHarness"
        inputs = (sources / "HarnessInputs.cs").read_text()
        native = (sources / "NativePlaybackProbe.cs").read_text()
        program = (sources / "Program.cs").read_text()
        self.assertIn(EXPECTED_SHA256, inputs)
        self.assertIn("new WindowsDownloadFileAccess()", program)
        self.assertIn("AcquireReadLeaseAsync", program)
        self.assertIn("new MpvDownloadStreamBridge(reader)", native)
        self.assertIn("_bridge.Register(_native, _handle)", native)
        self.assertIn("_native.Destroy(_handle)", native)
        self.assertLess(native.index("_native.Destroy(_handle)"), native.index("_bridge.ReleaseAfterDestroyAsync()"))
        self.assertIn("visiblePixelsVerified = false", program)
        self.assertIn("generalContainerPrefixSupportVerified = false", program)
        self.assertNotIn("Skip", native)
        self.assertNotIn("GetEnvironmentVariable", native)
        self.assertNotIn("VirtualProtect", native)
        self.assertNotIn("SetProcessMitigationPolicy", native)
        self.assertNotIn("File.Open", program)  # The growing partial is never reopened by pathname.


if __name__ == "__main__":
    unittest.main()

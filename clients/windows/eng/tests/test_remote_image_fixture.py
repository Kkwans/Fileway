"""Actual deterministic PNG generator regression tests; standard library only."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

SCRIPT = Path(__file__).resolve().parents[1] / 'New-RemoteImageSmokeFixture.py'
spec = importlib.util.spec_from_file_location('remote_image_fixture', SCRIPT)
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
HASH = '5a05232336535f96c4c698007a8e562019af15240b901fc3ecd4908b16f6874c'

class FixtureTests(unittest.TestCase):
    def test_stable_bytes_and_all_png_checksums(self):
        data = fixture.fixture_bytes()
        self.assertEqual(data, fixture.fixture_bytes())
        self.assertEqual(len(data), 461223)
        self.assertEqual(hashlib.sha256(data).hexdigest(), HASH)
        self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
        offset, chunks = 8, []
        while offset < len(data):
            size = struct.unpack_from('>I', data, offset)[0]
            kind, payload = data[offset + 4:offset + 8], data[offset + 8:offset + 8 + size]
            self.assertEqual(struct.unpack_from('>I', data, offset + 8 + size)[0], zlib.crc32(kind + payload))
            chunks.append((kind, payload)); offset += size + 12
        self.assertEqual(offset, len(data))
        self.assertEqual([kind for kind, _ in chunks], [b'IHDR', b'IDAT', b'IEND'])
        self.assertEqual(struct.unpack('>IIBBBBB', chunks[0][1]), (480, 320, 8, 2, 0, 0, 0))
        pixels = zlib.decompress(chunks[1][1])
        row = b'\0' + b'\xff\0\0' * 160 + b'\0\xff\0' * 160 + b'\0\0\xff' * 160
        self.assertEqual(pixels, row * 320)
        self.assertEqual(chunks[2][1], b'')

    def test_absolute_output_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.png'
            result = fixture.write_fixture(path)
            self.assertEqual(set(result), {'fixture', 'width', 'height', 'bytes', 'sha256'})
            self.assertEqual(result['sha256'], HASH)
            with self.assertRaises(FileExistsError): fixture.write_fixture(path)
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), HASH)
        with self.assertRaises(ValueError): fixture.write_fixture('relative.png')

    def test_cli_does_not_report_private_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'private.png'
            env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
            args = [sys.executable, str(SCRIPT), '--output', str(path)]
            result = subprocess.run(args, capture_output=True, text=True, env=env)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(json.loads(result.stdout)['sha256'], HASH)
            self.assertNotIn(directory, result.stdout + result.stderr)
            result = subprocess.run(args, capture_output=True, text=True, env=env)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stderr, 'fixture_exists\n')
            self.assertEqual(result.stdout, '')

if __name__ == '__main__': unittest.main()

import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location('tv_audio_package', Path(__file__).resolve().parents[1] / 'package-tv-audio.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def elf(abi, alignment=16384):
    wide = abi in ('arm64-v8a', 'x86_64')
    data = bytearray(128)
    data[:6] = b'\x7fELF' + bytes([2 if wide else 1, 1])
    struct.pack_into('<HH', data, 16, 3, {'armeabi-v7a':40,'arm64-v8a':183,'x86':3,'x86_64':62}[abi])
    struct.pack_into('<Q' if wide else '<I',data,32 if wide else 28,64)
    struct.pack_into('<HH',data,54 if wide else 42,56 if wide else 32,1)
    struct.pack_into('<I',data,64,1)
    struct.pack_into('<Q' if wide else '<I',data,64+(48 if wide else 28),alignment)
    return data


class NativePackageTest(unittest.TestCase):
    def test_admits_both_arm_and_emulator_elf_classes(self):
        for abi in ('armeabi-v7a','arm64-v8a','x86','x86_64'):
            with self.subTest(abi=abi): module.verify_elf(elf(abi),abi)
    def test_rejects_wrong_abi_and_unaligned_segments(self):
        for data, abi in ((elf('x86'),'armeabi-v7a'),(elf('arm64-v8a',4096),'arm64-v8a'),(elf('x86',4096),'x86')):
            with self.assertRaises(ValueError): module.verify_elf(data,abi)
    def test_rejects_truncated_and_malformed_headers(self):
        for data in (elf('x86')[:40],b'not an ELF library'*8):
            with self.assertRaises(ValueError):module.verify_elf(data,'x86')


if __name__ == '__main__': unittest.main()

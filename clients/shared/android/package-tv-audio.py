#!/usr/bin/env python3
"""Verify Android API28 audio AARs, including ELF32 and ELF64 load alignment."""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_elf(data, abi):
    expected = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183), 'x86': (1, 3), 'x86_64': (2, 62)}[abi]
    if len(data) < 64 or data[:4] != b'\x7fELF' or data[4] != expected[0] or data[5] != 1:
        raise ValueError('ELF class/encoding mismatch: ' + abi)
    if struct.unpack_from('<HH', data, 16) != (3, expected[1]):
        raise ValueError('ELF shared object/machine mismatch: ' + abi)
    wide = data[4] == 2
    start = struct.unpack_from('<Q' if wide else '<I', data, 32 if wide else 28)[0]
    size, count = struct.unpack_from('<HH', data, 54 if wide else 42)
    if size < (56 if wide else 32) or not count or start + size * count > len(data):
        raise ValueError('Malformed ELF program header table')
    loads = 0
    for index in range(count):
        at = start + size * index
        if struct.unpack_from('<I', data, at)[0] != 1:
            continue
        loads += 1
        offset, address = struct.unpack_from('<QQ' if wide else '<II', data, at + (8 if wide else 4))
        alignment = struct.unpack_from('<Q' if wide else '<I', data, at + (48 if wide else 28))[0]
        if alignment < 16384 or (offset - address) % 16384:
            raise ValueError('ELF load segment is not 16KiB compatible')
    if not loads:
        raise ValueError('ELF has no loadable segments')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--aar', type=Path, required=True)
    parser.add_argument('--work-root', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    if not args.output_dir.is_absolute() or args.output_dir.resolve() == Path.home():
        parser.error('Explicit owned output directory required')
    work, output = args.work_root.resolve(), args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    lock = json.loads((ROOT / 'native/media3-ffmpeg-tv.lock.json').read_bytes())
    info = json.loads((work / 'build-info.json').read_bytes())
    if info['inputLockSha256'] != digest(ROOT / 'native/media3-ffmpeg-tv.lock.json') or info['recipeSha256'] != digest(ROOT / 'build-tv-audio.py'):
        raise ValueError('TV native recipe/lock drift')
    windows = os.name == 'nt' or platform.system().startswith('MSYS_NT-')
    readelf = args.ndk / ('toolchains/llvm/prebuilt/' + ('windows-x86_64' if windows else 'linux-x86_64') + '/bin/' + ('llvm-readelf.exe' if windows else 'llvm-readelf'))
    libraries = []
    with zipfile.ZipFile(args.aar) as archive, tempfile.TemporaryDirectory(dir=output) as folder:
        expected = {'jni/' + abi + '/libffmpegJNI.so' for abi in lock['android']['abis']}
        if {name for name in archive.namelist() if name.endswith('.so')} != expected:
            raise ValueError('Native audio ABI closure mismatch')
        for abi in lock['android']['abis']:
            data = archive.read('jni/' + abi + '/libffmpegJNI.so')
            verify_elf(data, abi)
            library = Path(folder) / (abi + '.so')
            library.write_bytes(data)
            symbols = subprocess.check_output([str(readelf), '--dyn-syms', '--wide', str(library)], text=True)
            names = [line.split()[-1] for line in symbols.splitlines() if line.split() and line.split()[0].endswith(':')]
            if 'JNI_OnLoad' not in names or any(name.startswith(('av_', 'avcodec_', 'avpriv_', 'swr_', 'ff_')) for name in names):
                raise ValueError('FFmpeg JNI symbol isolation failed')
            libraries.append(dict(abi=abi, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    aar = output / 'fileway-tv-audio.aar'
    shutil.copyfile(args.aar, aar)
    source = output / 'fileway-tv-audio-sources.zip'
    with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as bundle:
        for entry in sorted((work / 'sources').rglob('*')):
            if entry.is_file():
                bundle.write(entry, entry.relative_to(work))
        bundle.write(ROOT / 'native/media3-ffmpeg-tv.lock.json', 'input-lock.json')
        bundle.write(ROOT / 'build-tv-audio.py', 'build-tv-audio.py')
        bundle.write(ROOT / 'package-tv-audio.py', 'package-tv-audio.py')
        bundle.write(ROOT.parent.parent / 'android/scripts/prepare-ffmpeg-probe.py', 'phone-prepare-ffmpeg-probe.py')
    manifest = dict(schema=1, packagingVerified=True, media3Version='1.11.1', minSdk=28, abis=lock['android']['abis'],
                    inputLockSha256=digest(ROOT / 'native/media3-ffmpeg-tv.lock.json'), recipeSha256=digest(ROOT / 'build-tv-audio.py'),
                    sharedRecipeSha256=info['sharedRecipeSha256'], packageRecipeSha256=digest(Path(__file__)),
                    aarFile=aar.name, aarSha256=digest(aar), sourceFile=source.name, sourceSha256=digest(source), libraries=libraries)
    (output / 'tv-audio.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()

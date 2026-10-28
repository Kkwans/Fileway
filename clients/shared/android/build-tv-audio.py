#!/usr/bin/env python3
"""Compile TV audio extensions using the maintained FFmpeg source/verifier recipe."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parent
LOCK = ROOT / 'native/media3-ffmpeg-tv.lock.json'
PHONE_RECIPE = ROOT.parent.parent / 'android/scripts/prepare-ffmpeg-probe.py'


def recipe():
    spec = importlib.util.spec_from_file_location('fileway_ffmpeg_recipe', PHONE_RECIPE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-root', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=3)
    args = parser.parse_args()
    if not args.work_root.is_absolute() or args.work_root.resolve() == Path.home() or len(args.work_root.parts) < 4:
        parser.error('Use an explicit owned native build directory')
    msys = platform.system().startswith('MSYS_NT-')
    if platform.machine().lower() not in ('x86_64', 'amd64') or not (platform.system() == 'Linux' or msys):
        parser.error('Use an existing Linux x86_64 host or MSYS2 x86_64 Python with the matching NDK host binaries')
    if not 1 <= args.jobs <= 8:
        parser.error('jobs must be 1..8')
    work = args.work_root.resolve()
    base = recipe()
    payload = LOCK.read_bytes()
    lock = json.loads(payload)
    work.mkdir(parents=True, exist_ok=True)
    with (work / '.build.lock').open('a+') as mutex:
        fcntl.flock(mutex, fcntl.LOCK_EX | fcntl.LOCK_NB)
        base.write_owned(work / 'input-lock.json', payload)
        for entry in lock['media3']['files']:
            relative = Path(entry['path'])
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Invalid locked source path')
            base.download(entry, work / 'sources' / relative)
        archive = work / 'downloads' / ('ffmpeg-' + lock['ffmpeg']['version'] + '.tar.xz')
        base.download(lock['ffmpeg'], archive)
        base.extract_regular(archive, work / 'sources/upstream', lock['ffmpeg']['archiveRoot'])
        source = work / 'sources/upstream' / lock['ffmpeg']['archiveRoot']
        base.write_owned(work / 'sources/licenses/ffmpeg-LGPL-2.1.txt', (source / 'COPYING.LGPLv2.1').read_bytes())
        ndk = args.ndk.resolve()
        if f"Pkg.Revision = {lock['android']['ndk']}" not in (ndk / 'source.properties').read_text():
            raise ValueError('Installed NDK does not match the pinned revision')
        notices = [entry for entry in ndk.glob('NOTICE*') if entry.is_file()]
        if not notices:
            raise ValueError('Installed NDK lacks redistribution notices')
        for notice in notices:
            base.write_owned(work / 'sources/licenses' / ('android-ndk-' + notice.name + '.txt'), notice.read_bytes())
        host = 'windows-x86_64' if msys else 'linux-x86_64'
        toolchain = ndk / ('toolchains/llvm/prebuilt/' + host + '/bin')
        if not (toolchain / ('clang.exe' if msys else 'clang')).is_file():
            raise ValueError('Pinned NDK compiler unavailable')
        for abi in lock['android']['abis']:
            output = work / 'build' / abi
            prefix = work / 'prefix' / abi
            output.mkdir(parents=True, exist_ok=True)
            # Reuse flags and license checks from the phone; override only ISA/ABI.
            equivalent = 'arm64-v8a' if abi == 'armeabi-v7a' else 'x86_64' if abi == 'x86' else abi
            command = base.configure_arguments(source, prefix, toolchain, equivalent, lock)
            if abi == 'armeabi-v7a':
                command = [x.replace('--arch=aarch64', '--arch=arm').replace('aarch64-linux-android28-', 'armv7a-linux-androideabi28-') for x in command]
                command.append('--cpu=armv7-a')
            elif abi == 'x86':
                command = [x.replace('--arch=x86_64', '--arch=x86').replace('x86_64-linux-android28-', 'i686-linux-android28-') for x in command]
            if msys:
                # The NDK supplies POSIX clang target wrappers beside its Windows
                # executables. Resolve LLVM archive tools explicitly as .exe.
                command = [value + '.exe' if value.startswith(('--ar=', '--nm=', '--ranlib=', '--strip=')) else value for value in command]
            subprocess.run(command, cwd=output, check=True)
            base.verify_configuration(output, lock)
            subprocess.run(['make', '-j' + str(args.jobs)], cwd=output, check=True)
            subprocess.run(['make', 'install'], cwd=output, check=True)
        info = dict(schema=1, inputLockSha256=hashlib.sha256(payload).hexdigest(),
                    recipeSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    sharedRecipeSha256=hashlib.sha256(PHONE_RECIPE.read_bytes()).hexdigest(),
                    minSdk=28, abis=lock['android']['abis'], host=platform.system() + ' ' + platform.machine(), ndk=lock['android']['ndk'],
                    compilerVersion=subprocess.check_output([str(toolchain / ('clang.exe' if msys else 'clang')), '--version'], text=True).splitlines()[0])
        base.write_owned(work / 'build-info.json', json.dumps(info, indent=2).encode() + b'\n')


if __name__ == '__main__':
    main()

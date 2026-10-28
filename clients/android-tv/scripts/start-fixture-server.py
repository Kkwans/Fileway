#!/usr/bin/env python3
"""Start the real Fileway backend against owned samples and a private database.

Only copies an explicit set of repository-owned media. Credentials and connection
metadata stay in the caller's private work directory; nothing is published.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--work-root', type=Path, required=True)
    parser.add_argument('--address', default='127.0.0.1')
    parser.add_argument('--client-host')
    parser.add_argument('--port', type=int, default=0)
    args = parser.parse_args()
    if not args.work_root.is_absolute() or args.work_root.resolve() == Path.home() or not args.server.is_file():
        parser.error('Explicit server binary and owned absolute work root required')
    work = args.work_root.resolve()
    gate = Path('/volume2/Common/governance/scripts/check-placement.py')
    if gate.is_file():
        subprocess.run(['python3', str(gate), '--path', str(work), '--operation', 'mkdir', '--kind', 'artifact'], check=True)
    work.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(work, 0o700)
    if (work / 'connection.json').exists():
        parser.error('Existing fixture receipt; inspect and stop its exact process before using a new work root')
    repo = Path(__file__).resolve().parents[3]
    fixtures = work / 'files'
    fixtures.mkdir()
    for name in ['Cinema', 'Music', 'Photos']:
        (fixtures / name).mkdir()
    own = repo / 'clients/android-tv/app/src/androidTest/assets/media'
    common = repo / 'clients/android/app/src/androidTest/assets/media'
    files = {
        own / 'tv-owned-source.mp4': fixtures / 'Cinema' / 'Fileway owned 150s +%# fixture.mp4',
        own / 'tv-timeline.srt': fixtures / 'Cinema' / 'Fileway owned timeline.srt',
        own / 'tv-owned-audio.m4a': fixtures / 'Music' / 'Fileway owned 440Hz.m4a',
        common / 'subtitle-fixture.mkv': fixtures / 'Cinema' / 'Fileway owned ASS font PGS.mkv',
    }
    for source, target in files.items():
        shutil.copyfile(source, target)
    # A small, deterministic owned photo; no external image fetch is involved.
    import struct
    import zlib
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    width, height = 600, 400
    rows = b''.join(b'\0' + bytes((32, 104, 200) if y < height//2 else (236, 178, 95)) * width for y in range(height))
    image = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')
    (fixtures / 'Photos' / 'Fileway owned color blocks.png').write_bytes(image)
    (fixtures / 'Photos' / 'Fileway owned second color blocks.png').write_bytes(image)
    # Isolate subtitle rendering from optional TrueHD audio on the same authored
    # source. Keep the full multi-audio fixture above for decoder validation.
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-i',str(common / 'subtitle-fixture.mkv'),
                    '-map','0:v:0','-map','0:s','-map','0:t','-c','copy','-an',
                    str(fixtures / 'Cinema' / 'Fileway owned subtitle styles.mkv')],check=True)
    large = fixtures / 'Large directory'
    large.mkdir()
    for index in range(2000):
        (large / ('Owned folder %04d with a long readable directory name' % index)).mkdir()
    for source, target in list(files.items()):
        if target.suffix == '.mp4':
            shutil.copyfile(source, target.with_name('Fileway owned very long title with spaces percentages % plus + hash # and Unicode \u6816\u5377 \U0001f3ac.mp4'))
    config = work / 'config.json'
    config.write_text('{}\n')
    database = work / 'fixture.db'
    password = secrets.token_urlsafe(24)
    env = {key: value for key, value in os.environ.items() if not key.startswith('FB_')}
    env['TMPDIR'] = str(work / 'tmp')
    (work / 'tmp').mkdir()
    hashed = subprocess.check_output([str(args.server), '--config', str(config), '--database', str(database), 'hash', password], env=env, text=True).strip()
    env['FB_PASSWORD'] = hashed
    env['FB_USERNAME'] = 'tv-fixture'
    with socket.socket() as check:
        check.bind((args.address, args.port))
        port = check.getsockname()[1]
    command = [str(args.server), '--config', str(config), '--database', str(database), '--root', str(fixtures), '--address', args.address, '--port', str(port),
               '--hlsCacheDir', str(work / 'hls'), '--hlsCacheMaxBytes', str(512 << 20), '--hlsProcessors', '1', '--hlsEncodeThreads', '1',
               '--disableDiscovery=true', '--cacheDir', str(work / 'previews'), '--cacheMaxBytes', str(128 << 20), '--videoPreviewProcessors', '1', '--imageProcessors', '1', '--disableExec=true']
    def interrupted(_number, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    with (work / 'server.log').open('wb') as log:
        child = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                if child.poll() is not None:
                    raise RuntimeError('Owned fixture server exited; inspect its private log')
                try:
                    with urllib.request.urlopen('http://' + args.address + ':' + str(port) + '/health', timeout=1) as response:
                        if response.status == 200:
                            break
                except Exception:
                    time.sleep(.1)
            else:
                raise RuntimeError('Owned fixture server did not become ready')
            receipt = dict(pid=child.pid, executable=str(args.server.resolve()), binarySha256=hashlib.sha256(args.server.read_bytes()).hexdigest(),
                           url='http://' + (args.client_host or args.address) + ':' + str(port), username='tv-fixture', password=password,
                           root=str(fixtures), scope='isolated real backend with repository-owned samples; no production state')
            fd = os.open(work / 'connection.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as stream:
                json.dump(receipt, stream, indent=2)
            print(json.dumps(dict(status='READY', connectionFile=str(work / 'connection.json'), pid=child.pid)), flush=True)
            child.wait()
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()


if __name__ == '__main__':
    main()

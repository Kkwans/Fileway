#!/usr/bin/env python3
"""Run native TV journeys only on an explicitly named, task-owned TV emulator."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', default='fileway-tv-api28')
    parser.add_argument('--connection-file', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--adb', type=Path)
    args = parser.parse_args()
    if hasattr(sys.stdout,"reconfigure"): sys.stdout.reconfigure(encoding="utf-8",errors="replace")
    if not args.serial.startswith('emulator-') or not args.output_dir.is_absolute():
        parser.error('Explicit emulator serial and absolute owned output directory required')
    sdk = os.environ.get('ANDROID_HOME', '')
    adb = str(args.adb or (Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')))
    def command(*values, **kwargs):
        if kwargs.get('text'): kwargs.setdefault('encoding','utf-8'); kwargs.setdefault('errors','replace')
        return subprocess.run([adb, '-s', args.serial, *values], check=True, **kwargs)
    def output(*values):
        return command(*values, capture_output=True, text=True, timeout=20).stdout.strip()
    name = output('emu', 'avd', 'name').splitlines()[0]
    api = output('shell', 'getprop', 'ro.build.version.sdk')
    abi = output('shell', 'getprop', 'ro.product.cpu.abilist')
    if name != args.expected_avd or output('shell', 'getprop', 'ro.kernel.qemu') != '1' or api != '28':
        raise RuntimeError('Emulator identity does not match the owned API28 target')
    if 'android.software.leanback' not in output('shell', 'pm', 'list', 'features'):
        raise RuntimeError('The selected target is not a TV system image')
    connection = json.loads(args.connection_file.read_text())
    url = urlsplit(connection['url'])
    if url.scheme not in ('http', 'https') or not url.hostname or url.query or url.fragment or url.username:
        raise RuntimeError('Invalid fixture service origin')
    if connection.get('username') != 'tv-fixture' or not re.fullmatch(r'[A-Za-z0-9_-]{24,64}', connection.get('password', '')):
        raise RuntimeError('An owned generated fixture account is required')
    destination = args.output_dir.resolve()
    if destination == Path.home() or len(destination.parts) < 4:
        raise RuntimeError('Unsafe evidence output directory')
    destination.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[1]
    command('install', '-r', str(root / 'app/build/outputs/apk/debug/app-debug.apk'), timeout=120)
    command('install', '-r', str(root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'), timeout=120)
    command('logcat', '-c', timeout=20)
    # Do not print the command, credentials, JWTs or connection receipt.
    result = subprocess.run([adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', 'io.github.kkwans.fileway.tv.TvJourneyTest,io.github.kkwans.fileway.tv.TvCacheAndDiscoveryTest', '-e', 'fixture_url', connection['url'],
        '-e', 'fixture_username', connection['username'], '-e', 'fixture_password', connection['password'],
        'io.github.kkwans.fileway.tv.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
    text = result.stdout + result.stderr
    (destination / 'instrumentation.txt').write_text(text,encoding="utf-8")
    logcat = command('logcat', '-d', '-t', '1500', capture_output=True, text=True, timeout=30).stdout
    # Native production logs contain codec information only; redact defensively.
    logcat = logcat.replace(connection['password'], '[REDACTED]')
    logcat = re.sub(r'(?i)(x-auth|authorization)[:= ]+[^\s]+', r'\1 [REDACTED]', logcat)
    (destination / 'logcat.txt').write_text(logcat,encoding="utf-8")
    pull = subprocess.run([adb, '-s', args.serial, 'pull', '/sdcard/Android/data/io.github.kkwans.fileway.tv/files/tv-acceptance', str(destination)], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    (destination / 'evidence-pull.txt').write_text(pull.stdout + pull.stderr,encoding="utf-8")
    count = re.search(r'OK \((\d+) tests?\)', text)
    passed = result.returncode == 0 and pull.returncode == 0 and count is not None and int(count.group(1)) >= 6 and 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text
    receipt = dict(status='PASS' if passed else 'FAIL', serial=args.serial, avd=name, api=api, abi=abi,
                   executedTests=int(count.group(1)) if count else None,
                   evidenceClass='API28 Android TV emulator with isolated real Fileway backend; software video diagnostics; no HDR/audio/physical-device claim')
    (destination / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n',encoding='utf-8')
    print(json.dumps(receipt), flush=True)
    if not passed:
        raise RuntimeError('Native TV journey batch failed; inspect collected evidence before changes')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Install explicit SDK packages without relying on the deprecated CLI wrapper."""
import argparse
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('packages', nargs='+', help='Canonical slash-separated SDK package paths')
    args = parser.parse_args()
    if not args.sdk.is_absolute():
        parser.error('An explicit absolute SDK root is required')
    binary = args.sdk / 'cmdline-tools/latest/bin' / ('android.exe' if os.name == 'nt' else 'android')
    legacy = args.sdk / 'cmdline-tools/latest/bin' / ('sdkmanager.bat' if os.name == 'nt' else 'sdkmanager')
    for package in args.packages:
        if package.startswith('/') or '\\' in package or any(part in ('', '.', '..') for part in package.split('/')):
            parser.error('Invalid SDK package coordinate')
        if binary.is_file():
            command = [str(binary), '--no-metrics', '--sdk=' + str(args.sdk), 'sdk', 'install', '--ignore-outdated-xmls', package]
        else:
            command = [str(legacy), '--sdk_root=' + str(args.sdk), '--install', package.replace('/', ';')]
        subprocess.run(command, input=b'y\n' * 16, check=True)
        # Both the deprecated Windows wrapper and old network failures can exit
        # successfully without installing anything. Verify the requested output.
        path = args.sdk.joinpath(*package.split('/'))
        if not (path / 'package.xml').is_file() and not (path / 'source.properties').is_file():
            raise RuntimeError('SDK package was not installed: ' + package)
        print('VERIFIED SDK package ' + package, flush=True)


if __name__ == '__main__':
    main()

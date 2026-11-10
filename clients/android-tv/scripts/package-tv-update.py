#!/usr/bin/env python3
"""Create the authenticated update manifest for an already signed TV APK.

This writes only to an explicitly chosen existing output directory. It never
creates a signing identity, uploads a release or changes the source APK.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile

PACKAGE = "io.github.kkwans.fileway.tv"
SIGNER = "ad35201bcbbcba94754447da9f6e7f369c2a4264bfd5e179194a3cd0b0a0c4a9"
MAX_APK_BYTES = 128 * 1024 * 1024
ABI_ORDER = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--channel", choices=("stable", "preview"), required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--notes-file", type=Path)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--apksigner-jar", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--signing-properties", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    apk = args.apk.resolve(strict=True)
    output = args.output_dir.resolve(strict=True)
    if not apk.is_file() or not output.is_dir() or not args.signing_properties.is_absolute():
        parser.error("An APK, existing output directory and absolute signing properties are required")
    for tool in (args.aapt, args.apksigner_jar, args.java, args.signing_properties):
        if not tool.is_file():
            parser.error("A specified build tool or signing properties file is unavailable")
    original = apk.stat()
    size = original.st_size
    if not 0 < size <= MAX_APK_BYTES:
        parser.error("TV APK size is outside the update limit")
    tag = re.fullmatch(r"android-tv-(\d{1,6}\.\d{1,6}\.\d{1,6})", args.tag)
    if not tag:
        parser.error("Invalid TV release tag")
    if not re.fullmatch(r"[a-f0-9]{40}", args.source_revision):
        parser.error("An exact 40-character source revision is required")
    if apk.name != f"Fileway-TV-{tag.group(1)}.apk":
        parser.error("APK filename must match the TV release version")

    badging = subprocess.run([str(args.aapt), "dump", "badging", str(apk)], check=True,
                             capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
    minimum = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.MULTILINE)
    if not package or not minimum or package.group(1) != PACKAGE or package.group(3) != tag.group(1):
        parser.error("APK package or actual version does not match the TV release")
    version_code, min_sdk = int(package.group(2)), int(minimum.group(1))
    if not 0 < version_code < 2**63 or min_sdk != 28:
        parser.error("APK version code or minimum SDK is incompatible")
    certificate_output = subprocess.run(
        [str(args.java), "-jar", str(args.apksigner_jar), "verify", "--min-sdk-version", "28", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    certificates = re.findall(r"certificate SHA-256 digest: ([a-fA-F0-9]{64})", certificate_output)
    if [item.lower() for item in certificates] != [SIGNER]:
        parser.error("APK does not use the original TV signing certificate")

    with ZipFile(apk) as archive:
        names = set(archive.namelist())
        native_abis = {name.split("/")[1] for name in names
                       if name.startswith("lib/") and name.endswith(".so") and name.count("/") == 2}
    if not {"arm64-v8a", "armeabi-v7a"}.issubset(native_abis) or native_abis - set(ABI_ORDER):
        parser.error("TV APK must contain both ARM ABIs and only supported native ABIs")
    required_libs = ("libnfbbridge.so", "libnfbcore.so", "libass.so", "libasskt.so", "libffmpegJNI.so")
    if any(f"lib/{abi}/{library}" not in names for abi in native_abis for library in required_libs):
        parser.error("A TV native ABI is missing a required playback library")
    if args.notes_file and args.notes_file.stat().st_size > 16 * 1024:
        parser.error("Release notes exceed the update manifest limit")
    notes = args.notes_file.read_text(encoding="utf-8") if args.notes_file else ""
    if len(notes.encode("utf-8")) > 16 * 1024:
        parser.error("Release notes exceed the update manifest limit")
    manifest = {
        "schema": 1, "product": "android-tv", "applicationId": PACKAGE,
        "tag": args.tag, "channel": args.channel, "versionName": package.group(3),
        "versionCode": version_code, "minSdk": min_sdk,
        "abis": [abi for abi in ABI_ORDER if abi in native_abis], "apkName": apk.name,
        "size": size, "sha256": digest(apk), "signerSha256": SIGNER,
        "sourceRevision": args.source_revision, "notes": notes,
    }
    current = apk.stat()
    if (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns) != (
            original.st_dev, original.st_ino, original.st_size, original.st_mtime_ns):
        parser.error("APK changed during update metadata inspection")
    payload = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(payload) > 64 * 1024:
        parser.error("Update manifest is too large")
    destinations = [output / "tv-update-v1.json", output / "tv-update-v1.sig"]
    if any(path.exists() for path in destinations):
        parser.error("Update manifest output already exists")
    created = []
    try:
        with tempfile.TemporaryDirectory(prefix=".tv-update-", dir=output) as temp:
            temporary = Path(temp)
            source = temporary / "manifest.json"
            signature = temporary / "manifest.sig"
            source.write_bytes(payload)
            signer = Path(__file__).resolve().with_name("SignTvUpdateManifest.java")
            subprocess.run([str(args.java), str(signer), str(args.signing_properties), str(source), str(signature)],
                           check=True, capture_output=True)
            signature_bytes = signature.read_bytes()
            if not 0 < len(signature_bytes) <= 1024:
                raise ValueError("Invalid update manifest signature")
            for target, data in zip(destinations, (payload, signature_bytes)):
                descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                created.append(target)
                with os.fdopen(descriptor, "wb") as stream:
                    stream.write(data)
    except Exception:
        for path in created:
            path.unlink(missing_ok=True)
        raise
    print(json.dumps({"manifest": destinations[0].name, "signature": destinations[1].name,
                      "apkSha256": manifest["sha256"], "versionCode": version_code}, ensure_ascii=False))


if __name__ == "__main__":
    main()

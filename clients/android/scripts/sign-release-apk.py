#!/usr/bin/env python3
"""Canonical Fileway Android APK release signing & verification utility.

Supports either:
  1. Remote signing over SSH (keeps private keystore strictly off the build workstation)
  2. Local signing via a private android-signing.properties file outside Git

Always enforces:
  - 16 KiB zipalign verification (`zipalign -c -P 16 4`)
  - Exact SHA-256 certificate match against Fileway's canonical Android signing identity
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile

CANONICAL_SIGNER_SHA256 = "e29020f3608ffbc943d7c1ff69d07405975b25a649124ad81dfe2ca6ea096ab2"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_sdk_build_tools(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
        if path.is_dir():
            return path
        raise FileNotFoundError(f"build-tools directory not found: {path}")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidate = Path(sdk) / "build-tools" / "36.0.0"
        if candidate.is_dir():
            return candidate
        bt_root = Path(sdk) / "build-tools"
        if bt_root.is_dir():
            versions = sorted(bt_root.iterdir(), reverse=True)
            if versions:
                return versions[0]
    raise FileNotFoundError("Set ANDROID_HOME/ANDROID_SDK_ROOT or pass --build-tools")


def find_java(explicit: str | None) -> str:
    if explicit:
        return explicit
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        exe = "java.exe" if os.name == "nt" else "java"
        candidate = Path(java_home) / "bin" / exe
        if candidate.is_file():
            return str(candidate)
    resolved = shutil.which("java")
    if resolved:
        return resolved
    raise FileNotFoundError("Set JAVA_HOME or pass --java")


def verify_apk(apk: Path, java: str, build_tools: Path, expected_signer: str) -> None:
    apksigner_jar = build_tools / "lib" / "apksigner.jar"
    zipalign_bin = build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")

    cert_out = subprocess.check_output(
        [java, "-jar", str(apksigner_jar), "verify", "--print-certs", str(apk)],
        text=True,
        timeout=60,
    )
    found = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", cert_out)
    if found != [expected_signer]:
        raise RuntimeError(
            f"Signature mismatch! Expected [{expected_signer}], got {found}. "
            "Refusing to output a mis-signed APK."
        )
    subprocess.run(
        [str(zipalign_bin), "-c", "-P", "16", "4", str(apk)],
        check=True,
        timeout=60,
    )


def ensure_aligned(input_apk: Path, aligned_apk: Path, build_tools: Path) -> Path:
    zipalign_bin = build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
    check = subprocess.run(
        [str(zipalign_bin), "-c", "-P", "16", "4", str(input_apk)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        timeout=60,
    )
    if check.returncode == 0:
        return input_apk
    subprocess.run(
        [str(zipalign_bin), "-f", "-P", "16", "4", str(input_apk), str(aligned_apk)],
        check=True,
        timeout=120,
    )
    return aligned_apk


def sign_via_ssh(
    aligned_apk: Path,
    output_apk: Path,
    ssh_host: str,
    remote_key_dir: str,
    remote_apksigner_jar: str,
    remote_work_dir: str,
    key_alias: str,
    expected_signer: str,
    remote_gate: str | None,
) -> None:
    input_sha = sha256_file(aligned_apk)
    tag = secrets.token_hex(8)
    gate_mkdir = f'python3 "{remote_gate}" --path "$stage" --operation mkdir --kind artifact >&2\n' if remote_gate else ""
    gate_in = f'python3 "{remote_gate}" --path "$stage/unsigned.apk" --operation create-file --kind artifact >&2\n' if remote_gate else ""
    gate_out = f'python3 "{remote_gate}" --path "$stage/signed.apk" --operation create-file --kind artifact >&2\n' if remote_gate else ""

    remote_script = (
        "set -eu\n"
        "umask 077\n"
        f'work="{remote_work_dir}"\n'
        f'key="{remote_key_dir}"\n'
        f'jar="{remote_apksigner_jar}"\n'
        'test -d "$work"\n'
        'test -r "$key/release.p12"\n'
        'test -r "$key/release.password"\n'
        'test -r "$jar"\n'
        f'stage="$work/phone-sign-{tag}"\n'
        f"{gate_mkdir}"
        'mkdir "$stage"\n'
        'cleanup() { rm -f "$stage/unsigned.apk" "$stage/signed.apk"; rmdir "$stage"; }\n'
        "trap cleanup EXIT\n"
        f"{gate_in}"
        f"{gate_out}"
        'cat > "$stage/unsigned.apk"\n'
        'actual="$(sha256sum "$stage/unsigned.apk")"\n'
        f'test "${{actual%% *}}" = "{input_sha}"\n'
        f'java -jar "$jar" sign --ks "$key/release.p12" --ks-pass "file:$key/release.password" '
        f'--ks-key-alias "{key_alias}" --v4-signing-enabled false '
        '--out "$stage/signed.apk" "$stage/unsigned.apk" >&2\n'
        f'java -jar "$jar" verify --print-certs "$stage/signed.apk" | grep -F "Signer #1 certificate SHA-256 digest: {expected_signer}" >&2\n'
        'cat "$stage/signed.apk"\n'
    )

    with aligned_apk.open("rb") as src, output_apk.open("wb") as dst:
        proc = subprocess.run(
            ["ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", ssh_host, remote_script],
            stdin=src,
            stdout=dst,
            stderr=subprocess.PIPE,
            timeout=240,
        )
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", errors="replace")
        raise RuntimeError(f"Remote SSH signing failed (exit {proc.returncode}):\n{err}")


def parse_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def sign_local(
    aligned_apk: Path,
    output_apk: Path,
    signing_file: Path,
    java: str,
    build_tools: Path,
) -> None:
    props = parse_properties(signing_file)
    for required in ("storeFile", "storePassword", "keyAlias", "keyPassword"):
        if not props.get(required):
            raise ValueError(f"Missing required property '{required}' in {signing_file}")
    apksigner_jar = build_tools / "lib" / "apksigner.jar"
    subprocess.run(
        [
            java,
            "-jar",
            str(apksigner_jar),
            "sign",
            "--ks",
            props["storeFile"],
            "--ks-pass",
            f"pass:{props['storePassword']}",
            "--ks-key-alias",
            props["keyAlias"],
            "--key-pass",
            f"pass:{props['keyPassword']}",
            "--v4-signing-enabled",
            "false",
            "--out",
            str(output_apk),
            str(aligned_apk),
        ],
        check=True,
        timeout=120,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Sign and verify a Fileway Android release APK.")
    parser.add_argument("--input", required=True, help="Path to unsigned (or prebuilt) APK.")
    parser.add_argument("--output", required=True, help="Destination path for signed APK.")
    parser.add_argument("--mode", choices=("ssh", "local"), required=True)
    parser.add_argument("--expected-signer", default=CANONICAL_SIGNER_SHA256)
    parser.add_argument("--build-tools", default=None)
    parser.add_argument("--java", default=None)
    # Local mode
    parser.add_argument("--signing-file", default=None)
    # SSH mode
    parser.add_argument("--ssh-host", default=None)
    parser.add_argument("--remote-key-dir", default=None)
    parser.add_argument("--remote-apksigner-jar", default=None)
    parser.add_argument("--remote-work-dir", default=None)
    parser.add_argument("--remote-gate", default=None)
    parser.add_argument("--key-alias", default="nfb-client-release")
    args = parser.parse_args()

    input_apk = Path(args.input).resolve()
    output_apk = Path(args.output).resolve()
    if not input_apk.is_file():
        raise FileNotFoundError(f"Input APK not found: {input_apk}")

    build_tools = find_sdk_build_tools(args.build_tools)
    java = find_java(args.java)
    output_apk.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="fileway-sign-", dir=str(output_apk.parent)) as tmp:
        tmp_dir = Path(tmp)
        aligned_apk = ensure_aligned(input_apk, tmp_dir / "aligned.apk", build_tools)
        candidate = tmp_dir / "signed.apk"

        if args.mode == "ssh":
            for opt in ("ssh_host", "remote_key_dir", "remote_apksigner_jar", "remote_work_dir"):
                if not getattr(args, opt):
                    raise ValueError(f"--{opt.replace('_', '-')} is required in ssh mode")
            sign_via_ssh(
                aligned_apk=aligned_apk,
                output_apk=candidate,
                ssh_host=args.ssh_host,
                remote_key_dir=args.remote_key_dir,
                remote_apksigner_jar=args.remote_apksigner_jar,
                remote_work_dir=args.remote_work_dir,
                key_alias=args.key_alias,
                expected_signer=args.expected_signer,
                remote_gate=args.remote_gate,
            )
        else:
            if not args.signing_file:
                raise ValueError("--signing-file is required in local mode")
            sign_local(aligned_apk, candidate, Path(args.signing_file).resolve(), java, build_tools)

        verify_apk(candidate, java, build_tools, args.expected_signer)
        shutil.move(str(candidate), str(output_apk))

    final_sha = sha256_file(output_apk)
    print(f"SIGNED_OK: {output_apk}")
    print(f"APK_SHA256: {final_sha}")
    print(f"SIGNER_SHA256: {args.expected_signer}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

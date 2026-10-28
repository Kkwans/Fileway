#!/usr/bin/env python3
"""Build an isolated Android Go bridge with the existing pinned NDK/toolchain."""
import argparse
import os
from pathlib import Path
import platform
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--api", type=int, default=28)
    parser.add_argument("--mode", choices=("direct", "embedded"), default="direct")
    parser.add_argument("--abis", default="armeabi-v7a,arm64-v8a")
    args = parser.parse_args()
    if args.api < 28:
        parser.error("The supported Android baseline is API28")
    core = Path(__file__).resolve().parent.parent / "core"
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[platform.system()]
    compiler = args.ndk / "toolchains/llvm/prebuilt" / host / "bin" / ("clang.exe" if os.name == "nt" else "clang")
    if not compiler.is_file():
        parser.error("The specified NDK has no compiler for this build host")
    targets = {"armeabi-v7a": ("arm", "armv7a", "7"), "arm64-v8a": ("arm64", "aarch64", ""),
               "x86": ("386", "i686", ""), "x86_64": ("amd64", "x86_64", "")}
    for abi in args.abis.split(","):
        if abi not in targets:
            parser.error("Unsupported ABI: " + abi)
        arch, target, arm = targets[abi]
        output = core / "generated" / f"api{args.api}-{args.mode}" / abi
        output.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env.update(CGO_ENABLED="1", GOOS="android", GOARCH=arch, GOARM=arm,
                   CC=f'"{compiler}" --target={target}-linux-{("androideabi" if arch == "arm" else "android")}{args.api}')
        command = ["go", "build", "-trimpath", "-buildmode=c-shared",
                   "-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384,-soname,libnfbcore.so"]
        if args.mode == "direct":
            command += ["-tags=fileway_direct"]
            dependencies = subprocess.check_output(["go", "list", "-tags=fileway_direct", "-deps", "./mobile"], cwd=core, env=env, text=True)
            if any(name.startswith("tailscale.com/") or name.endswith("/core/tailnet") for name in dependencies.splitlines()):
                raise RuntimeError("Direct native build contains an embedded network dependency")
        subprocess.run(command + ["-o", str(output / "libnfbcore.so"), "./mobile"], cwd=core, env=env, check=True)
        print(f"BUILT api={args.api} mode={args.mode} abi={abi}", flush=True)


if __name__ == "__main__":
    main()

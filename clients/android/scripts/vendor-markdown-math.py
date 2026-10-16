#!/usr/bin/env python3
"""Vendor fixed official browser assets; no Node/install/build step.

First run --list to inspect the exact selection, then --write with that same
cache directory. Existing unequal files are never overwritten.
"""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request

VERSION = "4.1.3"
PACKAGES = {
    "mathjax": ("https://registry.npmjs.org/mathjax/-/mathjax-4.1.3.tgz",
        "BN/8Pkgn7G1pIDYJqd9md+JHsE/jydSYbyOZnSdSA0WziuVO8mRxdYiWFumkVVly/8U+hm9DpIIoWuvySverzw=="),
    "mathjax-newcm-font": ("https://registry.npmjs.org/@mathjax/mathjax-newcm-font/-/mathjax-newcm-font-4.1.3.tgz",
        "gzAB3dFHilHX1l5x2xUqRL+1jDQt3Fyza1DkEMVXWC4E8SvsGdlgEza47HYi2WhVcgfkvf4zgUGzuhbq3Pjlew=="),
    "mathjax-mhchem-font-extension": ("https://registry.npmjs.org/@mathjax/mathjax-mhchem-font-extension/-/mathjax-mhchem-font-extension-4.1.3.tgz",
        "WFx0IooitEJq1TXc9V941o8eaZdICSPn8FpsIBDdhkMhb0if61jCvk6vtQN84dlLnNEorK011NfpirMkHYygSQ=="),
}
EXTENSIONS = ("ams", "newcommand", "configmacros", "color", "boldsymbol", "mathtools",
    "cancel", "enclose", "mhchem", "textmacros", "braket", "bbox", "amscd", "upgreek")
CORE = {"startup.js", "core.js", "input/tex-base.js", "output/svg.js", "ui/safe.js", "LICENSE", "package.json"}
CORE.update(f"input/tex/extensions/{name}.js" for name in EXTENSIONS)
GATE = "/volume2/Common/governance/scripts/check-placement.py"

def gate(path, kind, operation="create-file"):
    subprocess.run(["python3", GATE, "--path", str(path), "--operation", operation, "--kind", kind],
        check=True, stdout=subprocess.DEVNULL)

def write_exact(path, content, kind):
    gate(path, kind)
    if path.exists():
        if path.read_bytes() != content:
            raise SystemExit(f"Refusing to overwrite different content: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, required=True)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--list", action="store_true")
    modes.add_argument("--write", action="store_true")
    args = parser.parse_args()
    cache = args.cache_dir.resolve()
    if not str(cache).startswith("/volume2/Development/"):
        raise SystemExit("Use an explicit Development task cache")
    gate(cache, "evidence", "mkdir")
    cache.mkdir(parents=True, exist_ok=True)
    if args.write and not (cache / "selected-assets.json").is_file():
        raise SystemExit("Inspect --list inventory before --write")
    output = Path(__file__).resolve().parents[1] / "app/src/main/assets/markdown-math"
    inventory = []
    for name, (url, sri) in PACKAGES.items():
        archive = cache / f"{name}-{VERSION}.tgz"
        data = archive.read_bytes() if archive.exists() else urllib.request.urlopen(url, timeout=60).read()
        if base64.b64encode(hashlib.sha512(data).digest()).decode() != sri:
            raise SystemExit(f"SRI mismatch: {name}")
        write_exact(archive, data, "evidence")
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
            for member in tar.getmembers():
                relative = member.name.removeprefix("package/")
                selected = relative in CORE if name == "mathjax" else (
                    relative in {"svg.js", "package.json", "LICENSE", "NOTICE"} or
                    relative.startswith("svg/dynamic/") and relative.endswith(".js"))
                if not selected:
                    continue
                if not member.isfile() or ".." in Path(relative).parts:
                    raise SystemExit("Invalid archive member")
                content = tar.extractfile(member).read()
                destination = f"mathjax/{relative}" if name == "mathjax" else f"fonts/{name}/{relative}"
                inventory.append({"package": name, "version": VERSION, "archiveUrl": url,
                    "archiveIntegrity": "sha512-" + sri, "path": destination,
                    "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()})
                if args.write:
                    write_exact(output / destination, content, "source")
    inventory.sort(key=lambda item: item["path"])
    manifest = (json.dumps({"schema": 1, "version": VERSION, "files": inventory}, ensure_ascii=False, indent=2) + "\n").encode()
    write_exact(cache / "selected-assets.json", manifest, "evidence")
    if args.write:
        manifest_path = output / "vendor-manifest.json"
        if manifest_path.exists() and manifest_path.read_bytes() != manifest:
            previous = json.loads(manifest_path.read_bytes())
            current = {entry["path"]: entry for entry in inventory}
            # Permit only additive same-version dependencies. Existing upstream
            # files and their provenance remain byte-for-byte unchanged.
            if previous.get("schema") != 1 or previous.get("version") != VERSION or any(
                    current.get(entry["path"]) != entry for entry in previous["files"]):
                raise SystemExit("Refusing non-additive vendor manifest change")
            gate(manifest_path, "source", "edit-file")
            manifest_path.write_bytes(manifest)
        else:
            write_exact(manifest_path, manifest, "source")
    print(json.dumps({"mode": "write" if args.write else "list", "files": len(inventory),
        "bytes": sum(item["bytes"] for item in inventory), "inventory": str(cache / "selected-assets.json")}))

if __name__ == "__main__":
    main()

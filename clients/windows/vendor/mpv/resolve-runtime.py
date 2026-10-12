"""Resolve or restore the MSYS2 UCRT64 libmpv PE runtime without installing MSYS2.

All package bytes, extracted metadata, DLLs, and source bundles live outside Git.
Discovery records the packages selected by PE imports. Locked restoration never
reads a repository index and does not change the input lock.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import urllib.error
import urllib.parse

PIN = "aca9ce7359c63caa33612d160edad059fd931e1c434a87d6ca01f5af015cac76"
PACKAGE = "mingw-w64-ucrt-x86_64-mpv-0.41.0-8-any.pkg.tar.zst"
BASE = "https://mirror.msys2.org/mingw/ucrt64/"


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(url, path, expected=None):
    if is_link(path):
        raise ValueError("Package cache path is a link: " + path.name)
    if path.exists():
        if not path.is_file() or (expected is not None and digest(path) != expected):
            raise RuntimeError("Cached package hash/type mismatch; preserved: " + path.name)
        return path
    temporary = path.with_suffix(path.suffix + ".partial")
    if temporary.exists() or temporary.is_symlink():
        raise RuntimeError("Incomplete package retained; inspect before retrying: " + temporary.name)
    try:
        response = urllib.request.urlopen(url, timeout=60)
    except urllib.error.HTTPError as error:
        if error.code != 404 or not url.startswith(BASE):
            raise
        response = urllib.request.urlopen(url.replace("mirror.msys2.org", "repo.msys2.org", 1), timeout=60)
    with response, open(temporary, "xb") as output:
        shutil.copyfileobj(response, output)
    if expected and digest(temporary) != expected:
        raise RuntimeError("Package hash mismatch: " + path.name)
    # Linking on the same volume creates the final name only if absent. A
    # concurrent cache writer is preserved, even after the download began.
    try:
        os.link(temporary, path)
    except FileExistsError as error:
        raise RuntimeError("Package cache destination appeared; partial retained: " + path.name) from error
    temporary.unlink()
    return path


def fields(path):
    values, key = {}, None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("%") and line.endswith("%"):
            key = line.strip("%")
            values[key] = []
        elif line and key:
            values[key].append(line)
    return values


def inspect_pe(path):
    # Offline contract tests and --help need no installed third-party tools.
    import pefile

    image = pefile.PE(str(path), fast_load=True)
    try:
        image.parse_data_directories(directories=[1, 13])
        names = {entry.dll.decode("ascii").lower() for entry in getattr(image, "DIRECTORY_ENTRY_IMPORT", [])}
        names.update(entry.dll.decode("ascii").lower() for entry in getattr(image, "DIRECTORY_ENTRY_DELAY_IMPORT", []))
        return image.FILE_HEADER.Machine, sorted(names)
    finally:
        image.close()


def imports(path):
    return inspect_pe(path)[1]


def safe_relative(value):
    """Accept one portable relative path, including on case-insensitive Windows."""
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError("Unsafe relative path")
    if value.startswith("./"):
        value = value[2:]
    value = value.removesuffix("/")
    parts = value.split("/")
    for part in parts:
        if (not part or part in (".", "..") or part.endswith((".", " "))
                or any(ord(character) < 32 or ord(character) == 127 or character in '<>:"|?*' for character in part)
                or re.fullmatch(r"(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])", part.split(".", 1)[0].rstrip(" "))):
            raise ValueError("Unsafe relative path: " + value)
    return pathlib.PurePosixPath(*parts)


def is_link(path):
    if os.name == "nt" and not hasattr(path, "is_junction"):
        raise RuntimeError("Windows path validation requires Python 3.12 or newer junction checks")
    return path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction())


def output_path(root, relative):
    path = root
    for part in safe_relative(str(relative)).parts:
        path = path / part
        if is_link(path):
            raise ValueError("Output path is a link: " + str(path))
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("Output path escaped the artifact root")
    return path


def artifact_root(value, lock_path):
    root = pathlib.Path(os.path.abspath(value))
    for path in (root, *root.parents):
        if is_link(path):
            raise ValueError("Artifact root traverses a link: " + str(path))
        git = path / ".git"
        if git.is_file() or (git / "HEAD").is_file():
            raise ValueError("Runtime artifacts must stay outside Git")
    if lock_path.resolve().is_relative_to(root.resolve()):
        raise ValueError("Input lock must be outside the artifact root")
    if root.exists() and not root.is_dir():
        raise ValueError("Artifact root is not a directory")
    return root


def sha256_value(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", value):
        raise ValueError("Lock contains an invalid SHA-256")
    return value.lower()


def dll_name(value):
    path = safe_relative(value)
    if len(path.parts) != 1 or value != path.name or not path.name.lower().endswith(".dll"):
        raise ValueError("Lock DLL must be a basename")
    return path.name.lower()


def validated_lock(lock):
    if not isinstance(lock, dict) or lock.get("architecture") != "ucrt64-x86_64":
        raise ValueError("Only the locked UCRT64 x64 runtime is supported")
    packages, runtime, system = {}, {}, set()
    for key in ("packages", "runtimeFiles", "systemImports"):
        if not isinstance(lock.get(key), list):
            raise ValueError("Lock requires a " + key + " array")
    for item in lock.get("packages", []):
        if not isinstance(item, dict):
            raise ValueError("Lock package must be an object")
        name, version = item.get("name"), item.get("version")
        if (not isinstance(name, str) or not re.fullmatch(r"mingw-w64-ucrt-x86_64-[A-Za-z0-9_.+-]+", name)
                or not isinstance(version, str) or not re.fullmatch(r"[A-Za-z0-9_.+~-]+", version)):
            raise ValueError("Lock contains an invalid package identity")
        if not isinstance(item.get("packageUrl"), str):
            raise ValueError("Lock package URL must be a string")
        url = urllib.parse.urlsplit(item["packageUrl"])
        filenames = {name + "-" + version + "-" + architecture + ".pkg.tar.zst" for architecture in ("any", "x86_64")}
        if (url.scheme != "https" or url.netloc not in ("mirror.msys2.org", "repo.msys2.org")
                or url.query or url.fragment or url.path not in {"/mingw/ucrt64/" + filename for filename in filenames}):
            raise ValueError("Lock contains an unexpected package URL")
        if name.lower() in packages:
            raise ValueError("Lock contains duplicate packages")
        sha256_value(item.get("sha256"))
        packages[name.lower()] = item
    for item in lock.get("runtimeFiles", []):
        if not isinstance(item, dict) or not isinstance(item.get("imports"), list):
            raise ValueError("Lock runtime file must declare an imports array")
        name = dll_name(item.get("file"))
        if name in runtime:
            raise ValueError("Lock contains duplicate runtime DLLs")
        sha256_value(item.get("sha256"))
        dependencies = [dll_name(value) for value in item.get("imports", [])]
        if len(set(dependencies)) != len(dependencies):
            raise ValueError("Lock contains duplicate imports")
        runtime[name] = item
    for value in lock.get("systemImports", []):
        name = dll_name(value)
        if name in system or name in runtime:
            raise ValueError("Lock contains duplicate/conflicting system imports")
        system.add(name)
    mpv = packages.get("mingw-w64-ucrt-x86_64-mpv")
    if not mpv or mpv["version"] != lock.get("mpvVersion") or "libmpv-2.dll" not in runtime:
        raise ValueError("Lock does not identify its mpv runtime")
    reached, used_system, pending = set(), set(), ["libmpv-2.dll"]
    while pending:
        name = pending.pop()
        if name in reached:
            continue
        reached.add(name)
        for value in runtime[name]["imports"]:
            dependency = dll_name(value)
            if dependency in runtime:
                pending.append(dependency)
            elif dependency in system:
                used_system.add(dependency)
            else:
                raise ValueError("Unresolved locked PE import: " + dependency + " from " + name)
    if reached != set(runtime) or used_system != system:
        raise ValueError("Lock includes files outside its mpv import closure")
    return packages, runtime, system


def archive_entries(package):
    result = subprocess.run(["tar.exe", "-tf", str(package)], check=True,
                            capture_output=True, text=True, encoding="utf-8")
    entries, seen = {}, set()
    for raw in result.stdout.splitlines():
        if raw == "./":
            continue
        name = str(safe_relative(raw))
        if name.lower() in seen:
            raise ValueError("Duplicate archive path: " + name)
        seen.add(name.lower())
        if not raw.endswith("/"):
            entries[name] = raw
    return entries


def copy_archive_member(package, member, destination):
    # tar writes only to stdout. Never extract archive paths or symlinks onto
    # the filesystem; even a link member cannot redirect an output write.
    with open(destination, "xb") as output:
        subprocess.run(["tar.exe", "-xOf", str(package), member], check=True, stdout=output)


def write_member(package, member, root, relative):
    destination = output_path(root, relative)
    destination.parent.mkdir(parents=True, exist_ok=True)
    copy_archive_member(package, member, destination)
    return destination


def verify_runtime(directory, runtime, system):
    observed = [dll_name(path.name) for path in directory.iterdir() if path.name.lower().endswith(".dll")]
    if set(observed) != set(runtime) or len(observed) != len(runtime):
        raise RuntimeError("Runtime DLL set differs from the input lock")
    for name, item in runtime.items():
        path = output_path(directory, item["file"])
        if not path.is_file() or digest(path) != sha256_value(item["sha256"]):
            raise RuntimeError("Runtime DLL hash mismatch: " + name)
        machine, dependencies = inspect_pe(path)
        if machine != 0x8664:
            raise RuntimeError("Runtime DLL is not x64: " + name)
        if sorted(dependencies) != sorted(dll_name(value) for value in item["imports"]):
            raise RuntimeError("Runtime DLL imports differ from the input lock: " + name)
    system_root = os.environ.get("SystemRoot")
    if not system_root:
        raise RuntimeError("SystemRoot is required to verify Windows system imports")
    system32 = pathlib.Path(system_root) / "System32"
    for name in system:
        if not name.startswith(("api-ms-", "ext-ms-")) and not (system32 / name).is_file():
            raise RuntimeError("Locked system import is unavailable: " + name)


def promote_runtime(stage, runtime):
    """Atomically move a verified directory without replacing any destination."""
    if os.name == "nt":
        # Windows os.rename refuses an existing destination, including an empty
        # directory; POSIX rename alone does not provide that guarantee.
        os.rename(stage, runtime)
    elif sys.platform.startswith("linux"):
        import ctypes

        library = ctypes.CDLL(None, use_errno=True)
        rename = getattr(library, "renameat2", None)
        if rename is None:
            raise RuntimeError("Atomic no-replace runtime promotion is unavailable; stage retained")
        rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        rename.restype = ctypes.c_int
        if rename(-100, os.fsencode(stage), -100, os.fsencode(runtime), 1) != 0:  # AT_FDCWD, RENAME_NOREPLACE.
            error = ctypes.get_errno()
            raise OSError(error, os.strerror(error), str(stage), None, str(runtime))
    else:
        raise RuntimeError("Atomic no-replace runtime promotion is unsupported; stage retained")


def restore_runtime(root_value, lock_path):
    lock_path = pathlib.Path(lock_path)
    original = lock_path.read_bytes()
    lock = json.loads(original)
    packages, runtime_files, system = validated_lock(lock)
    root = artifact_root(root_value, lock_path)
    cache = output_path(root, "media-packages")
    runtime = output_path(root, "media-runtime/x64")
    if runtime.exists():
        raise RuntimeError("Existing runtime preserved; use a fresh artifact root")
    cache.mkdir(parents=True, exist_ok=True)
    runtime.parent.mkdir(parents=True, exist_ok=True)
    stage = pathlib.Path(tempfile.mkdtemp(prefix="x64.locked-", dir=runtime.parent))
    selected = set()
    for name, item in sorted(packages.items()):
        filename = pathlib.PurePosixPath(urllib.parse.urlsplit(item["packageUrl"]).path).name
        package = output_path(cache, filename)
        download(item["packageUrl"], package, sha256_value(item["sha256"]))
        if digest(package) != sha256_value(item["sha256"]):
            raise RuntimeError("Package hash mismatch: " + filename)
        entries = archive_entries(package)
        for metadata in (".PKGINFO", ".BUILDINFO"):
            if metadata not in entries:
                raise RuntimeError("Package metadata missing: " + name + " " + metadata)
            path = write_member(package, entries[metadata], stage, "provenance/" + name + "/" + metadata)
            if metadata == ".PKGINFO":
                fields = path.read_text(encoding="utf-8").splitlines()
                if ("pkgname = " + item["name"] not in fields or "pkgver = " + item["version"] not in fields):
                    raise RuntimeError("Package metadata identity differs from the input lock: " + name)
        for member, raw in entries.items():
            path = pathlib.PurePosixPath(member)
            if path.parent == pathlib.PurePosixPath("ucrt64/bin") and path.name.lower() in runtime_files:
                basename = path.name.lower()
                if basename in selected:
                    raise RuntimeError("Locked DLL appears in multiple packages: " + basename)
                write_member(package, raw, stage, runtime_files[basename]["file"])
                selected.add(basename)
            elif member.startswith("ucrt64/share/licenses/"):
                write_member(package, raw, stage, "licenses/" + name + "/" + member.removeprefix("ucrt64/share/licenses/"))
    verify_runtime(stage, runtime_files, system)
    if lock_path.read_bytes() != original:
        raise RuntimeError("Input lock changed during restoration; staged runtime retained")
    output_path(stage, "runtime-lock.json").write_bytes(original)
    output_path(stage, "locked-restore.json").write_text(json.dumps({
        "mode": "locked", "sourceLockSha256": hashlib.sha256(original).hexdigest(),
        "packageCount": len(packages), "runtimeFileCount": len(runtime_files),
        "redistributionApproved": False, "sourceArchivesDownloaded": False,
    }, indent=2) + "\n", encoding="utf-8")
    # An unexpected/new destination is never replaced, including another
    # restoration's result. Both failed and interrupted staging trees remain.
    if output_path(root, "media-runtime/x64").exists():
        raise RuntimeError("Runtime destination appeared; staged runtime retained")
    promote_runtime(stage, runtime)
    print("locked runtime", runtime, "dlls", len(runtime_files), "packages", len(packages), flush=True)
    return runtime


def discover_runtime(root, lock_path):
    cache = root / "media-packages"
    runtime = root / "media-runtime" / "x64"
    metadata = cache / "repository-metadata"
    cache.mkdir(parents=True, exist_ok=True)
    runtime.mkdir(parents=True, exist_ok=True)
    database = download(BASE + "ucrt64.files", cache / "ucrt64.files")
    if not metadata.exists():
        metadata.mkdir()
        subprocess.run(["tar.exe", "-xf", str(database), "-C", str(metadata)], check=True)
    index, packages = {}, {}
    for description in metadata.glob("*/desc"):
        item = fields(description)
        name = item["NAME"][0]
        if not name.startswith("mingw-w64-ucrt-x86_64-"):
            continue
        packages[name] = item
        for filename in fields(description.parent / "files").get("FILES", []):
            if filename.lower().startswith("ucrt64/bin/") and filename.lower().endswith(".dll"):
                index[pathlib.PurePosixPath(filename).name.lower()] = (name, filename)
    selected, locations = {}, {}

    def extract(name, item):
        filename = item["FILENAME"][0]
        print("fetch", filename, flush=True)
        package = download(BASE + filename, cache / filename, item["SHA256SUM"][0])
        destination = cache / "extracted" / (name + "-" + item["VERSION"][0])
        if not destination.exists():
            destination.mkdir(parents=True)
            subprocess.run(["tar.exe", "-xf", str(package), "-C", str(destination)], check=True)
        selected[name] = {"name": name, "version": item["VERSION"][0], "packageUrl": BASE + filename,
                          "sha256": item["SHA256SUM"][0], "licenses": item.get("LICENSE", []),
                          "packageBase": item.get("BASE", [name])[0], "metadataDirectory": str(destination)}
        locations[name] = destination
        print("package", name, item["VERSION"][0], flush=True)

    pinned = download(BASE + PACKAGE, cache / PACKAGE, PIN)
    mpv = cache / "mpv"
    if not mpv.exists():
        mpv.mkdir()
        subprocess.run(["tar.exe", "-xf", str(pinned), "-C", str(mpv)], check=True)
    selected["mingw-w64-ucrt-x86_64-mpv"] = {"name": "mingw-w64-ucrt-x86_64-mpv", "version": "0.41.0-8",
        "packageUrl": BASE + PACKAGE, "sha256": PIN, "licenses": ["GPL-2.0-or-later"], "packageBase": "mingw-w64-mpv", "metadataDirectory": str(mpv)}
    locations["mingw-w64-ucrt-x86_64-mpv"] = mpv
    pending = [mpv / "ucrt64" / "bin" / "libmpv-2.dll"]
    resolved, system = {}, set()
    system32 = pathlib.Path(os.environ["SystemRoot"]) / "System32"
    while pending:
        needed = {}
        for dll in pending:
            basename = dll.name.lower()
            if basename in resolved:
                continue
            dependencies = imports(dll)
            shutil.copy2(dll, runtime / dll.name)
            resolved[basename] = {"file": dll.name, "sha256": digest(dll), "imports": dependencies}
            for dependency in dependencies:
                if dependency not in index and (dependency.startswith(("api-ms-", "ext-ms-")) or (system32 / dependency).exists()):
                    system.add(dependency)
                    continue
                if dependency in resolved:
                    continue
                if dependency not in index:
                    raise RuntimeError("Unresolved third-party PE import: " + dependency + " from " + basename)
                name, path = index[dependency]
                needed[dependency] = (name, path)
        missing = {name for name, _ in needed.values() if name not in locations}
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
            futures = [executor.submit(extract, name, packages[name]) for name in sorted(missing)]
            for future in futures:
                future.result()
        pending = [locations[name] / pathlib.PurePosixPath(path) for name, path in needed.values()]
    notices = runtime / "licenses"
    notices.mkdir(exist_ok=True)
    for name, location in locations.items():
        licenses = location / "ucrt64" / "share" / "licenses"
        if licenses.exists():
            shutil.copytree(licenses, notices / name, dirs_exist_ok=True)
        for item in (".PKGINFO", ".BUILDINFO"):
            source = location / item
            if source.exists():
                target = root / "media-provenance" / name
                target.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target / item)
        package = selected[name]
        package["sourceArchiveUrl"] = "https://mirror.msys2.org/mingw/sources/" + package["packageBase"] + "-" + package["version"] + ".src.tar.zst"
        package.pop("metadataDirectory")
    lock = {"mpvVersion": "0.41.0-8", "architecture": "ucrt64-x86_64", "repositoryDatabaseSha256": digest(database),
            "packages": sorted(selected.values(), key=lambda item: item["name"]), "runtimeFiles": sorted(resolved.values(), key=lambda item: item["file"]),
            "systemImports": sorted(system), "redistributionApproved": False, "sourceArchivesDownloaded": False}
    pathlib.Path(lock_path).write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    (runtime / "runtime-lock.json").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print("runtime", runtime, "dlls", len(resolved), "packages", len(selected), flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts-root", required=True)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--lock", help="Discovery OUTPUT lock; resolves against the repository index")
    modes.add_argument("--lock-input", help="Read-only INPUT lock; restores only exact locked packages")
    args = parser.parse_args(argv)
    if args.lock_input:
        restore_runtime(args.artifacts_root, args.lock_input)
    else:
        discover_runtime(pathlib.Path(args.artifacts_root).resolve(), args.lock)


if __name__ == "__main__":
    main()

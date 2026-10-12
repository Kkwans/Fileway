"""Offline runtime-lock safety contracts; these do not prove native playback.

Packages are locally generated uncompressed tar fixtures, not media binaries.
The contract suite uses stdlib archive/PE mocks. One optional test exercises the
platform's existing tar; neither pefile nor a zstd module is needed.
"""
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error


SCRIPT = Path(__file__).resolve().parents[1] / "resolve-runtime.py"
SPEC = importlib.util.spec_from_file_location("fileway_mpv_resolver", SCRIPT)
resolver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(resolver)
RUN = subprocess.run
TAR = shutil.which("tar.exe") or shutil.which("tar")


def sha(value):
    return hashlib.sha256(value).hexdigest()


def fixture_archive(entries):
    value = io.BytesIO()
    with tarfile.open(fileobj=value, mode="w") as archive:
        for name, payload in entries.items():
            item = tarfile.TarInfo(name)
            if isinstance(payload, tuple):
                item.type = tarfile.SYMTYPE
                item.linkname = payload[0]
                archive.addfile(item)
            else:
                item.size = len(payload)
                archive.addfile(item, io.BytesIO(payload))
    return value.getvalue()


class LockedRestoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="Fileway Media Contract ", dir=os.environ.get("FILEWAY_MPV_CHECK_TMP"))
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "artifacts"
        self.lock_path = self.base / "runtime-packages.lock.json"
        self.system_root = self.base / "windows"
        (self.system_root / "System32").mkdir(parents=True)
        (self.system_root / "System32/kernel32.dll").write_bytes(b"synthetic OS file")
        self.name = "mingw-w64-ucrt-x86_64-mpv"
        self.version = "0.41.0-8"
        self.filename = self.name + "-" + self.version + "-any.pkg.tar.zst"
        self.url = resolver.BASE + self.filename
        self.dll = json.dumps({"machine": 0x8664, "imports": ["kernel32.dll"]}).encode()
        self.entries = {
            ".PKGINFO": ("pkgname = " + self.name + "\npkgver = " + self.version + "\n").encode(),
            ".BUILDINFO": b"format = 2\n",
            "ucrt64/bin/libmpv-2.dll": self.dll,
            "ucrt64/share/licenses/mpv/COPYING": b"Synthetic license notice\n",
        }
        self.lock = {
            "architecture": "ucrt64-x86_64", "mpvVersion": self.version,
            "packages": [{"name": self.name, "version": self.version, "packageUrl": self.url,
                          "sha256": "0" * 64, "licenses": ["GPL-2.0-or-later"]}],
            "runtimeFiles": [{"file": "libmpv-2.dll", "sha256": sha(self.dll), "imports": ["kernel32.dll"]}],
            "systemImports": ["kernel32.dll"],
            "redistributionApproved": False, "sourceArchivesDownloaded": False,
        }
        self.repack()
        self.requests = []
        self.tar_calls = []
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {"SystemRoot": str(self.system_root)}).start()
        patch.object(resolver.urllib.request, "urlopen", side_effect=AssertionError("Real network access is forbidden")).start()
        patch.object(resolver, "download", side_effect=self.download).start()
        patch.object(resolver, "inspect_pe", side_effect=self.inspect).start()
        patch.object(resolver.subprocess, "run", side_effect=self.run_tar).start()

    def repack(self):
        self.package_bytes = fixture_archive(self.entries)
        self.lock["packages"][0]["sha256"] = sha(self.package_bytes)
        self.save_lock()

    def save_lock(self):
        self.lock_path.write_bytes((json.dumps(self.lock, indent=2) + "\n").encode())

    def download(self, url, path, expected):
        self.assertEqual(self.url, url)
        self.assertNotIn("ucrt64.files", url)
        self.requests.append((url, expected))
        if path.exists():
            self.assertEqual(expected, resolver.digest(path))
        else:
            path.write_bytes(self.package_bytes)
        return path

    def inspect(self, path):
        value = json.loads(path.read_bytes())
        return value["machine"], value["imports"]

    def run_tar(self, arguments, **kwargs):
        self.assertEqual("tar.exe", arguments[0])
        self.assertIn(arguments[1], ("-tf", "-xOf"))
        self.tar_calls.append(arguments.copy())
        with tarfile.open(arguments[2], "r:") as archive:
            if arguments[1] == "-tf":
                names = [item.name + ("/" if item.isdir() else "") for item in archive.getmembers()]
                return subprocess.CompletedProcess(arguments, 0, "\n".join(names) + "\n", "")
            item = archive.getmember(arguments[3])
            # Streaming a link does not read its filesystem target. Selected
            # DLL links produce no DLL bytes and fail the expected hash.
            if item.isfile():
                with archive.extractfile(item) as content:
                    shutil.copyfileobj(content, kwargs["stdout"])
            return subprocess.CompletedProcess(arguments, 0)

    def restore(self):
        return resolver.restore_runtime(str(self.root), self.lock_path)

    def assert_not_activated(self):
        self.assertFalse((self.root / "media-runtime/x64").exists())

    def test_restores_exact_bytes_metadata_notices_and_unchanged_input(self):
        original = self.lock_path.read_bytes()
        modified = self.lock_path.stat().st_mtime_ns
        runtime = self.restore()
        self.assertEqual(self.root / "media-runtime/x64", runtime)
        self.assertEqual(self.dll, (runtime / "libmpv-2.dll").read_bytes())
        self.assertEqual(original, (runtime / "runtime-lock.json").read_bytes())
        self.assertEqual(original, self.lock_path.read_bytes())
        self.assertEqual(modified, self.lock_path.stat().st_mtime_ns)
        self.assertEqual(self.entries[".PKGINFO"], (runtime / "provenance" / self.name / ".PKGINFO").read_bytes())
        self.assertEqual(b"Synthetic license notice\n", (runtime / "licenses" / self.name / "mpv/COPYING").read_bytes())
        receipt = json.loads((runtime / "locked-restore.json").read_text())
        self.assertEqual(sha(original), receipt["sourceLockSha256"])
        self.assertFalse(receipt["redistributionApproved"])
        self.assertEqual([(self.url, sha(self.package_bytes))], self.requests)

    def test_package_hash_mismatch_retains_stage_and_never_activates(self):
        self.package_bytes += b"changed package"
        with self.assertRaisesRegex(RuntimeError, "Package hash mismatch"):
            self.restore()
        self.assert_not_activated()
        self.assertTrue(list((self.root / "media-runtime").glob("x64.locked-*")))

    def test_dll_hash_mismatch_never_activates(self):
        self.entries["ucrt64/bin/libmpv-2.dll"] += b"changed DLL"
        self.repack()
        with self.assertRaisesRegex(RuntimeError, "DLL hash mismatch"):
            self.restore()
        self.assert_not_activated()

    def test_actual_import_change_never_activates(self):
        changed = json.dumps({"machine": 0x8664, "imports": ["unexpected.dll"]}).encode()
        self.entries["ucrt64/bin/libmpv-2.dll"] = changed
        self.lock["runtimeFiles"][0]["sha256"] = sha(changed)
        self.repack()
        with self.assertRaisesRegex(RuntimeError, "imports differ"):
            self.restore()
        self.assert_not_activated()

    def test_non_x64_dll_never_activates(self):
        changed = json.dumps({"machine": 0x14c, "imports": ["kernel32.dll"]}).encode()
        self.entries["ucrt64/bin/libmpv-2.dll"] = changed
        self.lock["runtimeFiles"][0]["sha256"] = sha(changed)
        self.repack()
        with self.assertRaisesRegex(RuntimeError, "not x64"):
            self.restore()
        self.assert_not_activated()

    def test_missing_dll_never_activates(self):
        del self.entries["ucrt64/bin/libmpv-2.dll"]
        self.repack()
        with self.assertRaisesRegex(RuntimeError, "DLL set differs"):
            self.restore()
        self.assert_not_activated()

    def test_unavailable_windows_system_import_never_activates(self):
        (self.system_root / "System32/kernel32.dll").unlink()
        with self.assertRaisesRegex(RuntimeError, "system import is unavailable"):
            self.restore()
        self.assert_not_activated()

    def test_api_set_imports_need_no_literal_system32_file(self):
        name = "api-ms-win-core-synch-l1-2-0.dll"
        changed = json.dumps({"machine": 0x8664, "imports": [name]}).encode()
        self.entries["ucrt64/bin/libmpv-2.dll"] = changed
        self.lock["runtimeFiles"][0].update(sha256=sha(changed), imports=[name])
        self.lock["systemImports"] = [name]
        self.repack()
        self.assertTrue(self.restore().is_dir())

    def test_package_metadata_identity_must_match_lock(self):
        self.entries[".PKGINFO"] = b"pkgname = other\npkgver = 0.41.0-8\n"
        self.repack()
        with self.assertRaisesRegex(RuntimeError, "metadata identity differs"):
            self.restore()
        self.assert_not_activated()

    def test_both_package_metadata_files_are_required(self):
        for name in (".PKGINFO", ".BUILDINFO"):
            with self.subTest(name=name):
                original = self.entries.pop(name)
                self.repack()
                self.root = self.base / ("missing-" + name[1:])
                with self.assertRaisesRegex(RuntimeError, "metadata missing"):
                    self.restore()
                self.assert_not_activated()
                self.entries[name] = original

    def test_second_restore_preserves_existing_runtime_without_fetching(self):
        runtime = self.restore()
        original = (runtime / "libmpv-2.dll").read_bytes()
        self.requests.clear()
        with self.assertRaisesRegex(RuntimeError, "Existing runtime preserved"):
            self.restore()
        self.assertEqual(original, (runtime / "libmpv-2.dll").read_bytes())
        self.assertEqual([], self.requests)

    def test_input_changes_during_restore_prevent_activation(self):
        original_download = self.download
        def changed_download(*arguments):
            result = original_download(*arguments)
            self.lock_path.write_bytes(self.lock_path.read_bytes() + b" ")
            return result
        resolver.download.side_effect = changed_download
        with self.assertRaisesRegex(RuntimeError, "Input lock changed"):
            self.restore()
        self.assert_not_activated()

    def test_artifact_root_cannot_contain_input_lock(self):
        self.root = self.base
        with self.assertRaisesRegex(ValueError, "Input lock must be outside"):
            self.restore()
        self.assertEqual([], self.requests)

    def test_artifact_root_cannot_be_inside_git(self):
        self.root = SCRIPT.parent / "must-not-be-created"
        with self.assertRaisesRegex(ValueError, "outside Git"):
            self.restore()
        self.assertFalse(self.root.exists())
        self.assertEqual([], self.requests)

    def test_artifact_root_file_is_preserved(self):
        self.root.write_bytes(b"preserve")
        with self.assertRaisesRegex(ValueError, "not a directory"):
            self.restore()
        self.assertEqual(b"preserve", self.root.read_bytes())

    def test_output_links_do_not_redirect_writes(self):
        target = self.base / "outside"
        target.mkdir()
        self.root.mkdir()
        try:
            (self.root / "media-packages").symlink_to(target, target_is_directory=True)
        except OSError:
            self.skipTest("This platform/account cannot create a directory symlink")
        with self.assertRaisesRegex(ValueError, "Output path is a link"):
            self.restore()
        self.assertEqual([], list(target.iterdir()))
        self.assertEqual([], self.requests)

    def test_archive_traversal_is_rejected_without_external_writes(self):
        for name in ("../escape", "/absolute", "ucrt64/bin/../../escape", "ucrt64\\bin\\escape"):
            with self.subTest(name=name):
                self.entries[name] = b"bad"
                self.repack()
                # Each case has a separate cache so an earlier pinned fixture
                # cannot be reused as a different package identity.
                self.root = self.base / ("case-" + str(len(self.requests)))
                with self.assertRaises(ValueError):
                    self.restore()
                self.assert_not_activated()
                self.assertFalse((self.base / "escape").exists())
                del self.entries[name]

    def test_archive_case_collision_is_rejected(self):
        self.entries["ucrt64/bin/LIBMPV-2.DLL"] = self.dll
        self.repack()
        with self.assertRaisesRegex(ValueError, "Duplicate archive path"):
            self.restore()
        self.assert_not_activated()

    def test_selected_archive_symlink_cannot_read_or_write_external_target(self):
        outside = self.base / "outside.dll"
        outside.write_bytes(self.dll)
        self.entries["ucrt64/bin/libmpv-2.dll"] = (str(outside),)
        self.repack()
        with self.assertRaises(RuntimeError):
            self.restore()
        self.assertEqual(self.dll, outside.read_bytes())
        self.assert_not_activated()

    def test_cli_lock_modes_are_mutually_exclusive(self):
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit) as error:
            resolver.main(["--artifacts-root", str(self.root), "--lock", "out.json", "--lock-input", str(self.lock_path)])
        self.assertEqual(2, error.exception.code)
        self.assertEqual([], self.requests)

    def test_cli_dispatches_input_mode_without_discovery(self):
        with patch.object(resolver, "discover_runtime", side_effect=AssertionError("No discovery in locked mode")):
            resolver.main(["--artifacts-root", str(self.root), "--lock-input", str(self.lock_path)])
        self.assertTrue((self.root / "media-runtime/x64").is_dir())

    def test_two_dll_runtime_import_closure_is_restored(self):
        dependency = json.dumps({"machine": 0x8664, "imports": ["kernel32.dll"]}).encode()
        main = json.dumps({"machine": 0x8664, "imports": ["dependency.dll", "kernel32.dll"]}).encode()
        self.entries["ucrt64/bin/libmpv-2.dll"] = main
        self.entries["ucrt64/bin/dependency.dll"] = dependency
        self.lock["runtimeFiles"][0].update(sha256=sha(main), imports=["dependency.dll", "kernel32.dll"])
        self.lock["runtimeFiles"].append({"file": "dependency.dll", "sha256": sha(dependency), "imports": ["kernel32.dll"]})
        self.repack()
        runtime = self.restore()
        self.assertEqual(dependency, (runtime / "dependency.dll").read_bytes())

    def test_duplicate_dll_across_two_locked_packages_is_rejected(self):
        name, version = "mingw-w64-ucrt-x86_64-dependency", "1.0-1"
        filename = name + "-" + version + "-any.pkg.tar.zst"
        url = resolver.BASE + filename
        package = fixture_archive({".PKGINFO": ("pkgname = " + name + "\npkgver = " + version + "\n").encode(),
                                   ".BUILDINFO": b"format = 2\n", "ucrt64/bin/libmpv-2.dll": self.dll})
        self.lock["packages"].append({"name": name, "version": version, "packageUrl": url, "sha256": sha(package)})
        self.save_lock()
        def download(requested_url, path, expected):
            value = package if requested_url == url else self.package_bytes
            self.assertEqual(sha(value), expected)
            path.write_bytes(value)
            return path
        resolver.download.side_effect = download
        with self.assertRaisesRegex(RuntimeError, "appears in multiple packages"):
            self.restore()
        self.assert_not_activated()

    def test_late_destination_is_preserved_and_staged_runtime_is_not_activated(self):
        original_verify = resolver.verify_runtime
        def create_destination(*arguments):
            original_verify(*arguments)
            destination = self.root / "media-runtime/x64"
            destination.mkdir()
            (destination / "preserve.txt").write_bytes(b"different result")
        with patch.object(resolver, "verify_runtime", side_effect=create_destination):
            with self.assertRaisesRegex(RuntimeError, "destination appeared"):
                self.restore()
        self.assertEqual(b"different result", (self.root / "media-runtime/x64/preserve.txt").read_bytes())
        self.assertFalse((self.root / "media-runtime/x64/libmpv-2.dll").exists())

    def test_empty_destination_created_at_atomic_promotion_is_preserved(self):
        original_promote = resolver.promote_runtime
        late_inode = []
        def create_destination(stage, destination):
            destination.mkdir()
            late_inode.append(destination.stat().st_ino)
            original_promote(stage, destination)
        with patch.object(resolver, "promote_runtime", side_effect=create_destination):
            with self.assertRaises(FileExistsError):
                self.restore()
        destination = self.root / "media-runtime/x64"
        self.assertEqual(late_inode[0], destination.stat().st_ino)
        self.assertEqual([], list(destination.iterdir()))
        self.assertTrue(list((self.root / "media-runtime").glob("x64.locked-*")))

    def test_missing_systemroot_never_activates(self):
        with patch.dict(os.environ):
            os.environ.pop("SystemRoot", None)
            with self.assertRaisesRegex(RuntimeError, "SystemRoot is required"):
                self.restore()
        self.assert_not_activated()

    def test_case_changed_archive_dll_keeps_locked_output_spelling(self):
        self.entries["ucrt64/bin/LIBMPV-2.DLL"] = self.entries.pop("ucrt64/bin/libmpv-2.dll")
        self.repack()
        runtime = self.restore()
        self.assertEqual(self.dll, (runtime / "libmpv-2.dll").read_bytes())
        self.assertEqual(["libmpv-2.dll"], [path.name for path in runtime.iterdir() if path.suffix == ".dll"])

    def test_dot_prefixed_archive_members_restore_without_changing_paths(self):
        self.entries = {"./" + name: value for name, value in self.entries.items()}
        self.repack()
        self.assertTrue((self.restore() / "libmpv-2.dll").is_file())


class LockValidationTests(unittest.TestCase):
    def setUp(self):
        self.lock = {
            "architecture": "ucrt64-x86_64", "mpvVersion": "0.41.0-8",
            "packages": [{"name": "mingw-w64-ucrt-x86_64-mpv", "version": "0.41.0-8",
                          "packageUrl": resolver.BASE + "mingw-w64-ucrt-x86_64-mpv-0.41.0-8-any.pkg.tar.zst", "sha256": "a" * 64}],
            "runtimeFiles": [{"file": "libmpv-2.dll", "sha256": "b" * 64, "imports": ["kernel32.dll"]}],
            "systemImports": ["kernel32.dll"],
        }

    def test_committed_lock_has_exact_closed_runtime_graph(self):
        source = SCRIPT.parent / "runtime-packages.lock.json"
        original = source.read_bytes()
        packages, runtime, system = resolver.validated_lock(json.loads(original))
        self.assertEqual(112, len(packages))
        self.assertEqual(140, len(runtime))
        self.assertEqual(49, len(system))
        self.assertEqual(original, source.read_bytes())

    def test_invalid_hashes_and_architecture_are_rejected(self):
        for field, value in (("architecture", "x86"), ("mpvVersion", "different")):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.lock)
                changed[field] = value
                with self.assertRaises(ValueError):
                    resolver.validated_lock(changed)
        for collection in ("packages", "runtimeFiles"):
            for bad in (None, "", "a" * 63, "g" * 64):
                with self.subTest(collection=collection, value=bad):
                    changed = copy.deepcopy(self.lock)
                    changed[collection][0]["sha256"] = bad
                    with self.assertRaises(ValueError):
                        resolver.validated_lock(changed)

    def test_urls_cannot_redirect_credentials_hosts_paths_or_versions(self):
        for url in ("http://mirror.msys2.org/mingw/ucrt64/a.pkg.tar.zst",
                    "https://user:secret@mirror.msys2.org/mingw/ucrt64/a.pkg.tar.zst",
                    "https://evil.test/" + self.lock["packages"][0]["name"] + ".pkg.tar.zst",
                    self.lock["packages"][0]["packageUrl"] + "?token=secret",
                    self.lock["packages"][0]["packageUrl"] + "#fragment",
                    self.lock["packages"][0]["packageUrl"].replace("0.41.0-8", "0.42.0-1"),
                    resolver.BASE + "../escape.pkg.tar.zst"):
            with self.subTest(url=url):
                changed = copy.deepcopy(self.lock)
                changed["packages"][0]["packageUrl"] = url
                with self.assertRaises(ValueError):
                    resolver.validated_lock(changed)

    def test_duplicate_packages_dlls_system_and_imports_are_rejected(self):
        for key in ("packages", "runtimeFiles", "systemImports"):
            with self.subTest(key=key):
                changed = copy.deepcopy(self.lock)
                changed[key].append(copy.deepcopy(changed[key][0]))
                with self.assertRaises(ValueError):
                    resolver.validated_lock(changed)
        changed = copy.deepcopy(self.lock)
        changed["runtimeFiles"][0]["imports"].append("KERNEL32.DLL")
        with self.assertRaises(ValueError):
            resolver.validated_lock(changed)

    def test_unresolved_or_unreachable_declared_imports_are_rejected(self):
        changed = copy.deepcopy(self.lock)
        changed["runtimeFiles"][0]["imports"].append("unknown.dll")
        with self.assertRaisesRegex(ValueError, "Unresolved locked PE import"):
            resolver.validated_lock(changed)
        changed = copy.deepcopy(self.lock)
        changed["runtimeFiles"].append({"file": "unused.dll", "sha256": "c" * 64, "imports": []})
        with self.assertRaisesRegex(ValueError, "outside its mpv import closure"):
            resolver.validated_lock(changed)
        changed = copy.deepcopy(self.lock)
        changed["systemImports"].append("unused.dll")
        with self.assertRaisesRegex(ValueError, "outside its mpv import closure"):
            resolver.validated_lock(changed)

    def test_runtime_dll_cannot_be_classified_as_system(self):
        changed = copy.deepcopy(self.lock)
        changed["systemImports"].append("LIBMPV-2.DLL")
        with self.assertRaises(ValueError):
            resolver.validated_lock(changed)

    def test_schema_requires_arrays_objects_and_imports(self):
        for key in ("packages", "runtimeFiles", "systemImports"):
            changed = copy.deepcopy(self.lock)
            changed[key] = None
            with self.subTest(key=key), self.assertRaises(ValueError):
                resolver.validated_lock(changed)
        for key in ("packages", "runtimeFiles"):
            changed = copy.deepcopy(self.lock)
            changed[key] = ["wrong type"]
            with self.subTest(key=key), self.assertRaises(ValueError):
                resolver.validated_lock(changed)
        changed = copy.deepcopy(self.lock)
        del changed["runtimeFiles"][0]["imports"]
        with self.assertRaises(ValueError):
            resolver.validated_lock(changed)

    def test_windows_path_escapes_and_aliases_are_rejected(self):
        for value in ("../bad", "/absolute", "C:/bad", "a\\b", "a//b", "a/./b", "a/../b", "NUL.dll", "con.txt", "CON .dll", "COM¹.dll", "a.", "a ", "a:b", "a\x00b"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                resolver.safe_relative(value)
        for value in ("./libmpv-2.dll", "libmpv-2.dll/", "sub/libmpv-2.dll"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                resolver.dll_name(value)

    def test_windows_cannot_silently_skip_junction_validation(self):
        path = SimpleNamespace(is_symlink=lambda: False)
        with patch.object(resolver.os, "name", "nt"):
            with self.assertRaisesRegex(RuntimeError, "Python 3.12"):
                resolver.is_link(path)
        self.assertTrue(resolver.is_link(SimpleNamespace(is_symlink=lambda: False, is_junction=lambda: True)))


class PackageCacheTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("FILEWAY_MPV_CHECK_TMP"))
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "fixture.pkg.tar.zst"
        self.data = b"synthetic package bytes"
        self.url = resolver.BASE + self.path.name

    def test_matching_cached_package_needs_no_network(self):
        self.path.write_bytes(self.data)
        with patch.object(resolver.urllib.request, "urlopen", side_effect=AssertionError("No network")):
            self.assertEqual(self.path, resolver.download(self.url, self.path, sha(self.data)))

    def test_mismatched_cached_package_is_preserved_without_network(self):
        self.path.write_bytes(b"preserve invalid cache")
        with patch.object(resolver.urllib.request, "urlopen", side_effect=AssertionError("No network")):
            with self.assertRaisesRegex(RuntimeError, "Cached package hash/type mismatch"):
                resolver.download(self.url, self.path, sha(self.data))
        self.assertEqual(b"preserve invalid cache", self.path.read_bytes())

    def test_partial_package_is_preserved_without_network(self):
        partial = self.path.with_suffix(self.path.suffix + ".partial")
        partial.write_bytes(b"interrupted")
        with patch.object(resolver.urllib.request, "urlopen", side_effect=AssertionError("No network")):
            with self.assertRaisesRegex(RuntimeError, "Incomplete package retained"):
                resolver.download(self.url, self.path, sha(self.data))
        self.assertEqual(b"interrupted", partial.read_bytes())

    def test_download_hash_is_checked_before_cache_activation(self):
        with patch.object(resolver.urllib.request, "urlopen", return_value=io.BytesIO(b"wrong")):
            with self.assertRaisesRegex(RuntimeError, "Package hash mismatch"):
                resolver.download(self.url, self.path, sha(self.data))
        self.assertFalse(self.path.exists())
        self.assertEqual(b"wrong", self.path.with_suffix(self.path.suffix + ".partial").read_bytes())

    def test_download_accepts_only_expected_bytes(self):
        with patch.object(resolver.urllib.request, "urlopen", return_value=io.BytesIO(self.data)):
            self.assertEqual(self.path, resolver.download(self.url, self.path, sha(self.data)))
        self.assertEqual(self.data, self.path.read_bytes())

    def test_destination_created_during_download_is_preserved_with_owned_partial(self):
        late = b"another cache writer's result"
        def response(*args, **kwargs):
            self.path.write_bytes(late)
            return io.BytesIO(self.data)
        with patch.object(resolver.urllib.request, "urlopen", side_effect=response):
            with self.assertRaisesRegex(RuntimeError, "destination appeared"):
                resolver.download(self.url, self.path, sha(self.data))
        self.assertEqual(late, self.path.read_bytes())
        self.assertEqual(self.data, self.path.with_suffix(self.path.suffix + ".partial").read_bytes())

    def test_transient_http_failures_retry_same_url_before_writing(self):
        for code in (502, 503, 504):
            with self.subTest(code=code):
                failure = urllib.error.HTTPError(self.url, code, "transient", {}, None)
                with patch.object(resolver.urllib.request, "urlopen", side_effect=[failure, io.BytesIO(self.data)]) as request, patch.object(resolver.time, "sleep") as sleep:
                    resolver.download(self.url, self.path, sha(self.data))
                self.assertEqual([self.url, self.url], [call.args[0] for call in request.call_args_list])
                sleep.assert_called_once_with(1)
                self.assertEqual(self.data, self.path.read_bytes())
                self.path.unlink()

    def test_transient_http_retry_is_bounded_and_creates_no_partial(self):
        self.url = "https://repo.msys2.org/mingw/ucrt64/" + self.path.name
        failures = [urllib.error.HTTPError(self.url, 502, "transient", {}, None) for _ in range(3)]
        with patch.object(resolver.urllib.request, "urlopen", side_effect=failures) as request, patch.object(resolver.time, "sleep") as sleep:
            with self.assertRaises(urllib.error.HTTPError):
                resolver.download(self.url, self.path, sha(self.data))
        self.assertEqual(3, request.call_count)
        self.assertEqual([1, 2], [call.args[0] for call in sleep.call_args_list])
        self.assertFalse(self.path.exists())
        self.assertFalse(self.path.with_suffix(self.path.suffix + ".partial").exists())

    def test_nontransient_http_errors_are_not_retried(self):
        for code in (401, 403, 429):
            failure = urllib.error.HTTPError(self.url, code, "not retryable", {}, None)
            with patch.object(resolver.urllib.request, "urlopen", side_effect=failure) as request, patch.object(resolver.time, "sleep") as sleep:
                with self.assertRaises(urllib.error.HTTPError):
                    resolver.download(self.url, self.path, sha(self.data))
            self.assertEqual(1, request.call_count)
            sleep.assert_not_called()

    def test_exhausted_mirror_gateway_errors_use_only_official_origin(self):
        failures = [urllib.error.HTTPError(self.url, 502, "transient", {}, None) for _ in range(3)]
        with patch.object(resolver.urllib.request, "urlopen", side_effect=failures + [io.BytesIO(self.data)]) as request, patch.object(resolver.time, "sleep"):
            resolver.download(self.url, self.path, sha(self.data))
        self.assertEqual([self.url] * 3 + [self.url.replace("mirror.msys2.org", "repo.msys2.org", 1)], [call.args[0] for call in request.call_args_list])
        self.assertEqual(self.data, self.path.read_bytes())

    def test_official_mirror_fallback_retains_package_identity_and_hash(self):
        missing = urllib.error.HTTPError(self.url, 404, "fixture missing", {}, None)
        with patch.object(resolver.urllib.request, "urlopen", side_effect=[missing, io.BytesIO(self.data)]) as request:
            resolver.download(self.url, self.path, sha(self.data))
        self.assertEqual([self.url, self.url.replace("mirror.msys2.org", "repo.msys2.org", 1)], [call.args[0] for call in request.call_args_list])


class PeReaderTests(unittest.TestCase):
    def test_pe_reader_combines_normal_and_delay_imports_and_closes_image(self):
        image = SimpleNamespace(FILE_HEADER=SimpleNamespace(Machine=0x8664),
                                DIRECTORY_ENTRY_IMPORT=[SimpleNamespace(dll=b"KERNEL32.DLL")],
                                DIRECTORY_ENTRY_DELAY_IMPORT=[SimpleNamespace(dll=b"dependency.dll"), SimpleNamespace(dll=b"kernel32.dll")])
        with patch.dict("sys.modules", {"pefile": SimpleNamespace(PE=lambda *args, **kwargs: image)}):
            image.parse_data_directories = unittest.mock.Mock()
            image.close = unittest.mock.Mock()
            self.assertEqual((0x8664, ["dependency.dll", "kernel32.dll"]), resolver.inspect_pe(Path("synthetic.dll")))
        image.parse_data_directories.assert_called_once_with(directories=[1, 13])
        image.close.assert_called_once_with()

    def test_pe_reader_closes_image_when_parsing_fails(self):
        image = SimpleNamespace(parse_data_directories=unittest.mock.Mock(side_effect=ValueError("fixture parse error")), close=unittest.mock.Mock())
        with patch.dict("sys.modules", {"pefile": SimpleNamespace(PE=lambda *args, **kwargs: image)}):
            with self.assertRaises(ValueError):
                resolver.inspect_pe(Path("synthetic.dll"))
        image.close.assert_called_once_with()


class ArchiveIOTests(unittest.TestCase):
    @unittest.skipUnless(TAR, "Optional integration requires the platform's existing tar")
    def test_platform_tar_streams_regular_members_without_extracting_paths(self):
        with tempfile.TemporaryDirectory(prefix="Fileway Tar Stream ", dir=os.environ.get("FILEWAY_MPV_CHECK_TMP")) as folder:
            root = Path(folder)
            archive = root / "fixture.pkg.tar.zst"
            archive.write_bytes(fixture_archive({"nested/member": b"synthetic bytes"}))
            def run(arguments, **kwargs):
                self.assertIn(arguments[1], ("-tf", "-xOf"))
                return RUN([TAR, *arguments[1:]], **kwargs)
            with patch.object(resolver.subprocess, "run", side_effect=run):
                self.assertEqual({"nested/member": "nested/member"}, resolver.archive_entries(archive))
                resolver.copy_archive_member(archive, "nested/member", root / "streamed")
            self.assertEqual(b"synthetic bytes", (root / "streamed").read_bytes())
            self.assertFalse((root / "nested").exists())

if __name__ == "__main__":
    unittest.main()

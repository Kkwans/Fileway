# Isolated mpv runtime

The committed `runtime-packages.lock.json` identifies the preview's exact MSYS2
UCRT64 x64 packages, DLL bytes, normal/delay PE imports and Windows system imports.
Restoring it does not approve redistribution or establish native playback.

## Restore the committed lock

On Windows, use an external, fresh artifact root:

```powershell
python .\vendor\mpv\resolve-runtime.py --artifacts-root <external-artifacts> --lock-input .\vendor\mpv\runtime-packages.lock.json
python .\vendor\mpv\probe-runtime.py <external-artifacts>\media-runtime\x64
.\eng\Build-Windows.ps1 -ArtifactsRoot <external-artifacts> -MediaRuntimeRoot <external-artifacts>\media-runtime\x64 -Configuration Release
```

`--lock-input` is a read-only input. It is mutually exclusive with the original
`--lock` discovery-output option. Locked mode never reads `ucrt64.files`, resolves
new versions or writes a new repository lock. It downloads only the locked
package identities and checks their SHA-256. The existing official mirror's 404
fallback keeps the exact archive filename and expected hash; unavailable bytes
fail instead of being replaced with a newer package.

Restoration validates package metadata identity, every selected DLL's hash/x64
architecture and actual normal/delay imports, the exact mpv import closure, and
availability of declared Windows system DLLs. API-set imports need no literal
System32 file. Packages are inspected with `tar.exe`; selected entries stream to
owned output files through stdout. Archive paths and symlinks are never extracted
onto the filesystem. Windows path aliases, traversal and case collisions fail.

After a package's complete inventory passes those checks, locked restoration can
use bsdtar's `-q` for an exact, individual member read. This is enabled only on
Windows when `tar.exe --version` identifies bsdtar, the raw member path has no
pattern characters and the inventory contains no descendants of that member.
Unknown tar versions, other platforms and ambiguous member paths retain the
original full-scan command. Package/DLL hash checks, staging and the native probe
remain required; no Windows timing improvement is established by the offline
tests.

The input lock and artifact root must be separate. Artifacts cannot be inside a
Git checkout or traverse output symlinks/junctions. All validation happens in a
unique staging directory before its promotion to `media-runtime\x64`. A runtime
that already exists is preserved: choose another fresh artifact root rather than
overwriting it. Invalid cache entries, incomplete `.partial` archives and failed
staging directories are retained for inspection; retries do not silently replace
them. Identical verified package-cache files may be reused.
Package-cache activation uses a same-volume hard link with no overwrite. Runtime
promotion uses Windows's no-replace rename or Linux `renameat2(RENAME_NOREPLACE)`;
an unavailable primitive fails safely with the staged result retained.

The activated runtime contains DLLs, license notices, package `.PKGINFO` and
`.BUILDINFO` under `provenance`, an exact byte copy of the input lock, and a
`locked-restore.json` receipt identifying that lock's SHA-256. Corresponding source
and build completeness, nonfree configuration, native launch/rendering and public
redistribution remain separate gates. Do not upload the runtime or packages as
CI artifacts before those distribution gates are approved.

## Dependencies and offline tests

Python 3.12 or newer is required for Windows junction validation; missing junction
inspection support fails closed. Real restoration also needs the isolated
`pefile` dependency and Windows `tar.exe` with zstd support, plus `SystemRoot`.
Neither MSYS2 installation nor FFmpeg tools are required. Install Python packages
only in the task's isolated dependency environment, not global Python.
An explicit available pin is `pefile==2024.8.26`; its
`pefile-2024.8.26-py3-none-any.whl` SHA-256 is
`76f8b485dcd3b1bb8166f1128d395fa3d87af26360c2358fb75b80019b957c6f`.
See [PyPI's file metadata](https://pypi.org/project/pefile/2024.8.26/).
The offline tests do not need this wheel or any installation.

The offline contract suite needs only Python's standard library. It generates
synthetic tar packages and mocks PE metadata, download responses and archive
commands. One optional integration test also exercises the platform's existing
tar with an uncompressed synthetic archive; another exercises `-q` only with
recognized Windows bsdtar. They skip explicitly when their platform/tool gate is
unavailable.
No test downloads packages, installs tools or loads a real media DLL.

From the repository root:

```shell
python -m unittest discover -s clients/windows/vendor/mpv/tests -p "test_*.py" -v
```

Set `PYTHONDONTWRITEBYTECODE=1` to avoid Python cache files in the checkout.
`FILEWAY_MPV_CHECK_TMP` can select an existing external fixture root; otherwise the
system temporary directory is used. Tests include spaces in paths, DLL name case
differences, archive case collisions, unchanged-input checks, hash/import failures,
partial-cache preservation, unsafe output paths and normal/delay PE reader cleanup.
These tests do not prove real zstd extraction, native DLL loading, codec support,
visible frames, UI responsiveness or TX5Pro performance.

## Discovery is a separate maintenance operation

The legacy `--lock <output-lock>` mode remains available to deliberately discover
a new dependency closure against the MSYS2 repository index. Its output is a new
candidate lock, not reproducible restore input until reviewed and committed.
Use a separate external artifact root and external output lock. Never point the
discovery output at the committed input during a CI smoke run.

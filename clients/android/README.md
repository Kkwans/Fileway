# Fileway for Android

Native Android client for Fileway's Linux/Windows servers. Windows desktop is
planned as a later delivery in this same repository.

Android 10+ / arm64-v8a and x86_64. Kotlin, Compose, Media3 with FFmpeg audio and libass subtitles,
and an embedded Go/tsnet transport. Device-specific HDR, audio and long-play
behavior is validated separately from code and package checks.

## Android 1.0.0

[Download the signed Android APK](https://github.com/Kkwans/Fileway/releases/latest/download/fileway-android-1.0.0.apk)
· [Release notes](docs/release-1.0.0.md)
· [All releases](https://github.com/Kkwans/Fileway/releases)

The stable APK uses versionName `1.0.0`, versionCode **38**, Room **9**, and the
original package ID and signing certificate. Upgrade over an existing installation;
profiles, saved logins, downloads and local settings are retained. The published
release identifies its exact source commit and APK checksum. Recent-record batch
deletion requires the shared server `2026.10.25-v1` or later.

Files, Recent, Favorites and Tags use a single tap for details, a long press for
multiselect, and a double tap to open supported objects. Trash and operation-only
records expose their available actions in details. See the [1.0 design contract](docs/design-system.md).

### Historical 0.7.4 verification

The results below belong to the old `0.7.4-preview`/code14 release, not the current
1.0 verification. Its immutable [release and notes](https://github.com/Kkwans/Fileway/releases/tag/android-preview-0.7.4-63d3ddb)
remain available for historical reference.

Release build/lint and 95 JVM tests in the configured debug host-test variant
passed, including both ABI and 16 KiB ELF/ZIP checks. Xiaomi14 was cover-upgraded
to code 14 without changing its first install time. Saved-login/directory restore,
retained downloads, actual local MKV pixels and subtitles were observed on the
release APK. The diagnostic build passed 21 focused media/upload/recovery methods,
7 file-operation UI methods and 5 harness diagnostics. Linux and Windows run
shared server `2026.10.10-v4` from the same source. Native backend test/vet passed
(740 Linux and 756 Windows cases, platform skips recorded separately). The
unchanged Web artifact reuses its 582-test verification.

The [complete Android CI for this source](https://github.com/Kkwans/fileway/actions/runs/38019996792)
**failed**; its per-case results remain under review. The earlier
[ab3950 run](https://github.com/Kkwans/fileway/actions/runs/38007213426) reported
10/286 API35 and 7/287 API37 failures plus theme verify failures; those historical
counts are not this version's totals. Complete functional, hardware/media and UI
acceptance remain open.

### Implemented capabilities

- A task center with separate downloads, uploads, server background tasks and
  operation history; Recent separates playback/resume history from server-access records.
- File browsing/search, multiselect, file/folder downloads, favorites/groups, tags,
  trash and server storage tools.
- Native text/PDF reading; text search/copy, conditional editing that preserves
  encoding/newlines, and exclusive file creation.
- Foreground native audio playback with same-type queues, seek, audio-track and
  playback-speed controls, alongside native video and image viewing.
- Archive browsing/search, selected extraction/reporting and read-only member
  image/audio/video/document previews; rule-based batch rename, on-demand file
  checksums/comparison and ZIP download packaging.
- Account preferences/passwords, administrator user/permission/rule management,
  server configuration and authorized single-command output.
- App updates check both stable and preview channels without server credentials,
  label the channel, retain compatible upgrade choices and verify package/version,
  certificate and ABI before opening the system installer. See [update contract](docs/app-updates.md).

These are implemented feature groups, with scoped verification. Server capabilities
and versions determine availability; unsupported newer operations request an upgrade.
The final release records the app-update download/install/cover-upgrade result separately from build checks.

The historical stock-Media3/libVLC comparison is a separate, opt-in diagnostic:
build the test APK with `-PfilewayEngineComparison=true`, then explicitly run
`EngineComparisonTest` on the selected disposable device. The pinned VLC-version
and mpv probe options also select this source set. Its original candidate/pixel
assertions and historical failures are retained; it does not certify the selected
Media3/FFmpeg/libass product chain. Default instrumentation continues to include
the actual native playback, audio, subtitle, viewport and lifecycle gates.

### Core fixes in 0.7.4

- Save only engine-confirmed READY/ENDED positions, preserving valid resume data
  when a seek into an unavailable download range is still waiting or has failed.
- Use actual readable descriptor length when validating an upload source;
  unknown descriptor lengths retain the provider fallback, while access errors
  remain explicit. Existing fragments are retained when the source changed.
- Treat temporary metadata and range failures consistently as recoverable
  download waits; authorization and source-identity failures remain explicit.
- Persist directory-overwrite recovery journals across task interruption and
  retry, retaining originals/backups and refusing unknown ownership.
- Publish legacy copies and cross-device regular-file moves after preparation;
  failed operations do not blindly remove the destination.

Server-side fixes require `2026.10.10-v4`. Directory task recovery is a process
interruption protocol, not a power-loss or snapshot guarantee. Legacy directory
move merging still has separate leaf-write/recovery limitations. Preserve live
database writes and use a compatible forward repair when recovery is needed.

### Core fixes in 0.7.3

- Publish document save/create, atomic batch rename and current-account deletion
  completion after synchronous local callbacks; retain acknowledged writes and
  drafts when follow-up work fails, without replaying the remote mutation.
- Verify that encrypted credentials actually reached the final file before
  reporting local save success; preserve an existing readable value on failure.
- Keep both copies automatically for a same-directory COPY, preserving original
  filename bytes through copy/paste and conflict preflight.
- Publish regular files without removing an existing destination first, and use
  no-replace publication when overwrite was not approved.

These original regular-file publication fixes require shared server
`2026.10.10-v3` or later. The 0.7.4 section describes the additional recovery and
legacy-copy changes and their limits.

### Core fixes in 0.7.2

- Read completed local PDF/text downloads inside the App without the original
  server/account; bound source identity, read-only descriptors, size budgets and
  retries retain the original file. PDF layout uses finite pixel constraints.
- Recover from failed/canceled image previews by explicitly opening the original;
  bind leases, quality, cache revision and request generation during transitions.
- Ignore superseded external-subtitle parse failures and closed admin-page reads;
  retain acknowledged Web writes across renewal or account changes.
- Preserve original file identities through tags, selection, destination pickers,
  conflict preflight, copy/move/rename and deletion. Queued tasks require current
  owner permissions at submission, retry, publication and source deletion.

New original-path writes require server `2026.10.10-v2` or later. Older servers
remain usable for compatible operations; missing capabilities request an upgrade.
Persisted `wire-v1` tasks require a compatible server binary for recovery: retain
live database writes and use a forward repair, rather than blindly restoring a
pre-v2 executable or database.

### Core fixes in 0.7.1

- Decode external PGS/SUP display sets on demand and replay ended audio/video on
  one play action while retaining the current media, tracks and preferred speed.
- Reserve the whole upload batch's targets, serialize competing submissions and
  respect cancellation; preserve original-path identity through favorites and
  related Web resource reads, including group order/color updates.
- Retain acknowledged HTTP writes across renewal failure; isolate shared-server
  metadata changes by scope and recheck TUS overwrite permissions after creation.
- Cancel real source/upstream streams, keep partial Range reads distinct from
  whole-file completion, and normalize Windows HLS export paths before validation.

Shared-server fixes require server `2026.10.10-v1` or later on Linux or Windows.

### Upgrade, verification and recovery

Cover-install; do not uninstall or clear data. The same-package/same-certificate,
higher-versionCode path preserves existing Room8 state, profiles, saved credentials,
downloads/uploads, preferences and embedded-node identity. Earlier compatible
schemas use the existing migrations; there is no destructive migration fallback.
Use a same-certificate, higher-versionCode repair for recovery rather than a
lower-version APK against a newer database. See [local state](docs/local-state.md).

Additional evidence comes from signed debug/test-APK waves, including 95 JVM
tests, directly affected local-document/image device regressions, and separate
track/admin/tag/file-operation contracts. A real NAS source with eight embedded
PGS tracks was exercised across seeks, track/off/delay changes and actual subtitle
pixels. Complete offline MKV cold start and actual picture output were also
exercised. These results do not certify full CI, HDR, audible continuity,
long-play performance or complete UI acceptance.

Older partial videos need one online opening to prepare missing startup metadata
before offline cold start. New supported MKV/MP4/WebM downloads prepare it before
the body; only saved, decodable portions are available offline. Existing tasks
retain their original connection channel; changing the browsing connection does
not automatically reroute them. Expired legacy accounts without a saved password
need one login with keep-login enabled. See [download locations and recovery](docs/download-storage.md),
[uploads](docs/uploads.md) and [historical 0.6.1 scope](docs/preview-0.6.1.md).

Preview releases follow product significance: substantial feature groups advance
the minor version and important fixes advance the patch version. Delivered APKs
increase versionCode, preserve signing identity and identify the pushed source.
Preview publication never implies complete media/UI acceptance.

## Native dependency sources

Media3/FFmpeg/libass dependency inputs and recipes are unchanged from 0.6.1. Reuse the published
[Media3/FFmpeg source bundle](https://github.com/Kkwans/fileway/releases/download/android-preview-0.6.1-c3fe361/fileway-media3-ffmpeg-sources.tar.gz)
and [libass source bundle](https://github.com/Kkwans/fileway/releases/download/android-preview-0.6.1-c3fe361/fileway-libass-0.5.1-sources.tar.gz),
which remain assets of that release. Reproduction uses the [native build recipe](docs/ffmpeg-audio-probe.md)
and [pinned input lock](native/media3-ffmpeg.lock.json), with the
[upstream Media3 decoder module](https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg)
and [official FFmpeg releases](https://ffmpeg.org/download.html).

## Build

Use JDK 17 and Go 1.26.6 on a supported Linux x64 build host. Install Android platform 37.0, build tools 36.0.0, CMake 3.22.1 and NDK 28.2.13676358. Native dependencies must be compiled before Gradle packages the app.

Generate the pinned FFmpeg AAR and adjacent manifest using the
[native build recipe](docs/ffmpeg-audio-probe.md). Set the following variable to
that manifest's absolute path for every App Gradle invocation, including emulator
checks. CI produces this artifact in its reusable native-library job and verifies
the input lock, recipe and AAR hashes before consuming it. Alternatively, a Linux
source build can supply `-PfilewayFfmpegWorkDir` to use the prepared native module.

```sh
export ORG_GRADLE_PROJECT_filewayFfmpegManifest="$PROBE_OUTPUT/ffmpeg-probe.json"
bash scripts/build-native.sh
./gradlew :app:assembleDebug :app:lintDebug
```

The SDK path belongs in untracked `local.properties` or `ANDROID_HOME`.
See [transport contract](docs/embedded-routing.md) and [local state](docs/local-state.md).

## Emulator checks

Build both APKs, then run the host-managed suite on a disposable emulator that
is the only attached device:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/run-emulator-checks.py --serial emulator-5556
```

Use the actual selected emulator serial. The runner rejects physical devices and
additional attached devices. It runs the ordinary connected suite, then verifies
theme restoration with separate prepare/verify instrumentation invocations and
an intervening process stop. The prepare PID must differ from the verify PID;
saved preferences survive without clearing data. The first failing result and
logs remain under `app/build/acceptance`, including when later evidence export
fails. A host timeout does not authorize a second install or test job.

UTP may uninstall its target package, so this runner must never be used on an
occupied personal phone. Physical media, HDR, audible output and covering-upgrade
acceptance are separate gates; successful emulator checks do not certify them.

Credentials, Tailscale identity, signing keys and real media must stay outside this repository.

Embedded Tailscale is selected explicitly in the connection form. Configuration alone does not enroll a node; connect starts the official interactive login flow. The node's approved subnet routes are enabled, and its state is encrypted with a data key protected by Android Keystore. Embedded mode never falls back to direct HTTP. Real tailnet enrollment, external routing and device lifecycle acceptance are still pending.

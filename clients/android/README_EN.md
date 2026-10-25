# Fileway Android

[English](README_EN.md) · [简体中文](README.md) · [Project home](../../README_EN.md)

A native Android file and media client for your own Fileway server. Android **10+**, **arm64-v8a / x86_64**.

## Install and update

[Download Android 1.0.0 stable](https://github.com/Kkwans/Fileway/releases/download/android-1.0.0-966bf38/fileway-android-1.0.0.apk) · [Release notes](docs/release-1.0.0.md) · [All releases](https://github.com/Kkwans/Fileway/releases)

The main branch is preparing the **1.1.0 candidate**, which is not a published stable release. Upgrade over the existing app to preserve the package, signing identity and local state. Uninstalling or clearing data is not part of an ordinary upgrade.

## Screenshots

| Files | Video player · 1.1 candidate |
| --- | --- |
| <img src="../../docs/images/android-files.png" alt="File browser" width="300"> | <img src="../../docs/images/android-player.png" alt="Video player" width="300"> |

## Everyday use

- Connect to your NAS/Linux or Windows server directly or through embedded Tailscale.
- Tap for details, long-press for multiselect, and double-tap to open supported files and folders.
- Preview images, audio, video and common documents; manage local caches, favorites, tags and resume positions.
- Manage downloads, uploads, server tasks and operation history together. Use trash and storage tools for recovery and analysis.
- Select audio tracks, embedded or external subtitles, subtitle fonts and timing, playback speeds and directory playlists.

The 1.1 candidate also unifies glass feedback, cover-derived player ambience, animated icons and multiselect, and adds system picture-in-picture with manual entry by default.

## Native media and local state

Playback uses **Media3 + FFmpeg audio + libass subtitles**, with networking and transport in the [shared Go core](../shared/core/README.md). Format support and HDR output depend on the device, decoder and source media.

- [Subtitle formats](docs/subtitle-formats.md)
- [Download storage and recovery](docs/download-storage.md) · [Uploads](docs/uploads.md)
- [Local state](docs/local-state.md) · [App updates](docs/app-updates.md)
- [Native engine validation](docs/native-engine-validation.md) · [Historical release records](docs/release-history.md)

Recent-record batch deletion requires server `2026.10.25-v1` or later. Each server instance continues to control accounts, permissions and server settings.

## Build from source

Use the pinned JDK17, Android SDK37, NDK28.2, CMake3.22.1 and Go toolchain. Follow the [FFmpeg build recipe](docs/ffmpeg-audio-probe.md) to prepare the matching AAR and `ffmpeg-probe.json`, then build the transport core and app:

```sh
cd clients/android
export ORG_GRADLE_PROJECT_filewayFfmpegManifest=/absolute/path/to/ffmpeg-probe.json
bash scripts/build-native.sh
./gradlew :app:assembleDebug
```

On Windows, use the corresponding Gradle Wrapper and configured native toolchain. Keep signing identities, credentials, databases and private media outside the source tree.

Candidates bind to an exact source commit and retain the original signing identity. Code checks, UI acceptance and hardware/media evidence are recorded separately.

## Icons and licenses

UI icon sources are Lordicon System Outline and Material Symbols Rounded. Candidates package licensed official animation exports; public source builds retain semantic static fallbacks. See [Lordicon notices](licenses/lordicon.md) and [Material Symbols provenance](licenses/material-symbols.md).

The Android client uses [GPL-3.0](LICENSE); third-party notices remain included.

- [Media3/FFmpeg sources](https://github.com/Kkwans/Fileway/releases/download/android-preview-0.6.1-c3fe361/fileway-media3-ffmpeg-sources.tar.gz)
- [libass sources](https://github.com/Kkwans/Fileway/releases/download/android-preview-0.6.1-c3fe361/fileway-libass-0.5.1-sources.tar.gz)

Animated icons by [Lordicon.com](https://lordicon.com/).

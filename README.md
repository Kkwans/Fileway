# 栖卷 · Fileway

跨设备的个人文件与媒体中心。This repository is the migration destination for
NAS File Browser, its Windows server adaptation, and the native clients.

## Android 1.0.0

[下载正式版 APK](https://github.com/Kkwans/Fileway/releases/latest/download/fileway-android-1.0.0.apk)
· [发布说明](clients/android/docs/release-1.0.0.md)

## Components

The historical projects have been consolidated into this repository. Linux and
Windows use one server implementation and one Web build, tested on native CI
runners. Existing application IDs, signing identities and published preview URLs
remain unchanged. Android hardware/media acceptance is separate from server CI.

| Component | Path | Status |
| --- | --- | --- |
| Linux/Windows server core and Web | `server` | One backend, one frontend; native platform adapters |
| Android application | `clients/android` | 1.0.0 stable |
| Shared client core | [`clients/shared/core`](clients/shared/core/README.md) | Client-side Go transport and protocol core; not a second backend |
| Windows native client | Not scaffolded | Later planned delivery; no placeholder app |

Linux and Windows use the same API routes, core and Web build. macOS reuses them
when its native adapters and packaging are added. See [architecture](docs/server-architecture.md).
There is no separate Linux or Windows UI tree.
`clients/shared` currently contains only `core` because this is the one existing reusable
client module. It does not duplicate the API, file service or Web UI in `server`.
See the [server build guide](server/README.md) and [shared module guide](clients/shared/README.md).

## Licenses

This repository contains separately licensed components. Preserve each component's
LICENSE and third-party notices: server projects currently use Apache-2.0, and
the native client uses GPL-3.0. There is no blanket relicensing of imported code.

Development plans, session handoffs, audit snapshots and deployment evidence are
kept outside the source repository. Versioned source includes maintained product,
architecture, build and API documentation only.

Product branding is 栖卷 / Fileway. Existing package identifiers and protocol
names are intentionally preserved for compatibility, not accidental rebranding gaps.

The former [Linux server](https://github.com/Kkwans/nas-file-browser),
[Windows server](https://github.com/Kkwans/win-file-browser), and
[client](https://github.com/Kkwans/nas-file-browser-client) repositories are
read-only archives. Their history and published releases remain available;
all new development belongs here.

# Fileway（栖卷）Windows

Windows 11 x64 native client, using .NET 10, WinUI 3 and the existing Go client core. The application is unpackaged and self-contained; it launches directly as an EXE. There is no WebView in the main interface.

## Toolchain and isolation

The initial verified development toolchain is .NET SDK 10.0.401, Windows App SDK 2.5.1, Windows SDK BuildTools 10.0.26100.9169, winapp CLI 0.7.1 and Go 1.26.6. WinUI source was generated using Microsoft's `Microsoft.WindowsAppSDK.WinUI.CSharp.Templates` 0.0.7-alpha; the template generator is separate from the stable application framework. The actual template options are obtained from `dotnet new winui --help`.

`eng/Use-WindowsEnvironment.ps1` selects project-specific toolchains, caches, build output, data and temporary directories in the current PowerShell process. It does not update global environment variables, install Visual Studio, enable Developer Mode, register certificates or modify Android SDKs.

```powershell
.\eng\Install-WindowsTools.ps1 -ToolsRoot <isolated-tools>
. .\eng\Use-WindowsEnvironment.ps1 -ToolsRoot <isolated-tools> -ArtifactsRoot <external-artifacts>
.\eng\Build-Windows.ps1 -ToolsRoot <isolated-tools> -ArtifactsRoot <external-artifacts> -Configuration Debug
```

The installer script downloads fixed official tool archives, checks published hashes and extracts them into the selected directory. Incomplete downloads are retained for resume; no system installer is run. Defaults use sibling directories relative to the checkout, so the scripts also work with explicit temporary CI roots.

Deep checkout/tool paths can exceed native XAML compiler path limits. Pass an explicit short, externally owned `-NuGetPackagesRoot <path>` to environment/build/test scripts when needed. It is propagated through nested scripts and never changes global NuGet or Windows settings.

Build-Windows builds the Go Host first, embeds the actual Git commit and records dirty state and executable SHA-256 in `host-build.json`. It then copies the matching Host and manifest beside the application. A direct app build without these prerequisites fails explicitly.

The EXE is produced under `<external-artifacts>\bin\Fileway.App\x64\Debug\net10.0-windows10.0.26100.0\win-x64`. Run it with that directory as its working directory. Publishing also stays under the external artifact root. A successful build is not proof of native launch, media support or a clean installation.

## Component boundaries

- `Fileway.App`: native windows and view models, input, theme and accessibility.
- `Fileway.Core`: immutable account/resource identities, typed service contracts and use cases; no WinUI dependency.
- `Fileway.Infrastructure`: process IPC, persistence, credentials, local files and diagnostics.
- `Fileway.Playback`: native media bindings and playback lifecycle.
- `host`: separate Go executable referencing `clients/shared/core`, following [private IPC v1](docs/ipc-v1.md).

## First local preview

The maintained [product roadmap](docs/product-roadmap.md) assigns Web/Android
feature parity to concrete Windows milestones. The accepted Screenbox player is
the baseline; acceptance of that player does not approve all other application UI.

The native preview supports password/noauth connections, directory browsing, sorting and filtering the current directory, file details, a native image window, and libmpv video playback. Its native playback controls port parts of Screenbox's templates and interaction structure: pause, seek, volume/mute, precise speed, chapters, confirmed audio/subtitle track selection, local external-subtitle selection and format-aware subtitle settings. This is a source adaptation, not an embedded Screenbox application or its complete feature set. See [source attribution](licenses/screenbox-ui.md). Sprite and episode providers remain separate work.

The file browser uses the selected clear Fluent direction. The 0.2 preview adds
virtualized list/grid views, selection-preserving view switching and a responsive
details pane; grid items currently use file-type icons, not remote thumbnails.
Other visual directions remain later theme work. Native browser acceptance is
still distinct from the previously accepted playback surface.

Connection profiles now persist in SQLite. Keep signed in stores a DPAPI-encrypted
session token, never the password; restore verifies current account permissions.
Saved HTTPS connections can restore at startup. HTTP requires acknowledgement
for that connection attempt. Logout removes saved authentication while retaining
the profile; Forget removes both. See [local data and recovery](docs/local-data.md).

A complete local media file can also be opened offline by passing its absolute path as the sole argument to `Fileway.App.exe`. This does not acquire a remote media lease or require login.

Remote playback reuses the server's playback-history API and resume preferences;
the native nonmodal offer follows the Web player's resume/from-start/ask behavior.
Only confirmed engine positions are saved. Source resolution is displayed from
native video metadata; it does not imply HLS transcoding quality selection.

Windows executable, window, taskbar and installer icons use the Android launcher's
original foreground paths and background gradient. Windows optical exports
enlarge that foreground by 1.45 and use a rounded, transparent-corner background;
Android's adaptive-icon resources retain their existing safe area. The vector
master is `Assets/WindowsAppMark.svg`; `eng/Export-Branding.py` regenerates the
eight PNGs and seven native ICO sizes from the Android vector sources with Pillow.
This is a platform adaptation of the same brand, not a separate logo.

Application windows use a shared frameless caption for the main and image
windows; playback retains its accepted native window. Normal move/resize,
maximize/restore, system close and theme behavior remain part of native acceptance.
The window/branding and saved-connection/browser slices are delivered as 0.2.0.
Further usable increments use ordinary versions such as 0.2.1; neither the version
number nor a local package marks the full product roadmap complete.

The 0.2.1 increment adds account-bound video disk caching through the shared Go
core, bounded mpv memory read-ahead, actual buffered timeline intervals and the
primary video's upstream network speed in decimal MB/s. Storage settings save
a custom cache root, an independent future download location and one total data
budget of 5 GB by default. Directory changes take effect after restart; existing
data is not moved or removed. Thumbnails and original-image disk caching follow
in 0.2.2 and must share that budget. See [cache behavior and limits](docs/cache-storage.md).

The installer offers a destination folder and a desktop shortcut selected by
default. Chinese Windows UI or China region defaults to **栖卷**; other preferences
default to **Fileway**, with an explicit installation language choice taking
priority. Internal executable names and data paths stay stable. This naming
policy is separate from future complete UI localization.

This preview uses explicit direct connections, including routes supplied by the system Tailscale installation. System/PAC proxies, embedded tsnet, transfers, file mutations and external-player integration remain separate application work. The main interface contains no placeholder actions for those features.

The Go auxiliary process is managed by the app: it starts locally, uses private inherited control pipes, and exits with the parent. Process management prevents orphan processes and supports crash recovery. Server authorization continues to enforce the existing account permissions.

## Media and packaging

Native media binaries stay outside Git. `vendor/mpv/runtime-packages.lock.json` records the exact MSYS2 UCRT64 packages and PE imports selected for the preview. `resolve-runtime.py` resolves the runtime into an external artifact root; `probe-runtime.py` inspects the actual mpv/FFmpeg build, including rejection of an enabled nonfree configuration. These scripts require Python and `pefile` in the isolated dependency environment. Runtime licenses and binary provenance accompany the local payload; corresponding-source completeness and public redistribution approval remain separate release gates.

```powershell
.\eng\Build-Windows.ps1 -ArtifactsRoot <external-artifacts> -Configuration Release -MediaRuntimeRoot <pinned-runtime-directory> -Publish
.\eng\Package-Preview.ps1 -PublisherInput <external-artifacts>\publish\Release\win-x64 -ArtifactsRoot <external-artifacts>
```

The runtime is copied under `media` beside the application. Packaging consumes an existing publish directory and creates an unsigned ZIP and a current-user NSIS installer; it does not rebuild, install or publish a GitHub release. Keep the whole extracted directory together and run `Fileway.App.exe`. See [installer behavior](installer/README.md).

Local installers use zlib to reduce packaging time. Focused regression can select
the affected test classes with `eng/Test-Windows.ps1 -FilterClass <fully-qualified-class>`;
omitting the filter retains the full suite for relevant shared changes and release candidates.

Visual direction, representative native slice and final product acceptance remain separate gates. Build and runtime evidence belongs outside Git. A successful native media probe does not prove visible first frames, audible sound, HDR, all codecs, multiple monitors or a clean installation.

Windows code uses GPL-3.0, consistent with the shared core. Existing backend and Android licenses and identifiers remain unchanged. Third-party binaries require their own pinned provenance, notices and corresponding source/build materials before distribution.

# Fileway Windows

- Windows work happens only on `codex/windows-native-client` in the dedicated Windows worktree. This overrides the root migration rule to work on main for this component.
- Preserve Android application/module IDs and shared Go module IDs. Do not edit Android files or control its running tasks.
- The main agent owns public .NET contracts, project/lock files, root build and CI integration. Shared core/server/Host changes have one core owner. UI, media, persistence, transfers and tests have separate assigned owners; request interface changes through the main agent.
- The main agent runs GPT-6.1 Sol / max. Use GPT-6.1 Sol / high for ordinary implementation and max for difficult reasoning/interop. Verify actual runtime records. Prefer bounded implementation waves, with integration testing concentrated on a runnable slice.
- Keep build output, toolchains, caches, databases, private fixtures and execution evidence outside Git. Use the Windows environment script. Do not alter global PATH or other platforms' toolchains.
- IPC follows `docs/ipc-v1.md`. UI never handles NAS credentials or constructs server URLs. Remote wire paths are opaque and distinct from local paths.
- Visual direction approval and real native representative slice acceptance are required before expanding the UI. Technical probe windows do not constitute product acceptance.
- Use the shared frameless window chrome for application windows, retaining native move/resize/maximize/system-close semantics. The accepted playback host and its borderless owned surfaces keep their dedicated integration. New pages inherit their application's window chrome.
- File-browser direction A (clear Fluent) is selected. Keep visual experiment selectors out of the product flow; the native representative slice still requires user acceptance.
- Windows branding uses the Android-authored mark/colors with desktop optical sizing and rounded transparent corners. Export existing vector geometry with eng/Export-Branding.py; do not alter Android launcher resources for a Windows adjustment.
- Read/write operations with an unknown result must be reconciled; never blindly replay a mutation. Explicitly request trash deletion semantics.
- Commit only independently verified slices. No push, main merge, production/NAS change, paid service, signing identity replacement or public release without explicit authorization.
- Keep temporary plans, capability receipts and test evidence in the external artifact directory. Commit maintained product, architecture, API and build documentation only.

# 栖卷 TV visual and interaction system

Mode: Operate / Experience. Extend the existing Fileway visual identity.

- Consume the shared FilewayPalette and vector resources. Dark default with soft graphite canvas (#292A2F) and surface (#33343A), using Wholphin Blue/BoldBlue dark surface-container levels for projection rather than its near-black base; Fileway blue is an accent, not a blue wash. Optional light palette. Keep image canvas black.
- Use TV Material interaction primitives with explicitly themed 12dp shapes. No phone bottom navigation or touch-only affordance.
- Reference canvas: 960×540dp; safe content gutters default to 5%, independently adjustable. Titles 26–28sp, primary labels 18–20sp, secondary labels 14–16sp. Native sans with stable CJK baselines.
- Fixed left navigation; one readable page header and action row; content uses lazy regular grids (four columns by default, configurable 2–6) or lists. File names use 16sp for this denser projection layout. Home shows actual resume/recent entries. File cards reserve stable artwork/name slots.
- Focus has a 2dp blue outline and contrasting surface; selection uses a persistent tonal fill. Never move layout geometry when focus changes. Restore focus by resource identity and scrolling position.
- Modals and right panels contain focus, scroll when required and return it to their trigger. All operations work with directions, OK and Back. Root Back exits without confirmation.
- Video surface remains attached while HUD/panels appear. Keep timeline, primary playback action and options on clear axes; reserve legible subtitle space. Hide controls only when idle and no modal/edit state is active.
- Use 120–180ms color/opacity feedback only, respecting system disabled animations. No live blur, animated ambient backdrop or video frame copies.
- Inspect real text, long paths, many items, empty/loading/failed states and both themes. Native rendering evidence is required; physical image/HDR/audio acceptance is separate.

## TV application references

Reviewed 2026-10-28, based on upstream source and published interface documentation. These are interaction/composition references, not dependencies or runtime acceptance evidence. No third-party branding, copyrighted cover art or implementation code is copied.

| Reference | Source inspected | Fileway application |
| --- | --- | --- |
| [Wholphin](https://github.com/damontecres/Wholphin) | `HomePage`, `NavDrawer`, `GridCard`, `ItemRow`, `PlaybackControls`, `SeekBarState` | Separate navigation and content focus groups; horizontally scrollable resume/recent rows; stable artwork slots with captions; grouped playback options; coalesced seek requests. |
| [Jellyfin Android TV](https://github.com/jellyfin/jellyfin-androidtv) | `VideoPlayerControls`, `FocusPersistManager` | Main playback actions and secondary options grouped on separate axes; restore focus by semantic item key; request focus only after the target is attached and visible. |
| [JetStream Compose](https://github.com/android/tv-samples/tree/main/JetStreamCompose) | Official sample documentation and source structure | TV Material primitives, lazy lists/grids, lifecycle-owned playback, baseline-profile validation. Check current APIs rather than copying deprecated TvLazy examples. |
| [SmartTube](https://github.com/yuliskov/SmartTube) | Project interaction documentation and player structure | Supplementary reference for remote-only dense playback settings; do not inherit its visual palette, media engine or buffering recommendations. |

Source tree snapshots for traceability: Wholphin `cecfdaeba94560ed547288fe2cba0324ec9a9b24`; Jellyfin Android TV `c41f329d05bd8e1a6d4a555874e7988c59d9fb1e`; Android tv-samples `15ac8df7df91a07450751a5fe8c6fd17a6a4297f`; SmartTube `380a2b79d3aa1af44a7b8ffbb406ae483a13fbd6`. These are Git tree IDs, not Fileway commits.

### Composition decisions

- Home: small brand/header, visible current destination, actual Continue Watching row, Recent Access row and category entry cards. No giant hero without real source metadata; no fake poster library before the separately planned scraping system.
- Content grid: preserve the left navigation axis; directory/context name above a compact action row. Thumbnail and title geometry remains stable. Focus returns to the same item after playback, details and sorting.
- Player: adopt Wholphin Controller/PlaybackControls composition: title immediately above the timeline at the bottom, centered icon-only transport, settings/queue to the left and captions/audio/info to the right. Use a static transparent-to-black gradient and translucent controls, with no realtime blur or opaque footer. Play/pause is the first focus target when opening controls. A seek bar never traps focus away from neighboring options.
- Panels: one scrollable right-side surface for subtitles/audio/HDR/options. Current value, selection and focus are distinguishable; closing restores the exact trigger. Pause auto-hide while the panel or an editor is active.
- Seek: immediately show requested target, coalesce key repeats, commit one effective seek after input settles; do not save the requested target as confirmed playback progress.
- Focus budget: no continuous scale/backdrop animation on low-end hardware. Preserve reference hierarchy and interaction quality using stable outlines, tonal state and short feedback.

Published screenshots were located, but the web reader did not render the upstream image endpoints. Source-backed layout findings above are distinct from visual inspection; Fileway's own native rendered candidate still requires the planned emulator/device review.

## Confirmed projection and playback refinements

- No remote-control tutorials, marketing slogans or general performance disclaimers in routine UI. Keep real errors, exact states and recovery actions.
- The published JetStream screenshot collage was read as an actual image through the GitHub file connector. Wholphin playback composition was inspected at commit `6d029086e9e1c4b45a48bffaac9b39b8767f3b27`, in `PlaybackController.kt` and `PlaybackControls.kt`. Reuse that composition and focus hierarchy; Fileway implementation and coherent Material Symbols Rounded icons remain its own, without copying GPLv2 implementation code or third-party movie art.
- File cards have frameless stable 16:9 artwork, a thin focus outline and captions beneath; avoid nested padded panels. Unfocused navigation has no tile background.
- Beijing time sits at top-left; measured video transfer speed sits at top-right. No telemetry background. Each can remain visible or follow HUD visibility.
- Video prebuffer defaults to 2GiB of disk space, adjustable. RAM remains separately bounded. Display only measured cache coverage/decoder buffer; never turn VBR byte percentage into a fabricated time percentage.

## Locally inspected references

All three reference repositories were shallow-cloned outside Fileway source and pinned in the private research receipt. Wholphin HEAD `6d029086e9e1c4b45a48bffaac9b39b8767f3b27`: inspected local Theme.kt, BlueThemeColors.kt, BoldBlueThemeColors.kt, GridCard.kt, NavDrawer.kt, PlaybackOverlay.kt and transport/controller components. Jellyfin Android TV HEAD `e3f2cc02dbe484a1afde85bfce52a98d55267a2b`: read its packaged home/player/detail screenshots directly as images, plus current video control source. Packaged screenshots are project reference assets and do not establish current upstream runtime behavior. Official tv-samples HEAD recorded in the research receipt; JetStream screenshot collage was visually inspected. No cloned repository is vendored into Fileway and no upstream movie art is packaged.

For this projection-oriented build, use the reference's raised neutral surface levels as the normal canvas rather than an OLED-style near-black canvas. Player and image canvases retain media-appropriate black.

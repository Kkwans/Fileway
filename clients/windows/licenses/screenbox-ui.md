# Screenbox playback controls source adaptation

The native playback controls under `src/Fileway.App/Controls/Playback` use
Screenbox source at commit `6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07`.
This is a component-by-component WinUI 3 port, not the full Screenbox player,
and not a drop-in reuse of its LibVLC-backed view model.

Source retained and adapted:

- `ScreenboxButtonResources.xaml` transplants the `SubtleButtonStyle` control
  template, four native visual states, theme/high-contrast brush aliases and
  83 ms background transition from upstream `Styles/Button.xaml`. UWP contract
  conditionals are removed; hit targets are changed from 40/36 to 48 DIP and
  explicit keyboard focus visuals are added.
- `ScreenboxSpeedControl.xaml` ports the header/value/repeat-button/slider/
  rounded-preset structure of `PlaybackSpeedControl.xaml`; its code-behind is
  a new Fileway adapter. Range 0.125–5 and the native precise-number editor are
  Fileway additions.
- `ScreenboxVolumeControl.xaml` ports the toggle/slider/value structure of
  `VolumeControl.xaml`; its code-behind is a new Fileway adapter. LibVLC volume
  boost is omitted because Fileway's public contract is 0–100 percent.
- `ScreenboxTrackPicker.xaml` ports the native ListView, header/footer and
  `SingleSelectionFollowsFocus=False` structure of `CompositeTrackPicker.xaml`.
  Metadata presentation, engine-confirmed selection and local-subtitle event
  are Fileway adaptations; upstream's DI/view models are not copied.
- `ScreenboxChapterProgressBar` adapts proportional segments, one-DIP gaps and
  changed-segment updates from `ChapterProgressBar.xaml(.cs)`. Start-only
  Fileway chapter records and observed buffered extent replace ChapterCue;
  the SDK WinUI 3 ProgressBar template replaces the upstream UWP template.
  `ScreenboxSeekSlider` is a Fileway subclass of the SDK Slider: its progress
  layer is attached to `HorizontalTemplate` row 1, exactly the native track row,
  instead of centering a sibling layer against the whole Slider. Native thumb
  dimensions are retained; no visual-offset correction is added.
- `ScreenboxPlayerControls.xaml` adapts the seek-above-transport composition
  and separate primary/secondary groups, with native focus and the upstream
  Expanded/AdaptiveHidden/More overflow pattern (with Fileway actions/anchors).
  Its session wiring, seek race protection, visibility timer,
  subtitle settings and error handling are Fileway code, not copied Screenbox
  implementations.
  The immersive 80-DIP title-gradient composition follows `PlayerPage.xaml`;
  the minimize/maximize/close request buttons and local-DIP drag rectangle are
  Fileway's owned-window adapter. The `Screenbox.UI` TitleBar implementation
  and its native caption integration are not copied or claimed as working here.

Upstream references:

- [PlayerControls.xaml](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Controls/PlayerControls.xaml): seek-above-transport layout, native buttons, responsive tool grouping, track/speed flyouts.
- [PlayerPage.xaml](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Pages/PlayerPage.xaml): top/bottom gradient layers and high-contrast resource fallback.
- [SeekBar.xaml.cs](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Controls/SeekBar.xaml.cs): handled pointer registration and separate drag-preview/commit lifecycle.
- [PlaybackSpeedControl.xaml.cs](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Controls/PlaybackSpeedControl.xaml.cs): native speed editor and automation completion notification.
- [Styles/Button.xaml](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Styles/Button.xaml): full subtle-button template and states.
- [Styles/Flyout.xaml](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Styles/Flyout.xaml): native presenter inheritance and menu padding pattern.
- [PlaybackSpeedControl.xaml](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Controls/PlaybackSpeedControl.xaml), [VolumeControl.xaml](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Controls/VolumeControl.xaml), [CompositeTrackPicker.xaml](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Controls/CompositeTrackPicker.xaml), [ChapterProgressBar.xaml.cs](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/Screenbox/Controls/ChapterProgressBar.xaml.cs).

Upstream project: Screenbox, Tung Huynh and contributors.
These player files use the upstream root [GNU GPL version 3](https://github.com/huynhsontung/Screenbox/blob/6870eb0f9ac8ba7902eda53ccc9df8c537f3cc07/LICENSE), reproduced by the Windows component's `../LICENSE`.
They are not covered by the separate MIT license of the `Screenbox.UI` helper directory.

Modified for Fileway on 2026-10-10–11: port Windows.UI.Xaml to WinUI 3; remove
Screenbox-specific DI, services, playlist, casting and application resources;
bind the existing Fileway playback session; retain native focus, range controls
and confirmation-driven track/rate state; use 48 DIP targets and precise
0.125–5 speed input; add capability-gated subtitle settings and a shared
four-second visibility timer. Sprite and episode providers are not fabricated.

Material boundary: upstream's video chrome uses edge gradients; its
`AcrylicBrush BackgroundSource=Backdrop` is part of the in-app background-art
composition. Fileway's HWND video requires a separate native material boundary.
The owned WinUI HUD uses a window-level `NativePlayerAcrylicBackdrop`, based on
the SDK `DesktopAcrylicController` with Thin kind. Its native window region is
limited to measured top/footer bounds; the central movie area is excluded.
The edge gradients use translucent black contrast layers to limit bright-scene
washout without introducing transparent-white interpolation. Chrome keeps
its material when focus moves, while the SDK retains system transparency,
accessibility and capability fallback policy. Popups use the SDK's built-in
`DesktopAcrylicBackdrop` with Base kind when supported; disabling effects
restores the native `AcrylicInAppFillColorDefaultBrush` presenter style.
The failed WinUIEx/Win2D and SystemBackdropElement candidates are not loaded.
Native-video sampling, popup materials and platform fallback behavior require
separate runtime verification. Tint alone must not be described as verified
video blur. The pinned upstream has no
`Styles/Slider.xaml`: SeekBar uses the SDK `DefaultSliderStyle`; Fileway keeps
that native template and customizes supported resources.

No Screenbox screenshots, movie frames, branding, fonts, or LibVLC binaries are
copied into this component. Fluent SVG path source is recorded separately below.
Runtime media and other dependency licenses remain
separate from this source adaptation.

## Microsoft template notice

Upstream `Styles/Button.xaml` identifies Microsoft WinUI
[`Button_themeresources.xaml` v2.8.6](https://github.com/microsoft/microsoft-ui-xaml/blob/v2.8.6/dev/CommonStyles/Button_themeresources.xaml)
as the template source. The applicable MIT notice is retained below in addition
to the GPL-3.0 terms of the Screenbox adaptation:

MIT License

Copyright (c) Microsoft Corporation. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Microsoft Fluent System Icons SVG paths

Source: [microsoft/fluentui-system-icons](https://github.com/microsoft/fluentui-system-icons/tree/a347af95478f989038cccbf085978865264c048a), fixed commit `a347af95478f989038cccbf085978865264c048a`.
Only the required 20 Regular SVG paths are converted to WinUI Geometry resources. The native icon wrapper preserves each original 20×20 viewBox and uses one 20 DIP optical scale; no player font glyphs or mixed icon families are used. Source path data is unchanged; source fill is replaced by the native foreground brush. This copies icon assets, not the Fluent app/player implementation.

MIT License

Copyright (c) 2020 Microsoft Corporation

The full MIT permission and warranty text in the Microsoft template notice above also applies to these icons.

| Original 20 Regular SVG | SHA-256 |
| --- | --- |
| `add` | `fdf9fefaf083ff2349ac94afd47aab8ecb554650781c5797176b9d6ad3caee05` |
| `arrow_left` | `db86c904f14b430f6100b1c6bfe7e9b44fdc0ab375bcbbf9c74d371c87389009` |
| `book_open` | `902200bc2ca473ed6a3087029fb36638feeca826531e1f92a1d0da2a4a11cb96` |
| `chevron_down` | `56cb3f544124a67c79a5fa021ee35bb5488403a840bac1ae3291c5f6d71abccd` |
| `closed_caption` | `70d82e1a2f4ced57bb3465d76963de551ea7f16d94f04fa1efb179cf1ce239ae` |
| `dismiss` | `e84ad771fa8afba8e3ef52c98d9ab2d8ef4fc8e23c5954766a1c4e88fc13b355` |
| `full_screen_maximize` | `bcef45dfd2fc91dfef2e06079278330f774557a49451fe07b51834b1d65859ea` |
| `full_screen_minimize` | `22d70445c47ba6d76e8629cf8cb9f95fbed283d0d9ea57459a590d549a3effab` |
| `more_horizontal` | `5cc969f49ca33c856fb72804f9355e84db26dc6c23f2a1fc85995b67ad1c0c8d` |
| `music_note_2` | `96d841d4d8addf33481e364b65e6a9f5b710fb0aa3f737957421b929a4678327` |
| `pause` | `cad333d70ced0f6f63d0c17689df6d269b6e0698bfca2fcae994aa30c677dd61` |
| `play` | `0666c1378f63d4d5c32f91b4107086bc14799dff306027976be591da8843ae45` |
| `skip_back_10` | `129144c1fe23744ebf4e369470b886ae626f4f8853d4fda39b669e8e56474e9d` |
| `skip_forward_10` | `040d81ca76f1a5220b4a8a70c947e8c0049659d89188217562ccd5582390a9e5` |
| `speaker_0` | `c7ec714c4c70419168fc028c7896bf3fbc34e37683f4c7530ee5463bdf67b591` |
| `speaker_1` | `9b8bc9965fc7c71fa04c82df280e34402b3e5243b906dade9b5d4ef3d6fb7caf` |
| `speaker_2` | `42e7c3f32201be44f3252e263fedc2abf8c43da81c8b95ab7878795e3f3d53da` |
| `speaker_mute` | `c2734788021ec460bf579cbc6acdd87ea25f71e6aaecf15184a09a5025df082c` |
| `square_multiple` | `1531d8153716d09a5441adedb1b1fea4d2ee7484d407d2c368ffbf8d66b05709` |
| `square` | `6c9a157db4987d07e029f2fa0271f8b504fdae2e31423647defc0a4536d0130b` |
| `subtract` | `cdba492b0fe35494b23b8b0934a9b38af3b52058852777d4820ee8ced991c24f` |
| `text_font_size` | `cb6b86eef6726cfb6a90b81db866a31533a509c31d80184d8926a29634785bdc` |
| `top_speed` | `d3281f16e9ac16342139e953065ed5103bbc7047590257dcf3e0398dea2ef49e` |

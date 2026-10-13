# Artplayer assets and native design adaptation

The historical `art_*.xml` resource names now use Google Material Symbols Rounded
geometry, documented in [`material-symbols.md`](material-symbols.md). The SVG hash
table below records the original assets rather than the current vectors. The MIT
license and this native composition/design adaptation attribution are retained.

Source: https://github.com/zhw2590582/ArtPlayer/tree/40fcda6a37d0049d42e49c1e64e70d4fd9ba5f7f

Copyright (c) 2018 Harvey Zhao. MIT license is reproduced in `artplayer-MIT.txt` and packaged in the APK. SVG paths are converted without changes to Android VectorDrawable; SVG editor metadata is omitted. Dimensions/tint are set by the native control. No HTML5 player or web media pipeline is embedded.

The native control composition adapts `src/style/bottom.less`, `controls.less`, `progress.less`, `mobile.less` and `setting.less`: compact controls, thin timeline, quiet gradient and accent/check selection. Compose Slider/Dialog/selectable provide native interaction and accessibility; playback stays libVLC.

Original SVG SHA-256 (`packages/artplayer/src/icons/`):

| Asset | SHA-256 |
| --- | --- |
| play | c35562d20028d52ce329f35f90496a06d4dc5b6dc39df7ebcfb027f6f2393958 |
| pause | 9efe05ebf5610befddebcac51b3c9a491001189ff86204cb47928eb1617f6c7a |
| fullscreen-on | 2d71a366b612e29cb2d9cb23c05a75389f794d90646d28bb8a2ac740861a5daa |
| fullscreen-off | 185b49ff4b0383974cb15a8fda1ba413dc99a133d4a02a4b72c75381d52515f4 |
| check | 7d06c8797e3a71fa7439eda4ea5ae62378e1a974907733e1cdf413db4af99b0c |
| close | bb9e23e5305d11ec3d8f75a50ecdf3150deab8614c7df2af588873f2d78f06c2 |
| volume | baebc4ede3013202a4b13202a8d961cb0e5e065ee03733816022b2541d13884f |

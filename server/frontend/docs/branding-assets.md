# Web icon sources

The Web favicon, default header/login logo, PWA icons and Apple touch icon reuse Fileway's accepted
Android-authored mark and Windows optical recipe. Do not redraw or recolor them.

Run from the repository root with the contributor's existing Python and Pillow:

```powershell
python -B server/frontend/scripts/export-branding.py
```

The script imports `clients/windows/eng/Export-Branding.py` without writing
Python caches into that component. It renders Android's twelve foreground paths
and original blue gradient at 16× supersampling, using the accepted 1.45 centered
foreground scale and 24/108 rounded radius. PNG outputs are RGBA with transparent
rounded corners: 192×192, 512×512 and 180×180. The favicon/default logo SVG and seven-size ICO are copied
byte-for-byte from `clients/windows/src/Fileway.App/Assets`.

Source SHA-256 identities for this accepted export:

| Source | SHA-256 |
| --- | --- |
| `clients/windows/src/Fileway.App/Assets/WindowsAppMark.svg` | `feccc7dea7f3dc96202ec13431526fcf430c3c44b40933ce0eed43ecf97939cc` |
| `clients/windows/src/Fileway.App/Assets/AppIcon.ico` | `a8dc5e0b2c668c3738a61518e2422f75d8f024827921544c5bbe4529b9b29a0b` |
| `clients/android/app/src/main/res/drawable/ic_launcher_foreground.xml` | `776a8552fa2f688c1b58f06ce15a2fc578e782798f70d37e4a98bb07631e8e02` |
| `clients/android/app/src/main/res/drawable/ic_launcher_background.xml` | `afc27725b8a3940e852f349af48121968af32f452b80c6743ba0dd1a4a5fcc5d` |
| `clients/windows/eng/Export-Branding.py` | `f919c26db97983471d92320ffe799cb0e55ff626dfa8ec3d53e586a76c20cc9d` |

Dev and production templates reference all five assets with the stable cache
revision `fileway-feccc7dea7f3`, derived from the SVG SHA-256 prefix. After an
approved source change, rerun the script and update those revision queries in
both index templates and the static manifest. The generated report includes
output hashes, dimensions and alpha bounds.

Production keeps the server's `StaticURL` prefix. Dynamic manifest icons retain
`__prependStaticUrl`; its start URL retains the configured `BaseURL`. The static
manifest resolves both icon paths relative to itself and starts at `../`, so a
manifest served at `/base/static/manifest.json` opens `/base/`, while development
at `/manifest.json` opens `/`. User-defined application names, theme colors and
server branding file overrides remain separate settings.

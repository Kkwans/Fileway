"""Export Web icons from Fileway's accepted Android/Windows brand assets.

Uses the existing Windows vector renderer and installed Pillow; no source
brand geometry, color, platform asset or application setting is modified.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Import the shared renderer without leaving __pycache__ in Windows sources.
sys.dont_write_bytecode = True
REPOSITORY = Path(__file__).resolve().parents[3]
ICONS = REPOSITORY / "server/frontend/public/img/icons"
WINDOWS = REPOSITORY / "clients/windows/src/Fileway.App/Assets"
RENDERER = REPOSITORY / "clients/windows/eng/Export-Branding.py"
ANDROID = REPOSITORY / "clients/android/app/src/main/res/drawable"
SVG = "{http://www.w3.org/2000/svg}"
PNG_SIZES = {
    "android-chrome-192x192.png": 192,
    "android-chrome-512x512.png": 512,
    "apple-touch-icon.png": 180,
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    spec = importlib.util.spec_from_file_location("fileway_brand_renderer", RENDERER)
    if spec is None or spec.loader is None:
        raise RuntimeError("Existing Fileway vector renderer unavailable")
    renderer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(renderer)

    background = ET.parse(ANDROID / "ic_launcher_background.xml").getroot()
    foreground = ET.parse(ANDROID / "ic_launcher_foreground.xml").getroot()
    mark = ET.parse(WINDOWS / "WindowsAppMark.svg").getroot()
    authored = [(path.attrib[renderer.ANDROID + "fillColor"], path.attrib[renderer.ANDROID + "pathData"])
                for path in foreground.findall("path")]
    copied = [(path.attrib["fill"], path.attrib["d"]) for path in mark.findall(f"{SVG}g/{SVG}path")]
    if len(authored) != 12 or copied != authored:
        raise ValueError("Review changed Android/Windows brand geometry before export")
    if mark.find(f"{SVG}rect").attrib["rx"] != str(renderer.CORNER_RADIUS):
        raise ValueError("Windows optical radius does not match the shared renderer")
    expected_transform = f"translate(54 54) scale({renderer.FOREGROUND_SCALE}) translate(-54 -54)"
    if mark.find(f"{SVG}g").attrib["transform"] != expected_transform:
        raise ValueError("Windows optical scale does not match the shared renderer")

    # Rasterize authored vectors, not a resized existing PNG or an edited image.
    image = renderer.render(background, foreground, renderer.FOREGROUND_SCALE, renderer.CORNER_RADIUS)
    ICONS.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(WINDOWS / "WindowsAppMark.svg", ICONS / "favicon.svg")
    shutil.copyfile(WINDOWS / "WindowsAppMark.svg", ICONS.parent / "logo.svg")
    shutil.copyfile(WINDOWS / "AppIcon.ico", ICONS / "favicon.ico")
    results = []
    for name, edge in PNG_SIZES.items():
        output = ICONS / name
        renderer.sized_mark(image, (edge, edge)).save(output, optimize=True)
        with renderer.Image.open(output) as raster:
            if raster.size != (edge, edge) or raster.mode != "RGBA":
                raise ValueError("Unexpected Web icon dimensions or mode")
            corners = [(0, 0), (edge - 1, 0), (0, edge - 1), (edge - 1, edge - 1)]
            if any(raster.getpixel(point)[3] != 0 for point in corners) or raster.getchannel("A").getextrema() != (0, 255):
                raise ValueError("Web icons must retain the accepted transparent rounded corners")
        results.append({"asset": name, "size": [edge, edge], "mode": "RGBA", "alpha": [0, 255], "sha256": digest(output)})
    with renderer.Image.open(ICONS / "favicon.ico") as icon:
        if icon.ico.sizes() != set(renderer.ICO_SIZES):
            raise ValueError("Missing accepted native icon size")
        if any(icon.ico.getimage(size).getpixel((0, 0))[3] != 0 for size in renderer.ICO_SIZES):
            raise ValueError("ICO corners must remain transparent")
    print(json.dumps({
        "brand_revision": "fileway-" + digest(WINDOWS / "WindowsAppMark.svg")[:12],
        "byte_copies": {"favicon.svg": digest(ICONS / "favicon.svg"), "favicon.ico": digest(ICONS / "favicon.ico"),
                        "logo.svg": digest(ICONS.parent / "logo.svg")},
        "png_exports": results,
    }, indent=2))


if __name__ == "__main__":
    main()

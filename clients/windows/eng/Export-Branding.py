"""Export the authored Android vector mark with Windows optical sizing.

Requires Python and Pillow in the contributor's tool environment. This renders
vector geometry; it does not retouch a bitmap or change Android resources.
"""
from __future__ import annotations

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

REPOSITORY = Path(__file__).resolve().parents[3]
ASSETS = REPOSITORY / "clients/windows/src/Fileway.App/Assets"
SOURCE = REPOSITORY / "clients/android/app/src/main/res/drawable"
ANDROID = "{http://schemas.android.com/apk/res/android}"
VIEWPORT = 108
SUPERSAMPLE = 16
FOREGROUND_SCALE = 1.45
CORNER_RADIUS = 24
PNG_SIZES = {
    "LockScreenLogo.scale-200.png": (48, 48),
    "SplashScreen.scale-200.png": (1240, 600),
    "Square150x150Logo.scale-200.png": (300, 300),
    "Square44x44Logo.scale-200.png": (88, 88),
    "Square44x44Logo.targetsize-24_altform-unplated.png": (24, 24),
    "Square44x44Logo.targetsize-48_altform-lightunplated.png": (48, 48),
    "StoreLogo.png": (50, 50),
    "Wide310x150Logo.scale-200.png": (620, 300),
}
ICO_SIZES = [(n, n) for n in (16, 24, 32, 48, 64, 128, 256)]


def rgba(value: str) -> tuple[int, ...]:
    data = bytes.fromhex(value.lstrip("#"))
    if len(data) == 3:
        return tuple(data) + (255,)
    if len(data) == 4:
        alpha, red, green, blue = data
        return red, green, blue, alpha
    raise ValueError("Unsupported vector color")


def polygon(data: str, scale: float) -> list[tuple[float, float]]:
    # The brand consists of the original twelve straight-edged authored paths.
    tokens = re.findall(r"[A-Za-z]|-?\d+(?:\.\d+)?", data)
    points: list[tuple[float, float]] = []
    index = 0
    command = ""
    x = y = 0.0
    while index < len(tokens):
        if tokens[index].isalpha():
            command = tokens[index]
            if command not in ("M", "L", "H", "V", "Z"):
                raise ValueError("Unsupported source path command")
            index += 1
            if command == "Z":
                continue
        if command in ("M", "L"):
            x, y = map(float, tokens[index:index + 2])
            index += 2
        elif command == "H":
            x = float(tokens[index]); index += 1
        elif command == "V":
            y = float(tokens[index]); index += 1
        else:
            raise ValueError("Invalid source vector")
        tx, ty = ((value - 54) * scale + 54 for value in (x, y))
        if not (0 <= tx <= VIEWPORT and 0 <= ty <= VIEWPORT):
            raise ValueError("Optical transform clips the brand")
        points.append((tx * SUPERSAMPLE, ty * SUPERSAMPLE))
    return points


def render(background: ET.Element, foreground: ET.Element, scale: float, radius: int) -> Image.Image:
    gradient = background.find(".//gradient")
    if gradient is None:
        raise ValueError("Original gradient missing")
    start, end = (rgba(gradient.attrib[ANDROID + key]) for key in ("startColor", "endColor"))
    y0, y1 = (float(gradient.attrib[ANDROID + key]) for key in ("startY", "endY"))
    edge = VIEWPORT * SUPERSAMPLE
    image = Image.new("RGBA", (edge, edge))
    draw = ImageDraw.Draw(image)
    for y in range(edge):
        fraction = max(0, min(1, ((y + 0.5) / SUPERSAMPLE - y0) / (y1 - y0)))
        color = tuple(round(a + (b - a) * fraction) for a, b in zip(start, end))
        draw.line((0, y, edge - 1, y), fill=color)
    mask = Image.new("L", (edge, edge))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, edge - 1, edge - 1), radius=radius * SUPERSAMPLE, fill=255)
    image.putalpha(mask)
    for path in foreground.findall("path"):
        draw.polygon(polygon(path.attrib[ANDROID + "pathData"], scale), fill=rgba(path.attrib[ANDROID + "fillColor"]))
    return image


def sized_mark(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    mark = image.resize(size, Image.Resampling.LANCZOS)
    # Remove sub-one-percent Lanczos ringing outside the vector's transparent
    # silhouette; keep the visible anti-aliased rounded edge intact.
    mark.putalpha(mark.getchannel("A").point(lambda value: 0 if value < 4 else value))
    return mark


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview-dir", type=Path)
    args = parser.parse_args()
    background = ET.parse(SOURCE / "ic_launcher_background.xml").getroot()
    foreground = ET.parse(SOURCE / "ic_launcher_foreground.xml").getroot()
    if len(foreground.findall("path")) != 12:
        raise ValueError("Review changed Android brand geometry before export")
    image = render(background, foreground, FOREGROUND_SCALE, CORNER_RADIUS)
    for name, dimensions in PNG_SIZES.items():
        edge = 400 if name.startswith("SplashScreen") else min(dimensions)
        mark = sized_mark(image, (edge, edge))
        canvas = Image.new("RGBA", dimensions)
        canvas.alpha_composite(mark, ((dimensions[0] - edge) // 2, (dimensions[1] - edge) // 2))
        canvas.save(ASSETS / name, optimize=True)
    frames = [sized_mark(image, size) for size in ICO_SIZES]
    frames[-1].save(ASSETS / "AppIcon.ico", format="ICO", sizes=ICO_SIZES,
                    append_images=frames[:-1], bitmap_format="bmp")
    with Image.open(ASSETS / "AppIcon.ico") as ico:
        if ico.ico.sizes() != set(ICO_SIZES):
            raise ValueError("Missing native icon size")
        if any(ico.ico.getimage(size).getpixel((0, 0))[3] != 0 for size in ICO_SIZES):
            raise ValueError("Rounded icon corners must be transparent")
    paths = "\n".join(f'    <path fill="{path.attrib[ANDROID + "fillColor"]}" d="{path.attrib[ANDROID + "pathData"]}"/>'
                      for path in foreground.findall("path"))
    master = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
  <title>Fileway Windows optical brand export</title>
  <!-- Original Android mark/colors; Windows-only scale 1.45 and 24-unit radius. -->
  <defs><linearGradient id="blue" gradientUnits="userSpaceOnUse" x1="54" y1="18" x2="54" y2="90">
    <stop stop-color="#1C66FB"/><stop offset="1" stop-color="#56A1F5"/>
  </linearGradient></defs>
  <rect width="108" height="108" rx="24" fill="url(#blue)"/>
  <g transform="translate(54 54) scale(1.45) translate(-54 -54)">
{paths}
  </g>
</svg>
'''
    (ASSETS / "WindowsAppMark.svg").write_text(master, encoding="utf-8")
    if args.preview_dir:
        output = args.preview_dir.resolve()
        if output == REPOSITORY or REPOSITORY in output.parents:
            raise ValueError("Visual evidence must stay outside Git")
        output.mkdir(parents=True, exist_ok=True)
        previous = render(background, foreground, 1, 0)
        preview = Image.new("RGB", (900, 430), "#17191F")
        draw = ImageDraw.Draw(preview)
        for column, (label, mark) in enumerate((("Previous", previous), ("Windows optical", image))):
            x = 32 + column * 450
            draw.text((x, 18), label, fill="white")
            preview.paste(mark.resize((240, 240), Image.Resampling.LANCZOS), (x, 55), mark.resize((240, 240), Image.Resampling.LANCZOS))
            for index, size in enumerate((16, 24, 32, 48, 64)):
                tile = mark.resize((size, size), Image.Resampling.LANCZOS)
                preview.paste(tile, (x + index * 76, 335 - size // 2), tile)
        preview.save(output / "windows-icon-optical-comparison.png")
    print("Exported 8 PNGs, 7 ICO sizes and the Windows SVG master; Android source unchanged.")


if __name__ == "__main__":
    main()

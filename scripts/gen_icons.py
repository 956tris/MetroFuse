#!/usr/bin/env python3
"""Export the MetroFuse brand pack and platform icons.

Install Pillow and resvg-py, then run python scripts/gen_icons.py.
"""

import argparse
from io import BytesIO
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from PIL import Image, ImageDraw, ImageFont
from resvg_py import svg_to_bytes


ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "assets/app-icons/metrofuse"
ANDROID = ROOT / "androidApp/src/main/res"
DESKTOP = ROOT / "desktopApp/src/desktopMain/resources"
IOS = ROOT / "iosApp/Assets.xcassets"
SIZE = 640

# Circle centres traced from the supplied mark, with its visual bounds centred on the canvas.
LETTERS = (
    (98.5, 286.5, 17), (98.4, 321.4, 17.1), (97.75, 356.5, 17.1),
    (97.75, 391.5, 17.1), (97.65, 426.4, 17), (133.5, 320.75, 17.1), (169.1, 286.5, 17),
    (203.35, 321.5, 17.35), (203.35, 356.5, 17.35), (203.35, 391.5, 17.35), (203.35, 426.45, 17.35),
    (238.25, 321.5, 17.1), (272.6, 286.5, 17),
    (308.25, 321.5, 17.1), (308.25, 356.5, 17.1), (308.25, 391.5, 17.1), (308.25, 426.5, 17.1),
)
NOTE = (
    (480.9, 213.65, 18), (515.9, 221.75, 18), (541.85, 246.5, 18), (464.1, 245.4, 18),
    (428.65, 281.5, 18), (464.4, 281.35, 18.5), (500.5, 282.65, 18), (525.35, 308.6, 18),
    (463.1, 317.85, 18.4), (463.75, 353.85, 18.25), (463.65, 389.6, 18),
    (432.5, 371.9, 18), (432.5, 407.65, 18), (403.25, 351.75, 18),
    (373.65, 371.9, 18), (373.65, 407.65, 18), (403.25, 427.35, 17.9),
)
PRIDE = ("#FF6572", "#FFAD65", "#FFE374", "#65E6B0", "#78BFFF", "#CEA3FF")

# IDs match existing settings so installed alternate icons retain their selections.
THEMES = {
    "original": ("Original", ("#2F2F2F",), "#FFFFFF", "#EE3231", "AppIcon"),
    "arctic_haze": ("Arctic Haze", ("#142636", "#225470", "#122938"), "#E6F8FF", "#67DFFF", "ARCTIC_HAZE"),
    "lavender_leap": ("Lavender Leap", ("#291939", "#473363", "#241836"), "#F5ECFF", "#D1A9FF", "LAVENDER_LEAP"),
    "metromallow": ("Metromallow", ("#FFF3E8", "#F9D1DB", "#F6C2AE"), "#48243C", "#A8224F", "METROMALLOW"),
    "pride_special": ("Pride Special", ("#182039", "#302347", "#1C1732"), "#FFF8FF", PRIDE, "PRIDE_SPECIAL"),
    "static": ("Static", ("#1A1D23", "#3A424E", "#20262F"), "#F2F5F8", "#A9B5C6", "STATIC"),
    "zenith_black": ("Zenith Black", ("#FFFFFF",), "#161616", "#161616", "ZENITH_BLACK"),
    "zenith_white": ("Zenith White", ("#111111",), "#FFFFFF", "#FFFFFF", "ZENITH_WHITE"),
    "gradient": ("Gradient", ("#351633", "#612B5D", "#241C50"), "#FFF3FB", "#FF7998", "GRADIENT"),
}


def dots(letters, note):
    for circle in LETTERS:
        yield circle, letters
    for circle in NOTE:
        color = note if isinstance(note, str) else note[min(len(note) - 1, int((circle[1] - 213) / 39))]
        yield circle, color


def compact_note(color):
    for x, y, radius in NOTE:
        yield (SIZE / 2 + (x - 461) * 1.9, SIZE / 2 + (y - 320) * 1.9, radius * 1.9), color


def svg(groups=(), background=(), rounded=False, foreground_scale=1):
    lines = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}" viewBox="0 0 {SIZE} {SIZE}">']
    if background:
        fill = background[0]
        if len(background) > 1:
            lines.extend([
                '  <defs>',
                '    <linearGradient id="background" x2="100%" y2="100%">',
                *(f'      <stop offset="{index / (len(background) - 1):g}" stop-color="{color}"/>' for index, color in enumerate(background)),
                '    </linearGradient>',
                '  </defs>',
            ])
            fill = "url(#background)"
        radius = ' rx="136"' if rounded else ""
        lines.append(f'  <rect width="{SIZE}" height="{SIZE}"{radius} fill="{fill}"/>')
    for (cx, cy, radius), color in groups:
        cx = SIZE / 2 + (cx - SIZE / 2) * foreground_scale
        cy = SIZE / 2 + (cy - SIZE / 2) * foreground_scale
        lines.append(f'  <circle cx="{cx:.4f}" cy="{cy:.4f}" r="{radius * foreground_scale:.4f}" fill="{color}"/>')
    return "\n".join([*lines, "</svg>", ""])


def render(document, size):
    png = svg_to_bytes(svg_string=document, width=size, height=size, skip_system_fonts=True)
    return Image.open(BytesIO(png)).convert("RGBA")


def text_file(path, content):
    path.write_text(content, encoding="utf-8", newline="\n")


def android_vector(path, groups, dp=108, mark_scale=0.735):
    colors = {}
    for (cx, cy, radius), color in groups:
        x, y = SIZE / 2 + (cx - SIZE / 2) * mark_scale, SIZE / 2 + (cy - SIZE / 2) * mark_scale
        r = radius * mark_scale
        circle = f"M{x-r:.4f},{y:.4f}a{r:.4f},{r:.4f} 0 1,0 {2*r:.4f},0a{r:.4f},{r:.4f} 0 1,0 {-2*r:.4f},0z"
        colors.setdefault(color, []).append(circle)
    paths = "\n".join(
        f'    <path\n        android:fillColor="{color}"\n        android:pathData="{" ".join(circles)}" />'
        for color, circles in colors.items()
    )
    text_file(
        ANDROID / "drawable" / (path + ".xml"),
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{dp}dp"\n    android:height="{dp}dp"\n'
        f'    android:viewportWidth="{SIZE}"\n    android:viewportHeight="{SIZE}">\n{paths}\n</vector>\n',
    )


def android_background(path, palette):
    if len(palette) == 1:
        fill = f'<solid android:color="{palette[0]}" />'
    else:
        start, center, end = palette
        fill = (
            '<gradient android:angle="315" '
            f'android:startColor="{start}" android:centerColor="{center}" android:endColor="{end}" />'
        )
    text_file(
        ANDROID / "drawable" / (path + ".xml"),
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
        f"    {fill}\n</shape>\n",
    )


def write_android_theme(name, palette, glyph, full):
    nodpi = ANDROID / "drawable-nodpi"
    prefix = f"app_icon_{name}"
    if name == "original":
        android_background(prefix + "_background", palette)
        android_vector("ic_launcher_foreground", glyph)
    if name in {"original", "gradient", "zenith_black", "zenith_white"}:
        render(full, 512).save(nodpi / f"{prefix}_full.webp", lossless=True)
    if name in {"zenith_black", "zenith_white"}:
        android_background(prefix + "_background", palette)
    if name == "gradient":
        android_background(prefix + "_background", palette)
        render(svg(glyph, foreground_scale=0.735), 512).save(nodpi / f"{prefix}_foreground.webp", lossless=True)
        return
    if name != "original":
        render(svg(glyph), 512).save(nodpi / f"{prefix}_foreground.webp", lossless=True)
        android_vector(prefix + "_adaptive_foreground", glyph)
    if name not in {"original", "zenith_black", "zenith_white"}:
        render(svg(background=palette), 512).save(nodpi / f"{prefix}_background.webp", lossless=True)


def write_preview():
    sheet = Image.new("RGB", (1008, 1008), "#18191E")
    draw = ImageDraw.Draw(sheet)
    title = ImageFont.load_default(size=38)
    subtitle = ImageFont.load_default(size=18)
    label = ImageFont.load_default(size=22)
    draw.text((42, 34), "MetroFuse", font=title, fill="#FFFFFF")
    draw.text((44, 85), "Logo and launcher themes", font=subtitle, fill="#BBC0CC")
    for index, (name, (title, *_)) in enumerate(THEMES.items()):
        row, column = divmod(index, 3)
        x, y = 42 + column * 324, 134 + row * 285
        draw.rounded_rectangle((x, y, x + 270, y + 251), 25, fill="#23252E")
        tile = render((PACK / f"{name}.svg").read_text(encoding="utf-8"), 170)
        sheet.paste(tile, (x + 50, y + 21), tile)
        draw.text((x + 135, y + 217), title, font=label, fill="#F6F7FC", anchor="mm")
    sheet.save(PACK / "preview.png", optimize=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zip", type=Path, help="also export a portable SVG pack ZIP")
    args = parser.parse_args()

    PACK.mkdir(exist_ok=True)
    for name, (_, palette, letters, note, ios_icon) in THEMES.items():
        glyph = list(dots(letters, note))
        tile = svg(glyph, palette, rounded=True)
        full = svg(glyph, palette)
        text_file(PACK / f"{name}.svg", tile)
        render(full, 1024).convert("RGB").save(IOS / f"{ios_icon}.appiconset/AppIcon.png", optimize=True)
        write_android_theme(name, palette, glyph, full)

    _, palette, letters, note, _ = THEMES["original"]
    mark = svg(dots(letters, note))
    light_mark = svg(dots("#FFFFFF", "#FFFFFF"))
    dark_mark = svg(dots("#161616", "#161616"))
    tile = svg(dots(letters, note), palette, rounded=True)
    for name, document in [
        ("mark", mark), ("mark-light", light_mark), ("mark-dark", dark_mark),
        ("original-square", svg(dots(letters, note), palette)),
        ("note", svg(compact_note(note))),
        ("note-light", svg(compact_note("#FFFFFF"))),
        ("note-dark", svg(compact_note("#161616"))),
    ]:
        text_file(PACK / f"{name}.svg", document)

    android_vector("ic_launcher_monochrome", dots("#FFFFFF", "#FFFFFF"))
    android_vector("app_icon_gradient_monochrome", dots("#FFFFFF", "#FFFFFF"))
    android_vector("ic_notification", compact_note("#FFFFFF"), dp=24, mark_scale=1.03)

    text_file(DESKTOP / "logo.svg", tile)
    text_file(ROOT / "flatpak/com.metrolist.desktop.svg", tile)
    render(tile, 512).save(DESKTOP / "logo.png", optimize=True)
    render(tile, 512).save(ROOT / "flatpak/com.metrolist.desktop.png", optimize=True)
    render(svg(compact_note("#161616")), 512).save(DESKTOP / "logo_dark.png", optimize=True)
    sizes = (16, 24, 32, 48, 64, 128, 256)
    compact_tile = svg(compact_note(note), palette, rounded=True)
    render(tile, sizes[-1]).save(
        DESKTOP / "logo.ico",
        sizes=[(size, size) for size in sizes],
        append_images=[render(compact_tile if size <= 24 else tile, size) for size in sizes[:-1]],
    )
    render(tile, 1024).save(DESKTOP / "logo.icns")
    render(mark, 512).save(ROOT / "shared/src/commonMain/composeResources/drawable/logo.png", optimize=True)
    store_icon = render(svg(dots(letters, note), palette), 512).convert("RGB")
    store_icon.save(ROOT / "assets/ic_launcher-playstore.png", optimize=True)
    store_icon.save(ROOT / "fastlane/metadata/android/en-US/images/icon.png", optimize=True)

    _, palette, letters, note, _ = THEMES["gradient"]
    for name, document in [
        ("full", svg(dots(letters, note), palette)),
        ("background", svg(background=palette)),
        ("foreground", svg(dots(letters, note), foreground_scale=0.735)),
        ("monochrome", svg(dots("#FFFFFF", "#FFFFFF"), foreground_scale=0.735)),
    ]:
        text_file(ROOT / f"assets/app-icons/gradient/{name}.svg", document)
    write_preview()

    if args.zip:
        with ZipFile(args.zip, "w", ZIP_DEFLATED) as archive:
            for path in sorted(PACK.iterdir()):
                archive.write(path, f"MetroFuse-Logo-Pack/{path.name}")


if __name__ == "__main__":
    main()

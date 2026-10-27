#!/usr/bin/env python3
"""Genererer butiksgrafikken (feature graphic, 1024x500) til Google Play og F-Droid på alle fem sprog.
Papirarket har samme geometri og farver som ikonet i make_icon.py; undertitlen er short_description.txt.

Brug (kræver Pillow og Noto Serif/Sans):  python3 tools/make_feature_graphic.py
Skriver fastlane/metadata/android/<sprog>/images/featureGraphic.png."""
import math, os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
META = os.path.join(ROOT, "fastlane", "metadata", "android")
LANGS = ["en-US", "da-DK", "de-DE", "fr-FR", "es-ES"]
FONTS = "/usr/share/fonts/truetype/noto"
TITLE_FONT = f"{FONTS}/NotoSerif-Bold.ttf"
TEXT_FONT = f"{FONTS}/NotoSans-Regular.ttf"

W, H = 1024, 500
S = 3  # supersampling

# Farver og papirets geometri som i make_icon.py (108x108-koordinater)
BG = "#F3BE4D"
PAPER = "#FFFBF5"
FOLD = "#E9CF8F"
INK = "#7A5900"
INK2 = "#B08A3A"
SHADOW = (0, 0, 0, 0x26)
TITLE = "#3D2C00"    # mørkere end INK, så teksten kan læses på ravfarven
TEXT = "#4F3A00"

L, R, T, B, r, f = 34, 74, 28, 80, 5, 12
LINES = [
    (41, 60, 44, 5.0, INK),
    (41, 67, 55, 3.6, INK2),
    (41, 67, 63, 3.6, INK2),
    (41, 58, 71, 3.6, INK2),
]

def arc(cx, cy, rad, a0, a1, n=24):
    return [(cx + rad * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + rad * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]

def draw_paper(img, ox, oy, k):
    """Tegner papirarket med øverste venstre hjørne af 108-feltet i (ox, oy) og k pixels pr. enhed."""
    tr = lambda pts, dy=0: [(ox + x * k, oy + (y + dy) * k) for x, y in pts]
    paper = [(L + r, T), (R - f, T), (R, T + f), (R, B - r)]
    paper += arc(R - r, B - r, r, 0, 90) + [(L + r, B)]
    paper += arc(L + r, B - r, r, 90, 180) + [(L, T + r)]
    paper += arc(L + r, T + r, r, 180, 270)
    fold = [(R - f, T), (R - f, T + f - 3)] + arc(R - f + 3, T + f - 3, 3, 180, 90) + [(R, T + f)]

    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).polygon(tr(paper, 1.5), fill=SHADOW)
    img.alpha_composite(sh)
    dr = ImageDraw.Draw(img)
    dr.polygon(tr(paper), fill=PAPER)
    dr.polygon(tr(fold), fill=FOLD)
    for x1, x2, y, h, c in LINES:
        dr.rounded_rectangle([ox + x1 * k, oy + (y - h / 2) * k, ox + x2 * k, oy + (y + h / 2) * k],
                             radius=h / 2 * k, fill=c)

def wrap(dr, text, font, width):
    lines, cur = [], ""
    for word in text.split():
        test = f"{cur} {word}".strip()
        if dr.textlength(test, font=font) <= width or not cur:
            cur = test
        else:
            lines.append(cur)
            cur = word
    return lines + [cur] if cur else lines

def render(tagline):
    img = Image.new("RGBA", (W * S, H * S), BG)
    # Papiret fylder ca. 330 px i højden (52 enheder), centreret lodret til venstre
    k = 330 * S / (B - T)
    cx, cy = 250 * S, H * S / 2
    draw_paper(img, cx - (L + R) / 2 * k, cy - (T + B) / 2 * k, k)

    dr = ImageDraw.Draw(img)
    title_font = ImageFont.truetype(TITLE_FONT, 104 * S)
    text_font = ImageFont.truetype(TEXT_FONT, 34 * S)
    x, text_w = 450 * S, 520 * S
    lines = wrap(dr, tagline, text_font, text_w)
    title_h = 104 * S
    line_h = 46 * S
    gap = 28 * S
    block = title_h + gap + line_h * len(lines)
    y = (H * S - block) / 2
    dr.text((x, y + title_h), "DavNotes", font=title_font, fill=TITLE, anchor="ls")
    y += title_h + gap
    for line in lines:
        dr.text((x, y + 34 * S), line, font=text_font, fill=TEXT, anchor="ls")
        y += line_h
    return img.resize((W, H), Image.LANCZOS).convert("RGB")

for lang in LANGS:
    with open(os.path.join(META, lang, "short_description.txt"), encoding="utf-8") as fh:
        tagline = fh.read().strip()
    out = os.path.join(META, lang, "images", "featureGraphic.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    render(tagline).save(out, "PNG", optimize=True)
    print("ok", out)

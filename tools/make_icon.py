#!/usr/bin/env python3
"""Genererer DavNotes-ikonet: adaptive vektorer (res/drawable) + WebP til API 24-25 (res/mipmap-*).
Geometrien er i det adaptive ikons 108x108-koordinater; synlig zone er midten 72x72, sikker zone r=33.

Brug (kræver Pillow):  python3 tools/make_icon.py [forhåndsvisning.png]
Overskriver ikonfilerne i app/src/main/res og butiksikonet (512x512) til F-Droid i fastlane/. Med et filnavn gemmes også en forhåndsvisning
(firkantet, rund, tematiseret og lille)."""
import math, sys, os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")
STORE_ICON = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "icon.png")
PREVIEW = sys.argv[1] if len(sys.argv) > 1 else None

BG = "#F3BE4D"       # varm rav, som temaets primærfarve i mørk tilstand
PAPER = "#FFFBF5"
FOLD = "#E9CF8F"
INK = "#7A5900"      # temaets primærfarve i lys tilstand
INK2 = "#B08A3A"
SHADOW = (0, 0, 0, 0x26)

L, R, T, B, r, f = 34, 74, 28, 80, 5, 12
LINES = [  # (x1, x2, y, tykkelse, farve)
    (41, 60, 44, 5.0, INK),
    (41, 67, 55, 3.6, INK2),
    (41, 67, 63, 3.6, INK2),
    (41, 58, 71, 3.6, INK2),
]

# ---------- vektor ----------
def paper_path(dx=0.0, dy=0.0):
    p = lambda x, y: f"{x + dx:g},{y + dy:g}"
    return (f"M{p(L + r, T)} L{p(R - f, T)} L{p(R, T + f)} L{p(R, B - r)} "
            f"A{r},{r} 0 0 1 {p(R - r, B)} L{p(L + r, B)} A{r},{r} 0 0 1 {p(L, B - r)} "
            f"L{p(L, T + r)} A{r},{r} 0 0 1 {p(L + r, T)} Z")

FOLD_PATH = f"M{R - f},{T} L{R - f},{T + f - 3} A3,3 0 0 0 {R - f + 3},{T + f} L{R},{T + f} Z"

def line_path(x1, x2, y, h):
    k = h / 2
    return (f"M{x1 + k:g},{y - k:g} L{x2 - k:g},{y - k:g} A{k:g},{k:g} 0 0 1 {x2 - k:g},{y + k:g} "
            f"L{x1 + k:g},{y + k:g} A{k:g},{k:g} 0 0 1 {x1 + k:g},{y - k:g} Z")

def vector(paths):
    body = "\n".join(
        f'    <path\n        android:fillColor="{c}"{extra}\n        android:pathData="{d}" />'
        for d, c, extra in paths)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f"{body}\n</vector>\n")

fg = [(paper_path(0, 1.5), "#000000", '\n        android:fillAlpha="0.15"'),
      (paper_path(), PAPER, ""),
      (FOLD_PATH, FOLD, "")]
fg += [(line_path(x1, x2, y, h), c, "") for x1, x2, y, h, c in LINES]

# Monokrom (tematiserede ikoner): papiret som silhuet med fold og linjer skåret ud
mono = [(paper_path() + " " + FOLD_PATH + " " + " ".join(line_path(x1, x2, y, h) for x1, x2, y, h, _ in LINES),
         "#000000", '\n        android:fillType="evenOdd"')]

bg = [("M0,0h108v108h-108z", BG, "")]

d = f"{RES}/drawable"
open(f"{d}/ic_launcher_foreground.xml", "w").write(vector(fg))
open(f"{d}/ic_launcher_background.xml", "w").write(vector(bg))
open(f"{d}/ic_launcher_monochrome.xml", "w").write(vector(mono))

# ---------- raster (samme geometri) ----------
S = 24  # pixels pr. enhed ved supersampling

def arc(cx, cy, rad, a0, a1, n=16):
    return [(cx + rad * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + rad * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]

def paper_poly(dx=0, dy=0):
    pts = [(L + r, T), (R - f, T), (R, T + f), (R, B - r)]
    pts += arc(R - r, B - r, r, 0, 90)
    pts += [(L + r, B)]
    pts += arc(L + r, B - r, r, 90, 180)
    pts += [(L, T + r)]
    pts += arc(L + r, T + r, r, 180, 270)
    return [((x + dx) * S, (y + dy) * S) for x, y in pts]

def fold_poly():
    pts = [(R - f, T), (R - f, T + f - 3)] + arc(R - f + 3, T + f - 3, 3, 180, 90) + [(R, T + f)]
    return [(x * S, y * S) for x, y in pts]

def render_full():
    img = Image.new("RGBA", (108 * S, 108 * S), BG)
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).polygon(paper_poly(0, 1.5), fill=SHADOW)
    img = Image.alpha_composite(img, sh)
    dr = ImageDraw.Draw(img)
    dr.polygon(paper_poly(), fill=PAPER)
    dr.polygon(fold_poly(), fill=FOLD)
    for x1, x2, y, h, c in LINES:
        dr.rounded_rectangle([x1 * S, (y - h / 2) * S, x2 * S, (y + h / 2) * S], radius=h / 2 * S, fill=c)
    return img

full = render_full()
visible = full.crop((18 * S, 18 * S, 90 * S, 90 * S))  # det synlige 72x72-område

def masked(img, shape):
    m = Image.new("L", img.size, 0)
    md = ImageDraw.Draw(m)
    w = img.size[0]
    if shape == "round":
        md.ellipse([0, 0, w - 1, w - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, w - 1, w - 1], radius=int(w * 0.22), fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out

legacy = masked(visible, "square")
legacy_round = masked(visible, "round")
for dens, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    folder = f"{RES}/mipmap-{dens}"
    legacy.resize((px, px), Image.LANCZOS).save(f"{folder}/ic_launcher.webp", "WEBP", lossless=True)
    legacy_round.resize((px, px), Image.LANCZOS).save(f"{folder}/ic_launcher_round.webp", "WEBP", lossless=True)
os.makedirs(os.path.dirname(STORE_ICON), exist_ok=True)
legacy.resize((512, 512), Image.LANCZOS).save(STORE_ICON, "PNG", optimize=True)

if PREVIEW is None:
    sys.exit(0)

# ---------- forhåndsvisning: firkant, cirkel, lille størrelse og tematiseret ----------
def themed():
    img = Image.new("RGBA", (108 * S, 108 * S), "#1F2A44")
    m = Image.new("L", img.size, 0)
    dr = ImageDraw.Draw(m)
    dr.polygon(paper_poly(), fill=255)
    dr.polygon(fold_poly(), fill=0)
    for x1, x2, y, h, _ in LINES:
        dr.rounded_rectangle([x1 * S, (y - h / 2) * S, x2 * S, (y + h / 2) * S], radius=h / 2 * S, fill=0)
    img.paste(Image.new("RGBA", img.size, "#C8D6FF"), (0, 0), m)
    return masked(img.crop((18 * S, 18 * S, 90 * S, 90 * S)), "round")

P = 288
canvas = Image.new("RGBA", (P * 4 + 50, P + 20), "#FAFAFA")
for i, im in enumerate([legacy, legacy_round, themed()]):
    canvas.alpha_composite(im.resize((P, P), Image.LANCZOS), (10 + i * (P + 10), 10))
small = legacy_round.resize((48, 48), Image.LANCZOS)
canvas.alpha_composite(small, (10 + 3 * (P + 10) + 20, 10 + P // 2 - 24))
canvas.save(PREVIEW)
print("ok", PREVIEW)

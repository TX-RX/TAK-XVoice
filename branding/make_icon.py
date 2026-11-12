"""Generate the Texas-TAK branded icon set for TAK-XVoice.

Run: python branding/make_icon.py            # writes into the repo
     python branding/make_icon.py <out_root>  # writes under <out_root> (preview)

This script is the single source of truth for the plugin's brand artwork.
Running it reproduces every committed raster asset, so the brand stays
reproducible from code rather than from hand-edited PNGs. There are two
distinct marks:

  1. The gold-on-near-black "geometric walkie-talkie" — the home-screen
     launcher icon and the portal/upload master.
       - branding/xv-icon.png                         (512, master)
       - app/src/main/res/mipmap-{m,h,xh,xxh,xxx}dpi/ic_launcher.png
  2. The Texas-TAK brand mark — a honeycomb hex grid (TAK), an audio
     waveform (the voice layer) and a bee tying them together — drawn as
     a white silhouette on transparency so ATAK's title-bar tint colors
     it with the brand gold.
       - app/src/main/res/drawable/xv_tool_icon.png   (96)

Both marks are drawn on a 4x-supersampled canvas and downscaled with
LANCZOS so the strokes stay crisp at every density. Colors are the
canonical Texas-TAK tokens (see app/src/main/res/values/colors.xml):
accent gold #F2B33D on near-black bg #070707.
"""
import math
import os
import sys

from PIL import Image, ImageDraw

# --- brand tokens -----------------------------------------------------------
GOLD = (242, 179, 61, 255)     # xv_accent  #F2B33D
NEAR_BLACK = (7, 7, 7, 255)    # xv_bg      #070707
WHITE = (255, 255, 255, 255)   # tinted to gold by the title bar at runtime

SS = 4                          # supersampling factor
BASE = 512                      # master/logical size
C = BASE * SS                   # supersampled canvas edge

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))
OUT_ROOT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else REPO_ROOT
RES = os.path.join(OUT_ROOT, "app", "src", "main", "res")
MASTER_OUT = os.path.join(OUT_ROOT, "branding", "xv-icon.png")

MIPMAP_DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def _save(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path, "PNG", optimize=True)
    print(f"wrote {path} ({size}px)")


# --- mark 1: geometric walkie-talkie (gold line-art on near-black) ----------
def draw_launcher():
    """Return a BASE*SS RGBA image of the gold walkie-talkie on near-black."""
    img = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = SS  # shorthand: logical->canvas scale

    # rounded-square background
    d.rounded_rectangle((0, 0, C, C), radius=96 * s, fill=NEAR_BLACK)

    stroke = 15 * s

    # body: a vertical beveled rectangle (octagon silhouette), gold outline
    left, right, top, bottom, cut = 182 * s, 352 * s, 150 * s, 388 * s, 34 * s
    body = [
        (left + cut, top), (right - cut, top), (right, top + cut),
        (right, bottom - cut), (right - cut, bottom), (left + cut, bottom),
        (left, bottom - cut), (left, top + cut),
    ]
    d.line(body + [body[0]], fill=GOLD, width=stroke, joint="curve")

    # antenna mast + knob, rising from the body's upper-left shoulder
    ant_base = (left + 36 * s, top + 4 * s)
    ant_top = (left + 10 * s, top - 52 * s)
    d.line([ant_base, ant_top], fill=GOLD, width=stroke)
    knob_r = 13 * s
    d.ellipse((ant_top[0] - knob_r, ant_top[1] - knob_r,
               ant_top[0] + knob_r, ant_top[1] + knob_r), fill=GOLD)

    # three nested signal chevrons fanning up-right from the antenna tip
    cx, cy = ant_top
    for i, r in enumerate((40 * s, 70 * s, 100 * s)):
        d.arc((cx - r, cy - r, cx + r, cy + r), start=-70, end=5,
              fill=GOLD, width=max(stroke - 2 * s, 8 * s))

    # channel knob: short gold stub off the top-right shoulder
    kx, ky = right - cut + 6 * s, top + cut - 2 * s
    d.line([(kx, ky), (kx + 20 * s, ky - 16 * s)], fill=GOLD, width=stroke)

    # PTT bump: small rounded outline on the left flank
    px0, py0, px1, py1 = left - 22 * s, top + 92 * s, left + 2 * s, top + 182 * s
    d.rounded_rectangle((px0, py0, px1, py1), radius=8 * s, outline=GOLD,
                        width=stroke)

    # speaker waveform: a zig-zag (M/W) across the upper body
    wy = top + 86 * s
    wx0, wx1 = left + 24 * s, right - 24 * s
    span = wx1 - wx0
    zig = [
        (wx0, wy + 18 * s),
        (wx0 + span * 0.25, wy - 18 * s),
        (wx0 + span * 0.50, wy + 14 * s),
        (wx0 + span * 0.75, wy - 18 * s),
        (wx1, wy + 18 * s),
    ]
    d.line(zig, fill=GOLD, width=stroke, joint="curve")

    # grille: three horizontal gold bars on the lower body
    gx0, gx1 = left + 30 * s, right - 30 * s
    for row in range(3):
        gy = top + 150 * s + row * 40 * s
        d.line([(gx0, gy), (gx1, gy)], fill=GOLD, width=max(stroke - 3 * s, 7 * s))

    return img


# --- mark 2: honeycomb + waveform + bee (white silhouette on transparent) ---
def _hexagon(cx, cy, r):
    """Flat-top hexagon vertices of circumradius r about (cx, cy)."""
    pts = []
    for k in range(6):
        ang = math.radians(60 * k)
        pts.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
    return pts


def draw_mark():
    """Return a BASE*SS RGBA image of the honeycomb/waveform/bee mark."""
    img = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = SS

    cx, cy = C / 2, C / 2
    # circumradius sized so the full 7-hex flower (vertical extent 2.6r)
    # fits inside the frame with margin instead of clipping at the edge.
    r = 77 * s                      # hex circumradius
    stroke = 9 * s
    dx = 1.5 * r                    # flat-top neighbour offsets
    dy = math.sqrt(3) / 2 * r

    centers = [
        (cx, cy),
        (cx + dx, cy - dy), (cx + dx, cy + dy),
        (cx - dx, cy - dy), (cx - dx, cy + dy),
        (cx, cy - 2 * dy), (cx, cy + 2 * dy),
    ]
    for hx, hy in centers:
        hexp = _hexagon(hx, hy, r)
        d.line(hexp + [hexp[0]], fill=WHITE, width=stroke, joint="curve")

    # audio waveform: vertical bars marching toward the centre, left & right,
    # leaving a gap for the bee in the middle hexagon.
    bar_w = 10 * s
    heights = [34, 58, 90, 58, 34]
    for sign in (-1, 1):
        for i, h in enumerate(heights):
            bx = cx + sign * (int(r * 0.52) + i * int(r * 0.30))
            hh = h * s
            d.line([(bx, cy - hh), (bx, cy + hh)], fill=WHITE, width=bar_w)

    # bee: striped body + wings + antennae, centred in the middle hexagon
    bw, bh = 0.34 * r, 0.49 * r      # body half-extents, scaled to the hex
    d.ellipse((cx - bw, cy - bh, cx + bw, cy + bh), fill=WHITE)
    # black stripes carved into the body (transparent so the tint shows gaps)
    for row in (-0.42, 0.0, 0.42):
        sy = cy + row * bh
        d.line([(cx - bw * 0.9, sy), (cx + bw * 0.9, sy)],
               fill=(0, 0, 0, 0), width=7 * s)
    # wings
    wing = 30 * s
    d.ellipse((cx - bw - wing, cy - bh * 0.7, cx - bw + 6 * s, cy + bh * 0.2),
              fill=WHITE)
    d.ellipse((cx + bw - 6 * s, cy - bh * 0.7, cx + bw + wing, cy + bh * 0.2),
              fill=WHITE)
    # antennae
    for sign in (-1, 1):
        tipx = cx + sign * 16 * s
        d.line([(cx + sign * 6 * s, cy - bh), (tipx, cy - bh - 22 * s)],
               fill=WHITE, width=5 * s)
        d.ellipse((tipx - 6 * s, cy - bh - 28 * s, tipx + 6 * s, cy - bh - 16 * s),
                  fill=WHITE)

    return img


def main():
    launcher = draw_launcher()
    # master (portal/upload) + home-screen launcher mipmaps
    _save(launcher, MASTER_OUT, BASE)
    for density, size in MIPMAP_DENSITIES.items():
        _save(launcher, os.path.join(RES, density, "ic_launcher.png"), size)

    # ATAK title-bar / toolbar brand mark (transparent; tinted gold at runtime)
    mark = draw_mark()
    _save(mark, os.path.join(RES, "drawable", "xv_tool_icon.png"), 96)


if __name__ == "__main__":
    main()

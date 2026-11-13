"""Generate the Texas-TAK branded icon set for TAK-XVoice.

Run: python branding/make_icon.py            # writes into the repo
     python branding/make_icon.py <out_root>  # writes under <out_root> (preview)

This script is the single source of truth for the plugin's brand artwork.
Running it reproduces every committed raster asset, so the brand stays
reproducible from code rather than from hand-edited PNGs.

There is a single brand mark — the geometric, Texas-TAK-styled
"walkie-talkie" — rendered two ways from one shared geometry
(`_draw_walkie`) so the iconography stays identical everywhere it
appears:

  1. Gold-on-near-black, on a rounded-square background — the
     home-screen launcher icon and the portal/upload master.
       - branding/xv-icon.png                         (512, master)
       - app/src/main/res/mipmap-{m,h,xh,xxh,xxx}dpi/ic_launcher.png
  2. The same walkie-talkie as a white silhouette on transparency, no
     background square, so ATAK's title-bar / nav-button tint colors the
     shape with the brand gold and leaves the toolbar showing through.
       - app/src/main/res/drawable/xv_tool_icon.png   (96)

Both renderings are drawn on a 4x-supersampled canvas and downscaled
with LANCZOS so the strokes stay crisp at every density. Colors are the
canonical Texas-TAK tokens (see app/src/main/res/values/colors.xml):
accent gold #F2B33D on near-black bg #070707.
"""
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


# --- the walkie-talkie mark (shared geometry) -------------------------------
def _draw_walkie(d, s, color):
    """Stroke the geometric walkie-talkie onto draw context [d].

    [s] is the logical->canvas scale (SS) and [color] is the line color.
    No background is drawn here — callers decide whether to lay down a
    rounded-square backdrop (launcher) or leave it transparent (the
    tinted toolbar silhouette). This is the single source of the mark's
    shape so the launcher and the tool icon never drift apart.
    """
    stroke = 15 * s

    # body: a vertical beveled rectangle (octagon silhouette), outline
    left, right, top, bottom, cut = 182 * s, 352 * s, 150 * s, 388 * s, 34 * s
    body = [
        (left + cut, top), (right - cut, top), (right, top + cut),
        (right, bottom - cut), (right - cut, bottom), (left + cut, bottom),
        (left, bottom - cut), (left, top + cut),
    ]
    d.line(body + [body[0]], fill=color, width=stroke, joint="curve")

    # antenna mast + knob, rising from the body's upper-left shoulder
    ant_base = (left + 36 * s, top + 4 * s)
    ant_top = (left + 10 * s, top - 52 * s)
    d.line([ant_base, ant_top], fill=color, width=stroke)
    knob_r = 13 * s
    d.ellipse((ant_top[0] - knob_r, ant_top[1] - knob_r,
               ant_top[0] + knob_r, ant_top[1] + knob_r), fill=color)

    # three nested signal chevrons fanning up-right from the antenna tip
    cx, cy = ant_top
    for i, r in enumerate((40 * s, 70 * s, 100 * s)):
        d.arc((cx - r, cy - r, cx + r, cy + r), start=-70, end=5,
              fill=color, width=max(stroke - 2 * s, 8 * s))

    # channel knob: short stub off the top-right shoulder
    kx, ky = right - cut + 6 * s, top + cut - 2 * s
    d.line([(kx, ky), (kx + 20 * s, ky - 16 * s)], fill=color, width=stroke)

    # PTT bump: small rounded outline on the left flank
    px0, py0, px1, py1 = left - 22 * s, top + 92 * s, left + 2 * s, top + 182 * s
    d.rounded_rectangle((px0, py0, px1, py1), radius=8 * s, outline=color,
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
    d.line(zig, fill=color, width=stroke, joint="curve")

    # grille: three horizontal bars on the lower body
    gx0, gx1 = left + 30 * s, right - 30 * s
    for row in range(3):
        gy = top + 150 * s + row * 40 * s
        d.line([(gx0, gy), (gx1, gy)], fill=color, width=max(stroke - 3 * s, 7 * s))


# --- rendering 1: launcher (gold line-art on a near-black rounded square) ----
def draw_launcher():
    """Return a BASE*SS RGBA image of the gold walkie-talkie on near-black."""
    img = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = SS  # shorthand: logical->canvas scale

    # rounded-square background, then the mark in gold on top
    d.rounded_rectangle((0, 0, C, C), radius=96 * s, fill=NEAR_BLACK)
    _draw_walkie(d, s, GOLD)
    return img


# --- rendering 2: toolbar mark (white silhouette, no background) -------------
def draw_mark():
    """Return a BASE*SS RGBA image of the walkie-talkie silhouette.

    The same walkie-talkie as the launcher, but stroked in white on a
    transparent canvas with no rounded-square backdrop, so ATAK's
    title-bar / nav-button tint colors the shape with the brand gold and
    the surrounding toolbar shows through.
    """
    img = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    _draw_walkie(d, SS, WHITE)
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

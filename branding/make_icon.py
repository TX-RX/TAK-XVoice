"""Generate xv-icon.png — walkie-talkie silhouette with signal arcs.

Run: python branding/make_icon.py
Outputs:
  - branding/xv-icon.png                 (512x512, portal/upload master)
  - app/src/main/res/mipmap-mdpi/ic_launcher.png       (48)
  - app/src/main/res/mipmap-hdpi/ic_launcher.png       (72)
  - app/src/main/res/mipmap-xhdpi/ic_launcher.png      (96)
  - app/src/main/res/mipmap-xxhdpi/ic_launcher.png     (144)
  - app/src/main/res/mipmap-xxxhdpi/ic_launcher.png    (192)
  - app/src/main/res/drawable/xv_tool_icon.png         (96, no BG, for ATAK toolbar)

The toolbar variant has a transparent background so ATAK's nav-button
tint passes only over the silhouette. The launcher mipmaps keep the
slate rounded-square BG so the home-screen launcher icon looks right.
"""
import os

from PIL import Image, ImageDraw

SIZE = 512
OUT = os.path.join(os.path.dirname(__file__), "xv-icon.png")
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))
MIPMAP_BASE = os.path.join(REPO_ROOT, "app", "src", "main", "res")
MIPMAP_DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BG = (28, 32, 40, 255)         # slate background — matches ATAK dark theme
FG = (232, 236, 242, 255)      # silhouette color (near-white, slightly cool)

img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# Rounded-square background
d.rounded_rectangle((0, 0, SIZE, SIZE), radius=96, fill=BG)

# --- walkie-talkie body silhouette ---
body_w = 200
body_h = 300
bx0 = (SIZE - body_w) // 2 + 24   # nudge right so antenna + arcs sit left/up
by0 = 152
bx1 = bx0 + body_w
by1 = by0 + body_h
d.rounded_rectangle((bx0, by0, bx1, by1), radius=28, fill=FG)

# Side PTT bump (left side of the radio body) — sticks out so the shape reads
# unambiguously as a walkie-talkie and not a phone.
ptt_x0 = bx0 - 22
ptt_x1 = bx0 + 4
ptt_y0 = by0 + 60
ptt_y1 = ptt_y0 + 110
d.rounded_rectangle((ptt_x0, ptt_y0, ptt_x1, ptt_y1), radius=8, fill=FG)

# --- antenna ---
ant_base_x = bx0 + 36
ant_base_y = by0
ant_top_x = ant_base_x - 8
ant_top_y = by0 - 88
d.line((ant_base_x, ant_base_y, ant_top_x, ant_top_y), fill=FG, width=14)
# antenna tip ball
tip_r = 11
d.ellipse(
    (ant_top_x - tip_r, ant_top_y - tip_r, ant_top_x + tip_r, ant_top_y + tip_r),
    fill=FG,
)

# --- signal / call arcs emanating from antenna tip ---
def arc_wave(cx, cy, radius, start_deg, end_deg, width):
    d.arc(
        (cx - radius, cy - radius, cx + radius, cy + radius),
        start=start_deg,
        end=end_deg,
        fill=FG,
        width=width,
    )

# Three concentric arcs to the upper-right of the antenna tip
for r in (42, 74, 106):
    arc_wave(ant_top_x, ant_top_y, r, -68, 8, 12)

# Save master
img.save(OUT, "PNG", optimize=True)
print(f"wrote {OUT}")

# Density variants for app/src/main/res/mipmap-*dpi/ic_launcher.png
for density, size in MIPMAP_DENSITIES.items():
    out_dir = os.path.join(MIPMAP_BASE, density)
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "ic_launcher.png")
    img.resize((size, size), Image.LANCZOS).save(out_path, "PNG", optimize=True)
    print(f"wrote {out_path}")

# ATAK nav-button / pinned-tool drawable — silhouette only on transparent
# background so ATAK's toolbar tint colors the walkie-talkie, not a solid
# slate square. Rendered by stripping the BG fill from a clone of the
# master canvas and resaving at 96 px (mdpi-equivalent; the system
# upscales for higher densities since this is in res/drawable/, not a
# density-qualified bucket).
tool_img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
td = ImageDraw.Draw(tool_img)
td.rounded_rectangle((bx0, by0, bx1, by1), radius=28, fill=FG)
td.rounded_rectangle((ptt_x0, ptt_y0, ptt_x1, ptt_y1), radius=8, fill=FG)
td.line((ant_base_x, ant_base_y, ant_top_x, ant_top_y), fill=FG, width=14)
td.ellipse(
    (ant_top_x - tip_r, ant_top_y - tip_r, ant_top_x + tip_r, ant_top_y + tip_r),
    fill=FG,
)
for r in (42, 74, 106):
    td.arc(
        (ant_top_x - r, ant_top_y - r, ant_top_x + r, ant_top_y + r),
        start=-68, end=8, fill=FG, width=12,
    )
TOOL_OUT = os.path.join(MIPMAP_BASE, "drawable", "xv_tool_icon.png")
os.makedirs(os.path.dirname(TOOL_OUT), exist_ok=True)
tool_img.resize((96, 96), Image.LANCZOS).save(TOOL_OUT, "PNG", optimize=True)
print(f"wrote {TOOL_OUT}")

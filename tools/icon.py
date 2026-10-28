"""Draws the mod icon: a torch in its gold marker, lighting a diamond on a dark floor.

  uv run --with pillow python -I tools/icon.py src/main/resources/assets/torchplanner/icon.png
"""
import sys
from PIL import Image

N = 32
img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
px = img.load()


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = c


OUT = hexc("14161c")
FLOOR = hexc("262b36"); FLOOR_L = hexc("2e3442")
LIGHT = [hexc("6b5226"), hexc("846429"), hexc("a07a2e"), hexc("bf9134")]
RED = hexc("d2412f"); RED_D = hexc("8f2a1f")
PURPLE = hexc("9b4fd0"); PURPLE_D = hexc("63308a")
GOLD = hexc("ffc21a"); GOLD_D = hexc("c48a00")
STICK = hexc("8a6236"); STICK_D = hexc("5c3f20")
FLAME_Y = hexc("ffe066"); FLAME_O = hexc("ff9a1f"); FLAME_W = hexc("fff6c8")

# Dark stone floor with a tile grid, rounded corners.
rect(1, 0, 30, 31, OUT); rect(0, 1, 31, 30, OUT)
rect(1, 1, 30, 30, FLOOR)
for y in range(1, 31):
    for x in range(1, 31):
        if x % 6 == 0 or y % 6 == 0:
            px[x, y] = FLOOR_L

# Diamond of torch light, brighter toward the torch.
cx, cy = 16, 17
for y in range(1, 31):
    for x in range(1, 31):
        d = abs(x - cx) + abs(y - cy)
        if d <= 12:
            px[x, y] = LIGHT[min(3, (12 - d) // 3)]

# Spots that are still dark: red (mobs can spawn) and purple (no floor torch reaches).
for x, y in [(2, 2), (27, 2), (2, 27)]:
    rect(x, y, x + 2, y + 2, RED); rect(x, y + 2, x + 2, y + 2, RED_D)
rect(27, 27, 29, 29, PURPLE); rect(27, 29, 29, 29, PURPLE_D)

# Gold marker box around the torch.
bx0, by0, bx1, by1 = 11, 8, 21, 26
for x in range(bx0, bx1 + 1):
    px[x, by0] = GOLD; px[x, by1] = GOLD_D
for y in range(by0, by1 + 1):
    px[bx0, y] = GOLD; px[bx1, y] = GOLD_D
for x, y in [(bx0, by0), (bx1, by0), (bx0, by1), (bx1, by1)]:
    px[x, y] = GOLD

# The torch: stick plus flame.
rect(15, 15, 16, 24, STICK); rect(16, 15, 16, 24, STICK_D)
rect(14, 11, 17, 14, FLAME_O)
rect(15, 10, 16, 14, FLAME_Y)
rect(15, 12, 16, 13, FLAME_W)
px[15, 9] = FLAME_Y

scale = int(sys.argv[2]) if len(sys.argv) > 2 else 4
img.resize((N * scale, N * scale), Image.NEAREST).save(sys.argv[1])

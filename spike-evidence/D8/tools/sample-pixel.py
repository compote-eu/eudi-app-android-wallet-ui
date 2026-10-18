"""Print the RGB hex of one pixel of each PNG (x y in original pixels): the D8-brand token assertion."""
import sys
from PIL import Image
x, y = int(sys.argv[1]), int(sys.argv[2])
for p in sys.argv[3:]:
    r, g, b = Image.open(p).convert("RGB").getpixel((x, y))
    print(f"{p}: ({x},{y}) #{r:02X}{g:02X}{b:02X}")

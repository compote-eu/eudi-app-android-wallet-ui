#!/usr/bin/env bash
# M5e: the library's own rendering of the Home screen (ux/build/screens/SCR-home--<ui state>.html) as PNGs, one per
# ui state, brand and mode, cropped to the phone viewport (366 x 820 css px at 2x). Usage: render-spec-ref.sh <ux dir> <out dir>
set -euo pipefail
UX=$1; OUT=$2; C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"; mkdir -p "$OUT"
for page in "$UX"/build/screens/SCR-home--*.html; do
  ui=$(basename "$page" .html); ui=${ui#SCR-home--}
  for brand in eudiw_v1 idsk; do
    for mode in light high-contrast; do
      f="$OUT/$ui-$brand-$mode.png"
      "$C" --headless=new --disable-gpu --hide-scrollbars --window-size=480,960 --force-device-scale-factor=2 \
        --virtual-time-budget=4000 --screenshot="${f%.png}.full.png" "file://$page?brand=$brand&mode=$mode" >/dev/null 2>&1
      python3 -I -c "
from PIL import Image; import sys
im=Image.open(sys.argv[1]); im.crop((120,76,120+720,76+1630)).save(sys.argv[2]); " "${f%.png}.full.png" "$f"; rm -f "${f%.png}.full.png"
    done
  done
done
ls "$OUT" | wc -l

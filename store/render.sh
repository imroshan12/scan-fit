#!/usr/bin/env bash
# Renders the store graphics in store/ (STORE_LISTING §7) from their sources. macOS only: Quick Look
# (WebKit) renders the HTML, Pillow crops it (activate the .venv first), sips resizes the icon.
#   store/render.sh   -> store/play-feature-graphic-1024x500.png, store/play-icon-512.png
set -euo pipefail
cd "$(dirname "$0")"

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

# Feature graphic: Quick Look draws the page 1024 px wide into a 1024x1024 image; the graphic is its top 500 px.
qlmanage -t -s 1024 -o "${tmp}" feature-graphic.html >/dev/null 2>&1
# Crop the top 1024x500 and drop the alpha channel (Play wants no transparency). Pillow is in spec/tools/requirements.txt.
"${PYTHON:-python3}" -c 'import sys; from PIL import Image
Image.open(sys.argv[1]).convert("RGB").crop((0, 0, 1024, 500)).save(sys.argv[2])' \
  "${tmp}/feature-graphic.html.png" play-feature-graphic-1024x500.png

# Play icon: 512x512 PNG from the iOS 1024 icon (the same mark as the Android launcher icon).
sips -z 512 512 ../ios/ScanFit/Assets.xcassets/AppIcon.appiconset/1024.png --out play-icon-512.png >/dev/null

for f in play-feature-graphic-1024x500.png play-icon-512.png; do
  echo "$f: $(sips -g pixelWidth -g pixelHeight -g hasAlpha "$f" | awk '/pixel|hasAlpha/ {printf "%s ", $2}')"
done

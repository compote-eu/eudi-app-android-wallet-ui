#!/usr/bin/env bash
# M5e: every ui state of one brand, light and high contrast. Usage: shoot-brand.sh <brand> <out dir>
D=$(dirname "$0"); B=$1; OUT=$2; mkdir -p "$OUT"
for ui in default cloud offline empty cloud-empty document-invalid id-revoked id-suspended id-stale without-id without-pid without-dl notifications-unread dl-expiring; do
  "$D/shoot.sh" "gallery-home-$ui.$B" "$OUT/$ui-$B-light.png"
  "$D/shoot.sh" "gallery-home-$ui.$B.hc" "$OUT/$ui-$B-high-contrast.png"
done
echo finished > "$OUT/.done-$B"

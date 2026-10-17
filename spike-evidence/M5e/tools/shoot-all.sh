#!/usr/bin/env bash
# M5e: every ui state x {eudiw_v1, idsk} x {light, high contrast}. Usage: shoot-all.sh <out dir>
D=$(dirname "$0"); OUT=$1; mkdir -p "$OUT"
for ui in default cloud offline empty cloud-empty document-invalid id-revoked id-suspended id-stale without-id without-pid without-dl notifications-unread dl-expiring; do
  for brand in eudiw_v1 idsk; do
    "$D/shoot.sh" "gallery-home-$ui.$brand" "$OUT/$ui-$brand-light.png"
    "$D/shoot.sh" "gallery-home-$ui.$brand.hc" "$OUT/$ui-$brand-high-contrast.png"
  done
done

#!/usr/bin/env bash
# M9r (RULING-M9r-2): the D8 attempt-2 drills re-exported on the current spec baseline (dvm-week0 b55be65, exporter
# 0.5.0). The archived attempt-2 exports (exporter 0.3.0, 18 files) predate the exporter contract the app builds against
# since M5e/M6c (ux-library.json, ux-tokens.json, ux-assets.json, SCR-home.probe.json, drawables), so they cannot build
# the current modules at all. Each drill's archived spec.patch is applied unchanged (3-way for drill 3, whose context
# line moved), committed on a detached throwaway HEAD (clean export, ux_dirty false), exported, archived here.
# Usage: reexport-d8.sh <scratch-dir>
set -euo pipefail
S=${1:?scratch dir}; R=/Users/roman/code/eudi-kmp-spike; BASE=b55be65; W=$S/dvm-m9r
[[ -d $W ]] || git -C /Users/roman/code/dvm-week0 worktree add --detach "$W" $BASE
for d in 1 2 3 4 5-unseen; do
  E=$R/spike-evidence/M9r/regen/d8b-$d; mkdir -p "$E"; O=$S/m9r-export/d8b-$d; rm -rf "$O"; mkdir -p "$O"
  git -C "$W" checkout -q --detach $BASE; git -C "$W" reset -q --hard $BASE
  git -C "$W" apply -3 "$R/spike-evidence/D8-attempt2/$d/spec.patch"
  git -C "$W" add -A ux
  git -C "$W" -c user.name="M9r re-export" -c user.email=noreply@anthropic.com commit -q -m "M9r throwaway: D8-attempt2/$d/spec.patch on $BASE"
  echo "spec throwaway commit: $(git -C "$W" rev-parse HEAD) (base $BASE, patch spike-evidence/D8-attempt2/$d/spec.patch)" > "$E/spec-commit.txt"
  make -C "$W/ux" export-kmp OUT="$O/ux-spec" > "$E/export.log" 2>&1
  (cd "$O" && tar czf "$E/export.tar.gz" ux-spec)
  echo "d8b-$d: $(tail -1 "$E/export.log")"
done
git -C "$W" checkout -q --detach $BASE

#!/usr/bin/env bash
# D8 attempt 2, one drill, spec export + app side (KC1 steps 3-6, 8). Usage: drill-app.sh <n> <evidence-dir-name> <label>
# Prereq: /Users/roman/code/dvm-d8b-<n> is a detached worktree of dvm-week0 @ 874b4f9 with the drill's spec.patch applied
# and `make -C ux fast check test` already run (log in <evidence>/spec-gate.log).
set -euo pipefail
n=$1; d=$2; label=$3
R=/Users/roman/code/eudi-kmp-spike; E=$R/spike-evidence/D8-attempt2/$d; W=/Users/roman/code/dvm-d8b-$n
O=${D8B_SCRATCH:?}/$n; mkdir -p "$O" "$E/logs"
# the throwaway commit keeps the export clean (ux_dirty false): RULING-M8b-1; it is on no branch and is never pushed
git -C $W add -A ux
git -C $W -c user.name="D8 attempt 2 drill" -c user.email=noreply@anthropic.com commit -q -m "D8 attempt 2 drill $n ($label), throwaway: spike-evidence/D8-attempt2/$d/spec.patch"
echo "spec throwaway commit: $(git -C $W rev-parse HEAD)" | tee "$E/spec-commit.txt"
make -C $W/ux export-kmp OUT="$O/ux-spec" > "$E/export.log" 2>&1; tail -1 "$E/export.log"
(cd "$O" && tar czf "$E/export.tar.gz" ux-spec)
cd $R
git checkout -q -b spike/d8b-$n 6946d80c5
rsync -a --delete "$O/ux-spec/" ux-spec/; diff -r "$O/ux-spec" ux-spec && echo "ux-spec = export (verbatim)"
git add -A ux-spec
git commit -q -m "D8 attempt 2, drill $n ($label): sync ux-spec/ verbatim from the export of spike-evidence/D8-attempt2/$d/spec.patch

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MfiaMJZGVTeVGgBE7GYmJK"
echo "app commit: $(git rev-parse --short HEAD) on $(git rev-parse --abbrev-ref HEAD)"
git diff --stat 6946d80c5..spike/d8b-$n > "$E/diffstat.txt"; git diff --numstat 6946d80c5..spike/d8b-$n > "$E/numstat.txt"
echo "diffstat: $(tail -1 "$E/diffstat.txt"); outside ux-spec: $(git diff --name-only 6946d80c5..spike/d8b-$n | grep -vc '^ux-spec/' || true)"
s=$SECONDS; rc=0; ./spike-gate.sh > "$E/logs/gate.log" 2>&1 || rc=$?
cp build/spike-gate/gradle-tests.log "$E/logs/gate-gradle-tests.log"
echo "gate rc=$rc ($((SECONDS - s)) s): $(tail -1 "$E/logs/gate.log")"

#!/usr/bin/env bash
# M9r regen-smoke: the spike module tests against every archived export, each in a scratch copy of ux-spec/ (never in the
# tree, never committed). A spec-only sync that turns this red is a harness defect (SPIKE-PLAN §0.2b, rule T1).
# Usage: ./spike-regen-smoke.sh [host|all] [name ...]     (default: all = host + iOS simulator; names filter the exports)
# Env: REGEN_SCRATCH=<dir> (default: a new mktemp dir). Logs: build/spike-gate/regen-smoke/<name>.log.
# The build reads the copy through -PuxSpecDir and writes to <module>/build/regen-smoke/, apart from the tracked build.
set -uo pipefail
cd "$(dirname "$0")"
MODE=${1:-all}; [[ $# -gt 0 ]] && shift
[[ $MODE == host || $MODE == all ]] || { echo "usage: $0 [host|all] [name ...]"; exit 2; }
SCRATCH=${REGEN_SCRATCH:-$(mktemp -d -t regen-smoke)}; mkdir -p "$SCRATCH"
LOG=build/spike-gate/regen-smoke; mkdir -p "$LOG"
MODULES=$(grep -oE '":ux-[a-z-]+"' settings.gradle.kts | tr -d '"' | grep -v ':ux-spike-android')

# name|source: an archived export (tar.gz with a top-level ux-spec/) or the tracked baseline.
EXPORTS=("baseline|ux-spec")
for t in spike-evidence/M9r/regen/*/export.tar.gz; do EXPORTS+=("$(basename "$(dirname "$t")")|$t"); done   # D8 attempt-2 drills 1-5
for t in spike-evidence/M9/*/export.tar.gz; do EXPORTS+=("m9-$(basename "$(dirname "$t")")|$t"); done         # D9 options

tasks=(); for m in $MODULES; do
  if [[ $MODE == all ]]; then tasks+=("$m:allTests"); else tasks+=("$m:testAndroidHostTest"); fi
done

RC=0; T0=$SECONDS
for e in "${EXPORTS[@]}"; do
  name=${e%%|*}; src=${e#*|}
  if [[ $# -gt 0 ]] && ! printf '%s\n' "$@" | grep -qx "$name"; then continue; fi
  dir="$SCRATCH/$name"; rm -rf "$dir"; mkdir -p "$dir"
  if [[ $src == ux-spec ]]; then cp -R ux-spec "$dir/ux-spec"; else tar xzf "$src" -C "$dir"; fi
  find . -path '*/build/regen-smoke/test-results/*' -name '*.xml' -delete 2>/dev/null
  t0=$SECONDS
  ./gradlew --continue -PuxSpecDir="$dir/ux-spec" "${tasks[@]}" > "$LOG/$name.log" 2>&1; grc=$?
  summary=$(python3 -I - <<'PY'
import glob, xml.etree.ElementTree as ET
n = f = 0; failed = []
for p in glob.glob('ux-*/build/regen-smoke/test-results/**/*.xml', recursive=True):
    try: r = ET.parse(p).getroot()
    except ET.ParseError: continue
    for s in ([r] if r.tag == 'testsuite' else r.findall('testsuite')):
        n += int(s.get('tests', 0)); f += int(s.get('failures', 0)) + int(s.get('errors', 0))
        for c in s.findall('testcase'):
            if c.find('failure') is not None or c.find('error') is not None:
                failed.append(f"{p.split('/')[0]}:{c.get('classname').rsplit('.', 1)[-1]}.{c.get('name')}")
print(f'{n}/{f}')
for t in sorted(set(failed)): print('  FAILED', t)
PY
)
  counts=${summary%%$'\n'*}
  if [[ $grc == 0 && ${counts#*/} == 0 && ${counts%/*} -gt 0 ]]; then echo "PASS $name $counts ($((SECONDS - t0))s)"
  else echo "FAIL $name $counts gradle rc=$grc ($((SECONDS - t0))s) -> $LOG/$name.log"; RC=1; fi
  [[ $summary == *$'\n'* ]] && echo "${summary#*$'\n'}"
done
echo "regen-smoke ($MODE): $((SECONDS - T0))s, scratch $SCRATCH"
[[ -z ${REGEN_SCRATCH:-} ]] && rm -rf "$SCRATCH"
exit $RC

#!/usr/bin/env bash
# Spike quality gate: wiki/BRANCHES.md §4 plus the spike modules (SPIKE-PLAN.md).
# Usage: ./spike-gate.sh [--fast | --style-guard]   (--fast skips the Release build; --style-guard runs the two guards only)
# Prints one summary block; full logs go to build/spike-gate/.
set -uo pipefail
cd "$(dirname "$0")"
LOG=build/spike-gate; mkdir -p "$LOG"
SIM="${SPIKE_SIM:-platform=iOS Simulator,name=iPhone 17,OS=27.0}"
XFLAGS=(-skipMacroValidation -skipPackagePluginValidation)
SPIKE_MODULES=$(grep -oE '":ux-[a-z-]+"' settings.gradle.kts | tr -d '"' | grep -v ':ux-spike-android' || true)
RESULT=0
ONLY_STYLE_GUARD=0; [[ "${1:-}" == --style-guard ]] && ONLY_STYLE_GUARD=1

step() { # name, command...
  local name=$1; shift
  local t0=$SECONDS
  if "$@" >"$LOG/$name.log" 2>&1; then echo "PASS $name ($((SECONDS - t0))s)"
  else echo "FAIL $name ($((SECONDS - t0))s) -> $LOG/$name.log"; RESULT=1; fi
}

# KC1 guard (SPIKE-PLAN §3, M5b): no SCR- literal or screen name in any spike module or hook (the plan's grep), and no
# state-name literal of the bundle (the exported one, ux-spec/, since M6a) in their main sources. PresRequestScreen.kt (the hand-written consent screen and its
# registration) is the only exception. Untracked files count too, so the guard holds before a commit.
kc1_guard() {
  local paths=(ux-theme ux-elements ux-runtime ux-spike ux-spike-android shared-ui/src/iosUxSpikeMain
    shared-ui/src/commonMain/kotlin/eu/europa/ec/shared/navigation/DebugFlowHook.kt)
  local except=':(exclude,glob)ux-spike/**/PresRequestScreen.kt' hits states rc=0
  # M5e: the M5d fragment bundles (and this guard's exclusion for them) are gone; the Home probe is the export's own file,
  # copied at build time, and no path under the scanned modules carries a screen id any more.
  hits=$(git grep -n --untracked -E 'SCR-|"pres-' -- "${paths[@]}" "$except")
  [[ -z "$hits" ]] || { echo "KC1: SCR-/screen-name literals:"; echo "$hits"; rc=1; }
  states=$(python3 -I -c 'import json,sys; [print(f"\"{s}\"") for s in json.load(open(sys.argv[1]))["states"]]' \
    ux-spec/resources/files/FLW-pres-cross-device.bundle.json)
  hits=$(git grep -n --untracked -F -f <(echo "$states") -- ':(glob)ux-*/src/*Main/**/*.kt' ':(glob)ux-*/src/main/**/*.kt' \
    ':(exclude,glob)ux-contract/**' ':(glob)shared-ui/src/iosUxSpikeMain/**/*.kt' "${paths[6]}" "$except")
  [[ -z "$hits" ]] || { echo "KC1: state-name literals in main sources:"; echo "$hits"; rc=1; }
  grep -q '"SCR-pres-request"' ux-spike/src/commonMain/kotlin/eu/europa/ec/uxspike/PresRequestScreen.kt \
    || { echo "KC1: the one exception (PresRequestScreen.kt registration) is missing"; rc=1; }
  [[ $rc == 0 ]] && echo "KC1 guard: 0 hits ($(echo "$states" | wc -l | tr -d ' ') state names checked)"
  return $rc
}
step kc1-guard kc1_guard

# Style guard (SPIKE-PLAN §3, M5c, OR-2): no hard-coded dp, sp, Color(...), alpha literal, named numeric constant or
# FontWeight in the main sources of :ux-elements (templates and registry included). Values come from the tokens;
# the documented exceptions live in ux-elements/style-guard.allowlist (path|line|reason). Untracked files count too.
style_guard() {
  python3 -I - <<'PY'
import re, subprocess, sys
files = subprocess.run(['git', 'ls-files', '-co', '--exclude-standard', '--', 'ux-elements/src/*Main/*.kt'],
                       capture_output=True, text=True, check=True).stdout.split()
rules = [
    ('dp/sp literal', r'\b\d+(?:\.\d+)?\.(?:dp|sp)\b'),
    ('Dp()/TextUnit() constructor', r'\b(?:Dp|TextUnit)\('),
    ('Color(...)', r'\bColor\('),
    ('named Color', r'\bColor\.(?!Transparent\b|Unspecified\b)[A-Z]\w*'),
    ('alpha literal', r'(?i)\balpha\b[^\n]*?(?:=\s*|\(\s*|\belse\s+|\)\s*)-?\d*\.?\d+f?\b'),
    ('FontWeight', r'\bFontWeight\b'),
    ('named numeric constant', r'\b(?:const\s+)?val\s+[A-Z][A-Z0-9_]*\s*(?::\s*\w+)?\s*=\s*-?\d'),
]
allow = {}
for n, line in enumerate(open('ux-elements/style-guard.allowlist'), 1):
    if line.strip() and not line.startswith('#'):
        parts = line.rstrip('\n').split('|', 2)
        if len(parts) != 3 or not all(x.strip() for x in parts):
            print(f'style-guard: allowlist line {n} is not path|line|reason'); sys.exit(1)
        allow[(parts[0].strip(), parts[1].strip())] = False
hits = []
for f in files:
    in_block = False
    for n, raw in enumerate(open(f), 1):
        line = raw.strip()
        if in_block:
            in_block = '*/' not in line
            continue
        if line.startswith('/*'):
            in_block = '*/' not in line
            continue
        if line.startswith('//') or line.startswith('*') or line.startswith('import ') or line.startswith('package '):
            continue
        code = re.sub(r'//.*$', '', line)
        for name, rx in rules:
            if re.search(rx, code):
                if (f, line) in allow:
                    allow[(f, line)] = True
                else:
                    hits.append(f'{f}:{n}: {name}: {line}')
stale = [f'{p}|{l}' for (p, l), used in allow.items() if not used]
for h in hits: print('style-guard:', h)
for s in stale: print('style-guard: stale allowlist entry:', s)
if not hits and not stale:
    print(f'style-guard: 0 hits in {len(files)} files, {len(allow)} allowlisted')
sys.exit(1 if hits or stale else 0)
PY
}
step style-guard style_guard
[[ $ONLY_STYLE_GUARD == 1 ]] && { cat "$LOG/style-guard.log"; exit $RESULT; }

# ux-spec lock (boundary §3, L1-L3; M6a): every file of ux-spec/ matches UX_SPEC.lock (sha256 and bytes), no file is
# missing or unlisted, the lock spec_hash equals the bundle's and the contract and theme SPEC_HASH literals, and
# ux_dirty is false outside a spike/d8-* branch. ux-spec/ is exporter-only: a red step means re-export, never a hand fix.
uxspec_lock() {
  python3 -I - "$(git rev-parse --abbrev-ref HEAD)" <<'PY'
import hashlib, json, os, re, sys
root = 'ux-spec'
lock = json.load(open(os.path.join(root, 'UX_SPEC.lock')))
listed = {f['path']: f for f in lock['files']}
on_disk = {os.path.relpath(os.path.join(d, n), root) for d, _, ns in os.walk(root) for n in ns} - {'UX_SPEC.lock'}
errors = [f'unlisted file: {p}' for p in sorted(on_disk - listed.keys())]
errors += [f'missing file: {p}' for p in sorted(listed.keys() - on_disk)]
for p in sorted(listed.keys() & on_disk):
    data = open(os.path.join(root, p), 'rb').read()
    if hashlib.sha256(data).hexdigest() != listed[p]['sha256'] or len(data) != listed[p]['bytes']:
        errors.append(f'sha256/bytes mismatch: {p}')
h = lock['spec_hash']
bundle = json.load(open(os.path.join(root, 'resources/files', lock['flows'][0] + '.bundle.json')))
if bundle.get('spec_hash') != h: errors.append(f"bundle spec_hash {bundle.get('spec_hash')} != lock {h}")
for p in listed:
    if p.endswith(('Spec.kt', 'ThemeSpec.kt')):
        m = re.search(r'SPEC_HASH: String = "([^"]+)"', open(os.path.join(root, p)).read())
        if not m or m.group(1) != h: errors.append(f'SPEC_HASH literal in {p} != lock {h}')
if lock.get('ux_dirty') and not sys.argv[1].startswith('spike/d8-'): errors.append('ux_dirty export outside spike/d8-*')
for e in errors: print('uxspec-lock:', e)
if not errors:
    print(f"uxspec-lock: {len(listed)} files match UX_SPEC.lock, spec_hash {h[:19]}..., ux@{lock['ux_commit'][:12]}, "
          f"exporter {lock['exporter']['version']}")
sys.exit(1 if errors else 0)
PY
}
step uxspec-lock uxspec_lock

find . -path '*/build/test-results/*' -name '*.xml' -not -path './build/*' -delete 2>/dev/null

ux_tests=(); for m in $SPIKE_MODULES; do ux_tests+=("$m:allTests"); done
step gradle-tests ./gradlew --continue test \
  :shared-logic:iosSimulatorArm64Test :shared-ui:iosSimulatorArm64Test \
  :shared-ui:testAndroidHostTest :shared-logic:testAndroidHostTest \
  "${ux_tests[@]}" :androidApp:assembleDevDebug
step gradle-ios-demo ./gradlew --continue :shared-logic:iosSimulatorArm64Test :shared-ui:iosSimulatorArm64Test -PappFlavor=demo
step lint ./gradlew --continue detekt ktlintCheck
step ios-project ./gradlew generateIosProject
step xcode-debug xcodebuild -project iosApp/iosApp.xcodeproj -scheme EudiWallet -configuration Debug \
  -destination "$SIM" -derivedDataPath DerivedData "${XFLAGS[@]}" build
if [[ "${1:-}" != "--fast" ]]; then
  step xcode-release env CONFIGURATION=Release xcodebuild -project iosApp/iosApp.xcodeproj -scheme EudiWallet \
    -configuration Release -destination "$SIM" -derivedDataPath DerivedData-Release "${XFLAGS[@]}" build
fi

echo "--- test counts (tests/failures/errors/skipped per task dir) ---"
python3 - <<'PY'
import glob, os, xml.etree.ElementTree as ET, collections
agg = collections.defaultdict(lambda: [0, 0, 0, 0]); failed = []
for f in glob.glob('**/build/test-results/**/*.xml', recursive=True):
    try: r = ET.parse(f).getroot()
    except ET.ParseError: continue
    key = f.split('/build/test-results/')[0] + ':' + f.split('/build/test-results/')[1].split('/')[0]
    for s in ([r] if r.tag == 'testsuite' else r.findall('testsuite')):
        a = agg[key]
        for i, k in enumerate(('tests', 'failures', 'errors', 'skipped')): a[i] += int(s.get(k, 0))
        for c in s.findall('testcase'):
            if c.find('failure') is not None or c.find('error') is not None:
                failed.append(f"{c.get('classname')}.{c.get('name')}")
tot = [0, 0, 0, 0]
for k in sorted(agg):
    print(f"{k}: {'/'.join(map(str, agg[k]))}"); tot = [x + y for x, y in zip(tot, agg[k])]
print(f"TOTAL: {'/'.join(map(str, tot))}")
for t in failed: print(f"FAILED {t}")
PY
echo "GATE $([[ $RESULT == 0 ]] && echo GREEN || echo RED)"
exit $RESULT

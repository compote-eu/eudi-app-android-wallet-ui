#!/usr/bin/env bash
# Spike quality gate: wiki/BRANCHES.md §4 plus the spike modules (SPIKE-PLAN.md).
# Usage: ./spike-gate.sh [--fast]   (--fast skips the Release build)
# Prints one summary block; full logs go to build/spike-gate/.
set -uo pipefail
cd "$(dirname "$0")"
LOG=build/spike-gate; mkdir -p "$LOG"
SIM="${SPIKE_SIM:-platform=iOS Simulator,name=iPhone 17,OS=27.0}"
XFLAGS=(-skipMacroValidation -skipPackagePluginValidation)
SPIKE_MODULES=$(grep -oE '":ux-[a-z-]+"' settings.gradle.kts | tr -d '"' | grep -v ':ux-spike-android' || true)
RESULT=0

step() { # name, command...
  local name=$1; shift
  local t0=$SECONDS
  if "$@" >"$LOG/$name.log" 2>&1; then echo "PASS $name ($((SECONDS - t0))s)"
  else echo "FAIL $name ($((SECONDS - t0))s) -> $LOG/$name.log"; RESULT=1; fi
}

# KC1 guard (SPIKE-PLAN §3, M5b): no SCR- literal or screen name in any spike module or hook (the plan's grep), and no
# state-name literal of the bundle in their main sources. PresRequestScreen.kt (the hand-written consent screen and its
# registration) is the only exception. Untracked files count too, so the guard holds before a commit.
kc1_guard() {
  local paths=(ux-theme ux-elements ux-runtime ux-spike ux-spike-android shared-ui/src/iosUxSpikeMain
    shared-ui/src/commonMain/kotlin/eu/europa/ec/shared/navigation/DebugFlowHook.kt)
  local except=':(exclude,glob)ux-spike/**/PresRequestScreen.kt' hits states rc=0
  hits=$(git grep -n --untracked -E 'SCR-|"pres-' -- "${paths[@]}" "$except")
  [[ -z "$hits" ]] || { echo "KC1: SCR-/screen-name literals:"; echo "$hits"; rc=1; }
  states=$(python3 -I -c 'import json,sys; [print(f"\"{s}\"") for s in json.load(open(sys.argv[1]))["states"]]' \
    spike-evidence/boundary/FLW-pres-cross-device.bundle.PROVISIONAL.json)
  hits=$(git grep -n --untracked -F -f <(echo "$states") -- ':(glob)ux-*/src/*Main/**/*.kt' ':(glob)ux-*/src/main/**/*.kt' \
    ':(exclude,glob)ux-contract/**' ':(glob)shared-ui/src/iosUxSpikeMain/**/*.kt' "${paths[6]}" "$except")
  [[ -z "$hits" ]] || { echo "KC1: state-name literals in main sources:"; echo "$hits"; rc=1; }
  grep -q '"SCR-pres-request"' ux-spike/src/commonMain/kotlin/eu/europa/ec/uxspike/PresRequestScreen.kt \
    || { echo "KC1: the one exception (PresRequestScreen.kt registration) is missing"; rc=1; }
  [[ $rc == 0 ]] && echo "KC1 guard: 0 hits ($(echo "$states" | wc -l | tr -d ' ') state names checked)"
  return $rc
}
step kc1-guard kc1_guard

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

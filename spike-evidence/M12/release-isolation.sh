#!/usr/bin/env bash
# M12 release-isolation check at HEAD, both platforms (SPIKE-PLAN §1 "Release-isolation check").
# Run after a full ./spike-gate.sh (it builds DerivedData-Release). Every count must be 0 or absent.
# Usage: spike-evidence/M12/release-isolation.sh <throwaway keystore> <password>
set -uo pipefail
cd "$(dirname "$0")/../.."
KS=$1; PW=$2
export PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH
# Plan patterns plus every spike class/package/tag name used since M2 (DebugFlowHook is the declared seam, no pattern).
PAT='eu\.europa\.ec\.ux\.|eu/europa/ec/ux/|uxspike|UxSpike|FakePresCrossDevice|UxFlowRoute|ux-theme|ux-contract|ux-runtime|ux-elements|ux-spike|UX-SPIKE|PresCrossDevice|UxBrandConfig|UxThemeSpec|PresRequestScreen|InteractorPresCrossDevice|AdapterGaps|UxElementRegistry|UxTokens|UxComp|IosAccessibilityBridge|UxDcApi|uxDcApiSubflow|IosUxSpike|FlowEngine|FlowHost|ScreenProbe|UxHomeProbe'
echo "# M12 release isolation at $(git rev-parse --short HEAD) $(date -Iseconds)"
echo "patterns: $PAT"

echo "== iOS =="
echo "-- Gradle graph: CONFIGURATION=Release :shared-ui:dependencies, project ':ux-' lines"
n=$(CONFIGURATION=Release ./gradlew -q :shared-ui:dependencies 2>/dev/null | grep -c "project ':ux-")
c=$(./gradlew -q :shared-ui:dependencies 2>/dev/null | grep -c "project ':ux-")
# split by configuration: test-only configurations (the M11b adapter tests in androidHostTest) do not ship
nt=$(CONFIGURATION=Release ./gradlew -q :shared-ui:dependencies 2>/dev/null | awk '/^[A-Za-z]/{cfg=$1} /project .:ux-/{ if (cfg !~ /[Tt]est/) n++ } END{print n+0}')
cfgs=$(CONFIGURATION=Release ./gradlew -q :shared-ui:dependencies 2>/dev/null | awk '/^[A-Za-z]/{cfg=$1} /project .:ux-/{print cfg}' | sort -u | tr '\n' ' ')
echo "release: $n lines, all configurations (control, Debug graph: $c); in non-test configurations: $nt; configurations with ux-: $cfgs"
APP=DerivedData-Release/Build/Products/Release-iphonesimulator/EudiWallet.app
APPEX=$APP/Extensions/EudiWalletDocumentProvider.appex
FW=DerivedData-Release/Build/Products/Release-iphonesimulator/SharedKit.framework/SharedKit
ls -l "$APP/EudiWallet" | awk '{print "Release app binary built:", $6, $7, $8}'
for b in "$APP/EudiWallet" "$APPEX/EudiWalletDocumentProvider" "$FW"; do
  [ -f "$b" ] || { echo "MISSING $b"; continue; }
  s=$(nm -gU "$b" 2>/dev/null | grep -cE "$PAT"); t=$(strings -a "$b" | grep -cE "$PAT")
  echo "$(basename "$b"): nm -gU spike symbols $s, strings $t (nm total $(nm -gU "$b" 2>/dev/null | wc -l | tr -d ' '))"
done
echo "-- T9 note: EudiComponentActivity is Android-only; DebugFlowHook symbol present by design: $(nm -gU "$FW" 2>/dev/null | grep -c DebugFlowHook)"
echo "-- resources: compose-resources packages in .app and .appex"
for d in "$APP" "$APPEX"; do
  echo "$(basename "$d"): $(find "$d" -path '*compose-resources*' -mindepth 1 -maxdepth 4 -type d | grep -oE 'eu\.europa\.ec\.[a-z.]+resources' | sort -u | tr '\n' ' ')"
  echo "  spike resource files: $(find "$d" -type f | grep -cE 'ux\.contract|ux\.theme|ux\.elements|ux\.runtime|uxspike|ux-spike|bundle\.json|ux-tokens|ux-library|ux-assets|probe\.json')"
done

echo "== Android =="
for f in dev demo; do
  r=$(./gradlew -q :androidApp:dependencies --configuration ${f}ReleaseRuntimeClasspath 2>/dev/null | grep -cE "project ':ux-|uxspike")
  d=$(./gradlew -q :androidApp:dependencies --configuration ${f}DebugRuntimeClasspath 2>/dev/null | grep -cE "project ':ux-|uxspike")
  echo "${f}ReleaseRuntimeClasspath: $r   (control ${f}DebugRuntimeClasspath: $d)"
done
SIGN=(-Pandroid.injected.signing.store.file="$KS" -Pandroid.injected.signing.store.password="$PW"
  -Pandroid.injected.signing.key.alias=throw -Pandroid.injected.signing.key.password="$PW")
for f in dev demo; do
  F=$(tr '[:lower:]' '[:upper:]' <<<"${f:0:1}")${f:1}
  if ./gradlew -q :androidApp:assemble${F}Release "${SIGN[@]}" >build/m12-assemble-$f.log 2>&1; then
    apk=$(ls -t androidApp/build/outputs/apk/$f/release/*.apk | head -1)
    echo "-- $f release APK built: $apk"
    echo "dex packages with spike patterns: $(apkanalyzer dex packages "$apk" 2>/dev/null | grep -cE "$PAT")"
    echo "files (assets, composeResources) with spike patterns: $(apkanalyzer files list "$apk" 2>/dev/null | grep -cE "$PAT|ux\.contract|ux\.theme|ux\.elements|ux\.runtime|bundle\.json|ux-tokens|ux-library|ux-assets|probe\.json")"
    echo "manifest ux-spike-init / UxSpikeInit providers: $(apkanalyzer manifest print "$apk" 2>/dev/null | grep -ciE 'ux-spike-init|UxSpikeInit')"
    echo "EudiComponentActivity entries in release dex (T9 ships here; its guard is inert with no hook bound): $(apkanalyzer dex packages --defined-only "$apk" 2>/dev/null | grep -c 'EudiComponentActivity')"
  else
    echo "-- $f release APK: BUILD FAILED (see build/m12-assemble-$f.log tail below)"; tail -15 build/m12-assemble-$f.log
  fi
done
dbg=$(ls -t androidApp/build/outputs/apk/dev/debug/*.apk | head -1)
echo "-- control, dev debug APK ($dbg): dex $(apkanalyzer dex packages "$dbg" 2>/dev/null | grep -cE "$PAT"), files $(apkanalyzer files list "$dbg" 2>/dev/null | grep -cE "$PAT|ux\.contract|bundle\.json"), provider $(apkanalyzer manifest print "$dbg" 2>/dev/null | grep -ciE 'ux-spike-init|UxSpikeInit')"

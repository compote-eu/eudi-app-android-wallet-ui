#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-07 - orchestrates the Android (Maestro) and iOS (Appium) halves of a
# BLE proximity presentation where iOS deselects EVERY requested field on
# the consent screen (not just some, like TC-04's partial scenario) and
# is expected to find the Share button disabled - preventing an empty
# response from ever being sent.
#
# What this test is actually checking: whether iOS's own Share-disabled
# claim (checked via WebDriver's standard element /enabled endpoint, not
# a screenshot guess) matches Android's ACTUAL received state (verified
# independently via its own logcat - tc-07-verify-no-receipt.py), same
# discipline as TC-13/14. Code-grounded expectation (confirmed before
# writing this): BaseRequestView.swift's shareButton() binds
# `isEnabled: canShare`, which is false exactly when zero checkboxes are
# selected (RequestDataUIModel.swift's canShare()).
#
# Physical prerequisite (one-time, not automated here): same camera rig
# as TC-02/03/04/13/14. See README.md in this directory.
#
# Usage:
#   IOS_UDID=<udid> APPLE_TEAM_ID=<team> IOS_BUNDLE_ID=<bundle-id> \
#     ANDROID_UDID=<serial> EUDI_WALLET_PIN=<pin> ./tc-07-ble-zero-selection.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_UDID="${IOS_UDID:?Set IOS_UDID to the physical iPhone's UDID}"
APPLE_TEAM_ID="${APPLE_TEAM_ID:?Set APPLE_TEAM_ID}"
IOS_BUNDLE_ID="${IOS_BUNDLE_ID:?Set IOS_BUNDLE_ID}"
ANDROID_UDID="${ANDROID_UDID:?Set ANDROID_UDID to the physical Android device's adb serial}"
: "${EUDI_WALLET_PIN:?Set EUDI_WALLET_PIN}"

IOS_TIMEOUT_SECONDS="${IOS_TIMEOUT_SECONDS:-180}"

export PATH="$HOME/.maestro/bin:$PATH"

RUN_TS="$(date +%Y%m%d_%H%M%S 2>/dev/null || echo run)"
FORENSICS_DIR="$SCRIPT_DIR/tc07-forensics-$RUN_TS"
LOGCAT_FILE="$(mktemp -t tc07-android-logcat)"

echo "--- Step 1: start Android logcat capture ---"
adb -s "$ANDROID_UDID" logcat -c
adb -s "$ANDROID_UDID" logcat -v time > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

echo "--- Step 2: start the Android flow in the background (create full-PID request, open scanner) ---"
MAESTRO_TESTS_DIR="$HOME/.maestro/tests"
MAESTRO_START_MARKER="$(mktemp -t tc07-maestro-marker)"
maestro --udid "$ANDROID_UDID" test "$SCRIPT_DIR/tc-07-ble-zero-selection.yaml" &
ANDROID_PID=$!

echo "--- Step 2b: wait for Maestro's own driver setup to become ready ---"
MAESTRO_LOG=""
deadline=$((SECONDS + 60))
while [ "$SECONDS" -lt "$deadline" ]; do
  candidate="$(find "$MAESTRO_TESTS_DIR" -maxdepth 1 -newer "$MAESTRO_START_MARKER" -type d 2>/dev/null | sort | tail -1)"
  if [ -n "$candidate" ] && [ -f "$candidate/maestro.log" ] && grep -q "Running flow" "$candidate/maestro.log" 2>/dev/null; then
    MAESTRO_LOG="$candidate/maestro.log"
    break
  fi
  sleep 2
done
rm -f "$MAESTRO_START_MARKER"
if [ -z "$MAESTRO_LOG" ]; then
  echo "Maestro's driver setup did not reach 'Running flow' within 60s - this is the startup hang investigated for TC-03 (2026-08-26), not a rig/timing issue on the iOS side. Aborting before running the iOS half."
  kill "$ANDROID_PID" 2>/dev/null || true
  kill "$LOGCAT_PID" 2>/dev/null || true
  exit 1
fi
echo "Maestro is running ($MAESTRO_LOG)"

echo "--- Step 3: run the iOS half (trigger QR, deselect every field, confirm Share disables) ---"
mkdir -p "$FORENSICS_DIR"
python3 "$SCRIPT_DIR/tc-07-ios-zero-selection.py" "$IOS_UDID" "$APPLE_TEAM_ID" "$IOS_BUNDLE_ID" "$FORENSICS_DIR" &
IOS_PID=$!
IOS_TIMED_OUT=0
deadline=$((SECONDS + IOS_TIMEOUT_SECONDS))
while kill -0 "$IOS_PID" 2>/dev/null; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    IOS_TIMED_OUT=1
    kill "$IOS_PID" 2>/dev/null
    sleep 2
    kill -9 "$IOS_PID" 2>/dev/null
    break
  fi
  sleep 2
done
wait "$IOS_PID" 2>/dev/null
IOS_EXIT=$?
if [ "$IOS_TIMED_OUT" -eq 1 ]; then
  IOS_EXIT=124
fi

echo "--- Step 4: wait for (or give up on) the Android flow ---"
# A non-zero exit here is the EXPECTED outcome for TC-07 (Android's
# "Show documents" assertion is supposed to time out, since Share is
# expected to stay disabled and nothing should ever be sent) - it is
# not treated as fatal, same as TC-13/14.
wait "$ANDROID_PID" 2>/dev/null
ANDROID_EXIT=$?
echo "Android flow exit=$ANDROID_EXIT (non-zero is EXPECTED here - see yaml header comment)"

echo "--- Step 5: stop logcat capture and verify Android's actual received state ---"
kill "$LOGCAT_PID" 2>/dev/null || true
sleep 1
cp "$LOGCAT_FILE" "$FORENSICS_DIR/android_logcat.txt" 2>/dev/null

python3 "$SCRIPT_DIR/tc-07-verify-no-receipt.py" "$LOGCAT_FILE"
VERIFY_EXIT=$?

echo ""
echo "=================== TC-07 RESULT ==================="
if [ "$IOS_EXIT" -eq 124 ]; then
  echo "iOS script itself TIMED OUT after ${IOS_TIMEOUT_SECONDS}s (script-level hang, not a captured app outcome)."
elif [ "$IOS_EXIT" -ne 0 ]; then
  echo "iOS script FAILED (exit=$IOS_EXIT) - see output above; Share may not have disabled correctly, or another assertion failed."
elif [ -f "$FORENSICS_DIR/tc07_ios_outcome.txt" ]; then
  IOS_OUTCOME="$(cat "$FORENSICS_DIR/tc07_ios_outcome.txt")"
  echo "iOS reported:        $IOS_OUTCOME (Share disabled at zero selection, inert when tapped anyway)"
  if [ "$VERIFY_EXIT" -eq 0 ]; then
    echo "Android received:    NOTHING (confirmed via logcat - no ResponseReceived)"
    echo ""
    echo "MATCH: iOS correctly disabled Share and Android independently confirms nothing was sent."
  else
    echo "Android's actual receipt state did NOT confirm as expected (verify script exit=$VERIFY_EXIT) - see output above."
  fi
else
  echo "No iOS outcome file found - script may have exited before recording one."
fi
echo "======================================================"
echo ""
echo "Logcat capture kept at: $LOGCAT_FILE (and copied into $FORENSICS_DIR)"
echo "Screenshots kept at: $FORENSICS_DIR"

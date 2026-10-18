#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-13 - orchestrates the Android (Maestro) and iOS (Appium) halves of a
# BLE proximity presentation that gets interrupted mid-session: iOS taps
# Share, then the Android verifier's own Bluetooth is disabled via adb
# before any response bytes are sent (see tc-13-ios-interrupt-share.py's
# docstring for why "right after Share" rather than trying to time the
# actual ~55ms GATT write burst, which live measurement showed is far
# too tight a window for reactive automation).
#
# What this test is actually checking: whether iOS's UI outcome (success
# screen vs. error vs. hang) matches Android's ACTUAL received state
# (verified independently via its own logcat - tc-13-verify-no-receipt.py),
# same discipline TC-24/25/26 already apply to remote/server-side receipt
# verification. A same-day code investigation
# (ProximityLoadingViewModel/ProximitySessionCoordinator) found the
# app's success screen is driven purely by the third-party SDK's local
# `$status` publisher reaching `.responseSent`, not by any
# application-level confirmation that the verifier actually received the
# response - so this is a real, code-grounded question, not a fishing
# expedition.
#
# Physical prerequisite (one-time, not automated here): same camera rig
# as TC-02/03/04. See README.md in this directory.
#
# Usage:
#   IOS_UDID=<udid> APPLE_TEAM_ID=<team> IOS_BUNDLE_ID=<bundle-id> \
#     ANDROID_UDID=<serial> EUDI_WALLET_PIN=<pin> ./tc-13-ble-mid-transfer-disconnect.sh
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
FORENSICS_DIR="$SCRIPT_DIR/tc13-forensics-$RUN_TS"
LOGCAT_FILE="$(mktemp -t tc13-android-logcat)"

# Safety net: no matter how this script exits, make sure Android's
# Bluetooth ends up back on - tc-13-ios-interrupt-share.py already does
# this in its own finally block, but this is a second, independent
# guarantee in case that script itself crashes before reaching it.
trap 'adb -s "$ANDROID_UDID" shell svc bluetooth enable >/dev/null 2>&1 || true' EXIT

echo "--- Step 1: start Android logcat capture ---"
adb -s "$ANDROID_UDID" logcat -c
adb -s "$ANDROID_UDID" logcat -v time > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!

echo "--- Step 2: start the Android flow in the background (create full-PID request, open scanner) ---"
MAESTRO_TESTS_DIR="$HOME/.maestro/tests"
MAESTRO_START_MARKER="$(mktemp -t tc13-maestro-marker)"
maestro --udid "$ANDROID_UDID" test "$SCRIPT_DIR/tc-13-ble-mid-transfer-disconnect.yaml" &
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

echo "--- Step 3: run the iOS half (trigger QR, tap Share, disable Android Bluetooth, observe outcome) ---"
mkdir -p "$FORENSICS_DIR"
python3 "$SCRIPT_DIR/tc-13-ios-interrupt-share.py" "$IOS_UDID" "$APPLE_TEAM_ID" "$IOS_BUNDLE_ID" "$ANDROID_UDID" "$FORENSICS_DIR" &
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
# UNLIKE TC-02/03/04: a non-zero exit here is the EXPECTED outcome for
# TC-13 (Android's "Show documents" assertion is supposed to time out,
# since Bluetooth was deliberately disabled before any response could
# arrive) - it is not treated as fatal.
wait "$ANDROID_PID" 2>/dev/null
ANDROID_EXIT=$?
echo "Android flow exit=$ANDROID_EXIT (non-zero is EXPECTED here - see yaml header comment)"

echo "--- Step 5: stop logcat capture and verify Android's actual received state ---"
kill "$LOGCAT_PID" 2>/dev/null || true
sleep 1
cp "$LOGCAT_FILE" "$FORENSICS_DIR/android_logcat.txt" 2>/dev/null

python3 "$SCRIPT_DIR/tc-13-verify-no-receipt.py" "$LOGCAT_FILE"
VERIFY_EXIT=$?

echo ""
echo "=================== TC-13 RESULT ==================="
if [ "$IOS_EXIT" -eq 124 ]; then
  echo "iOS script itself TIMED OUT after ${IOS_TIMEOUT_SECONDS}s (script-level hang, not a captured app outcome)."
elif [ "$IOS_EXIT" -ne 0 ]; then
  echo "iOS script FAILED to complete its observation (exit=$IOS_EXIT) - see output above, not a verdict on the app."
elif [ -f "$FORENSICS_DIR/tc13_ios_outcome.txt" ]; then
  IOS_OUTCOME="$(cat "$FORENSICS_DIR/tc13_ios_outcome.txt")"
  echo "iOS displayed:      $IOS_OUTCOME"
  if [ "$VERIFY_EXIT" -eq 0 ]; then
    echo "Android received:   NOTHING (confirmed via logcat - no ResponseReceived)"
    case "$IOS_OUTCOME" in
      SUCCESS_SCREEN*)
        echo ""
        echo "*** MISMATCH: iOS showed SUCCESS while Android received ZERO response bytes. ***"
        echo "*** This is the false-pass scenario - iOS's UI cannot be trusted as evidence of a completed share. ***"
        ;;
      *)
        echo ""
        echo "MATCH: iOS did NOT claim success, consistent with Android's actual non-receipt. Correct behavior."
        ;;
    esac
  else
    echo "Android's actual receipt state could not be independently confirmed (verify script exit=$VERIFY_EXIT) - see output above."
  fi
else
  echo "No iOS outcome file found - script may have exited before recording one."
fi
echo "======================================================"
echo ""
echo "Logcat capture kept at: $LOGCAT_FILE (and copied into $FORENSICS_DIR)"
echo "iOS screenshots kept at: $FORENSICS_DIR"

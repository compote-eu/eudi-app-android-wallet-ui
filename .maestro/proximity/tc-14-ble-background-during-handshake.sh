#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-14 - orchestrates the Android (Maestro) and iOS (Appium) halves of a
# BLE proximity presentation where iOS is backgrounded (not force-killed
# - see TC-47) almost immediately after the QR is displayed, during
# Android's engagement/GATT-connect/request-send sequence, well before
# iOS would normally reach its consent screen.
#
# Same discipline as TC-13: whether iOS's UI outcome after being
# foregrounded again matches Android's ACTUAL logged state (verified
# independently via its own logcat - tc-14-verify-state.py), not
# whatever iOS's screen claims. The original strategy analysis flagged
# this specific class of mismatch before ("wallet showed a stale/
# invalid QR code while verifier reported 'Connected'") - this test
# exists to check whether that's still true.
#
# Physical prerequisite (one-time, not automated here): same camera rig
# as TC-02/03/04/13. See README.md in this directory.
#
# Usage:
#   IOS_UDID=<udid> APPLE_TEAM_ID=<team> IOS_BUNDLE_ID=<bundle-id> \
#     ANDROID_UDID=<serial> EUDI_WALLET_PIN=<pin> ./tc-14-ble-background-during-handshake.sh
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
FORENSICS_DIR="$SCRIPT_DIR/tc14-forensics-$RUN_TS"
LOGCAT_FILE="$(mktemp -t tc14-android-logcat)"

echo "--- Step 1: start Android logcat capture ---"
adb -s "$ANDROID_UDID" logcat -c
adb -s "$ANDROID_UDID" logcat -v time > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

echo "--- Step 2: start the Android flow in the background (create full-PID request, open scanner) ---"
MAESTRO_TESTS_DIR="$HOME/.maestro/tests"
MAESTRO_START_MARKER="$(mktemp -t tc14-maestro-marker)"
maestro --udid "$ANDROID_UDID" test "$SCRIPT_DIR/tc-14-ble-background-during-handshake.yaml" &
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

echo "--- Step 3: run the iOS half (trigger QR, background app, hold, foreground, observe outcome) ---"
mkdir -p "$FORENSICS_DIR"
python3 "$SCRIPT_DIR/tc-14-ios-background-during-handshake.py" "$IOS_UDID" "$APPLE_TEAM_ID" "$IOS_BUNDLE_ID" "$ANDROID_UDID" "$FORENSICS_DIR" &
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
# A non-zero exit here is a plausible/expected outcome for TC-14 too
# (Android's "Show documents" assertion may well time out if iOS never
# recovers cleanly) - not treated as fatal, same as TC-13.
wait "$ANDROID_PID" 2>/dev/null
ANDROID_EXIT=$?
echo "Android flow exit=$ANDROID_EXIT (non-zero is a plausible outcome here - see yaml header comment)"

echo "--- Step 5: stop logcat capture and check Android's actual logged state ---"
kill "$LOGCAT_PID" 2>/dev/null || true
sleep 1
cp "$LOGCAT_FILE" "$FORENSICS_DIR/android_logcat.txt" 2>/dev/null

ANDROID_STATE_OUTPUT="$(python3 "$SCRIPT_DIR/tc-14-verify-state.py" "$LOGCAT_FILE")"
echo "$ANDROID_STATE_OUTPUT"
ANDROID_STAGE="$(echo "$ANDROID_STATE_OUTPUT" | sed -n 's/^Android furthest stage reached: //p')"

echo ""
echo "=================== TC-14 RESULT ==================="
if [ "$IOS_EXIT" -eq 124 ]; then
  echo "iOS script itself TIMED OUT after ${IOS_TIMEOUT_SECONDS}s (script-level hang, not a captured app outcome)."
elif [ "$IOS_EXIT" -ne 0 ]; then
  echo "iOS script FAILED to complete its observation (exit=$IOS_EXIT) - see output above, not a verdict on the app."
elif [ -f "$FORENSICS_DIR/tc14_ios_outcome.txt" ]; then
  IOS_OUTCOME="$(cat "$FORENSICS_DIR/tc14_ios_outcome.txt")"
  echo "iOS displayed (final):     $IOS_OUTCOME"
  echo "Android furthest stage:    $ANDROID_STAGE"
  echo ""
  case "$ANDROID_STAGE" in
    connection_established|request_sent|response_received)
      # Android believes/believed it had an active connection (or further)
      # with this device - iOS should reflect SOME awareness of that
      # (either having recovered to the consent screen, or showing an
      # explicit error) rather than looking like nothing happened.
      case "$IOS_OUTCOME" in
        CONSENT_SCREEN*|SUCCESS_SCREEN*|ALERT_PRESENT*)
          echo "MATCH: Android reached '$ANDROID_STAGE' and iOS shows '$IOS_OUTCOME' - consistent, iOS reflects that something happened."
          ;;
        *)
          echo "*** MISMATCH: Android reached '$ANDROID_STAGE' (it believes/believed a connection was in progress) ***"
          echo "*** but iOS shows '$IOS_OUTCOME' - no sign of that connection ever having happened. ***"
          echo "*** This is the 'verifier says Connected, wallet shows stale/blank state' pattern flagged in the original strategy analysis. ***"
          ;;
      esac
      ;;
    engagement_received)
      echo "Android only got as far as scanning the QR (no GATT connection established) - inconclusive either way; both sides plausibly agree nothing progressed further."
      ;;
    no_engagement_observed)
      echo "Android never even logged scanning the QR - the interruption likely happened before Android's camera had a chance to scan it (rig/timing, not app behavior)."
      ;;
    *)
      echo "Unrecognized Android stage '$ANDROID_STAGE' - see script output above."
      ;;
  esac
else
  echo "No iOS outcome file found - script may have exited before recording one."
fi
echo "======================================================"
echo ""
echo "Logcat capture kept at: $LOGCAT_FILE (and copied into $FORENSICS_DIR)"
echo "Screenshots (both iOS and Android, at each phase) kept at: $FORENSICS_DIR"

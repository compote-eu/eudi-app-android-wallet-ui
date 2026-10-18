#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-04 - orchestrates the Android (Maestro) and iOS (Appium) halves of a
# BLE proximity presentation where Android requests the FULL PID but
# iOS's consent screen shares only a PARTIAL subset - the original
# manual-testing freeze repro (app froze after presenting, needed a
# reinstall to recover, never reproduced a second time back then).
#
# A variation of TC-03's orchestrator (tc-03-ble-full-pid.sh), reusing
# the same proven infrastructure and both already-confirmed root-cause
# fixes (Maestro driver-readiness polling, splash-screen-aware PIN
# handling) unchanged. What's different: tc-04-ios-partial-share.py
# deselects two requested fields on the consent screen before tapping
# Share (see that script's docstring for how the tap coordinates were
# derived), and this orchestrator adds freeze forensics - if the iOS
# half hangs, it captures logs + screenshots from BOTH devices before
# anything else happens, since the original bug required a reinstall to
# recover and left no diagnostic trail.
#
# Physical prerequisite (one-time, not automated here): same camera rig
# as TC-02/TC-03. See README.md in this directory.
#
# Usage:
#   IOS_UDID=<udid> APPLE_TEAM_ID=<team> IOS_BUNDLE_ID=<bundle-id> \
#     ANDROID_UDID=<serial> EUDI_WALLET_PIN=<pin> ./tc-04-ble-full-pid-partial-share.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_UDID="${IOS_UDID:?Set IOS_UDID to the physical iPhone's UDID}"
APPLE_TEAM_ID="${APPLE_TEAM_ID:?Set APPLE_TEAM_ID}"
IOS_BUNDLE_ID="${IOS_BUNDLE_ID:?Set IOS_BUNDLE_ID}"
ANDROID_UDID="${ANDROID_UDID:?Set ANDROID_UDID to the physical Android device's adb serial}"
: "${EUDI_WALLET_PIN:?Set EUDI_WALLET_PIN}"

# iOS hangs (a real freeze, not just a slow step) should not block this
# script forever - the original bug needed a reinstall to recover, so an
# unbounded wait here would just leave the rig stuck. This timeout is
# generous (the iOS half normally completes in well under a minute past
# the consent screen) specifically so a genuine freeze is what trips it,
# not a slow-but-healthy run.
IOS_TIMEOUT_SECONDS="${IOS_TIMEOUT_SECONDS:-180}"

export PATH="$HOME/.maestro/bin:$PATH"

RUN_TS="$(date +%Y%m%d_%H%M%S 2>/dev/null || echo run)"
FORENSICS_DIR="$SCRIPT_DIR/tc04-forensics-$RUN_TS"
LOGCAT_FILE="$(mktemp -t tc04-android-logcat)"

capture_forensics() {
  # Freeze forensics: capture BOTH devices' state before anything else -
  # no recovery action (reinstall, force-kill) is taken automatically.
  # See tc-04-ios-partial-share.py's except block for the iOS-side
  # screenshot attempt this complements (it may itself fail if WDA is
  # unresponsive during a genuine freeze, which is itself diagnostic).
  echo "--- Capturing freeze forensics into $FORENSICS_DIR ---"
  mkdir -p "$FORENSICS_DIR"
  adb -s "$ANDROID_UDID" exec-out screencap -p > "$FORENSICS_DIR/android_screen.png" 2>/dev/null
  adb -s "$ANDROID_UDID" shell dumpsys activity activities > "$FORENSICS_DIR/android_activity_dump.txt" 2>/dev/null
  cp "$LOGCAT_FILE" "$FORENSICS_DIR/android_logcat.txt" 2>/dev/null
  xcrun devicectl device info details --device "$IOS_UDID" > "$FORENSICS_DIR/ios_device_info.txt" 2>/dev/null
  echo "Forensics captured (best-effort - some files may be empty if the device was unresponsive)."
}

echo "--- Step 1: start Android logcat capture ---"
adb -s "$ANDROID_UDID" logcat -c
adb -s "$ANDROID_UDID" logcat -v time > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

echo "--- Step 2: start the Android flow in the background (create full-PID request, open scanner, wait for result) ---"
MAESTRO_TESTS_DIR="$HOME/.maestro/tests"
MAESTRO_START_MARKER="$(mktemp -t tc04-maestro-marker)"
maestro --udid "$ANDROID_UDID" test "$SCRIPT_DIR/tc-04-ble-full-pid-partial-share.yaml" &
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

echo "--- Step 3: run the iOS half (trigger QR, deselect two fields, confirm partial share + PIN) ---"
mkdir -p "$FORENSICS_DIR"
# Neither `timeout` nor `gtimeout` is installed on this Mac (confirmed
# live, 2026-08-27 - coreutils isn't present) - a manual background
# process + watchdog kill is this project's existing substitute pattern
# elsewhere for the same reason.
python3 "$SCRIPT_DIR/tc-04-ios-partial-share.py" "$IOS_UDID" "$APPLE_TEAM_ID" "$IOS_BUNDLE_ID" "$FORENSICS_DIR" &
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

if [ "$IOS_EXIT" -eq 124 ]; then
  echo "!!! iOS half TIMED OUT after ${IOS_TIMEOUT_SECONDS}s - this is consistent with the original TC-04 freeze. !!!"
  capture_forensics
  kill "$ANDROID_PID" 2>/dev/null || true
  kill "$LOGCAT_PID" 2>/dev/null || true
  trap - EXIT
  echo "TC-04: FAIL (iOS half froze/timed out - forensics in $FORENSICS_DIR). No recovery action taken automatically."
  exit 124
fi

echo "--- Step 4: wait for the Android flow to reach its final assertion ---"
wait "$ANDROID_PID"
ANDROID_EXIT=$?

echo "--- Step 5: stop logcat capture and verify received attributes ---"
kill "$LOGCAT_PID" 2>/dev/null || true
trap - EXIT
sleep 1

python3 "$SCRIPT_DIR/tc-04-verify-attributes.py" "$LOGCAT_FILE"
VERIFY_EXIT=$?

if [ "$IOS_EXIT" -ne 0 ] || [ "$ANDROID_EXIT" -ne 0 ] || [ "$VERIFY_EXIT" -ne 0 ]; then
  echo "TC-04: FAIL (ios_exit=$IOS_EXIT android_exit=$ANDROID_EXIT verify_exit=$VERIFY_EXIT)."
  echo "Logcat capture kept at: $LOGCAT_FILE"
  echo "iOS screenshots (if any) kept at: $FORENSICS_DIR"
  exit 1
fi

echo "TC-04: PASS."
echo "Logcat capture kept at: $LOGCAT_FILE"
echo "Pre-share screenshot kept at: $FORENSICS_DIR"

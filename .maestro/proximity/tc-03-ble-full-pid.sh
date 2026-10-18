#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-03 - orchestrates the Android (Maestro) and iOS (Appium) halves of a
# BLE proximity presentation with the full PID (no attribute exclusions).
#
# A copy of TC-02's orchestrator (tc-02-ble-custom-attributes.sh),
# updated for the different Android request-creation step (see
# tc-03-ble-full-pid.yaml) and file references. The Maestro-driver
# readiness wait below is TC-02's already-confirmed fix, unchanged - see
# that script's own comment for why it exists.
#
# Physical prerequisite (one-time, not automated here): Android's camera
# fixed in a rig aimed at the iPhone's screen location, focus/distance
# already verified. See README.md in this directory.
#
# Why a shell orchestrator, not a single Maestro flow: this test spans two
# physical devices driven by two different tools - Maestro for Android,
# Appium for iOS, since Maestro's physical-iOS support was confirmed not
# viable here (see the TC-37 investigation: an upstream packaging bug in
# its bundled driver, and no automatic port-forwarding to the on-device
# runner even after patching that). Android's flow runs in the background
# (tc-03-ble-full-pid.yaml blocks on extendedWaitUntil while the
# iOS script does its part), matching the pattern already used by
# tc-32-reinstall-wipe.sh and tc-47-force-kill-recovery.sh for
# orchestration a pure Maestro flow or a pure Appium script can't do alone.
#
# Usage:
#   IOS_UDID=<udid> APPLE_TEAM_ID=<team> IOS_BUNDLE_ID=<bundle-id> \
#     ANDROID_UDID=<serial> EUDI_WALLET_PIN=<pin> ./tc-03-ble-full-pid.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_UDID="${IOS_UDID:?Set IOS_UDID to the physical iPhone's UDID}"
APPLE_TEAM_ID="${APPLE_TEAM_ID:?Set APPLE_TEAM_ID}"
IOS_BUNDLE_ID="${IOS_BUNDLE_ID:?Set IOS_BUNDLE_ID}"
ANDROID_UDID="${ANDROID_UDID:?Set ANDROID_UDID to the physical Android device's adb serial}"
: "${EUDI_WALLET_PIN:?Set EUDI_WALLET_PIN}"

export PATH="$HOME/.maestro/bin:$PATH"

LOGCAT_FILE="$(mktemp -t tc03-android-logcat)"

echo "--- Step 1: start Android logcat capture ---"
adb -s "$ANDROID_UDID" logcat -c
adb -s "$ANDROID_UDID" logcat -v time > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

echo "--- Step 2: start the Android flow in the background (create full-PID request, open scanner, wait for result) ---"
MAESTRO_TESTS_DIR="$HOME/.maestro/tests"
MAESTRO_START_MARKER="$(mktemp -t tc03-maestro-marker)"
maestro --udid "$ANDROID_UDID" test "$SCRIPT_DIR/tc-03-ble-full-pid.yaml" &
ANDROID_PID=$!

# Maestro's own device-driver connection (UiAutomator2/instrumentation) can
# intermittently hang indefinitely during setup, before running a single
# flow command, if a previous "maestro test" run against this same device
# finished only moments earlier - confirmed live (2026-08-26): one run
# started ~90s after the prior one finished never got past "Selected
# device..." in its own maestro.log (no "Running flow...", no "Launch
# app...", nothing - Android's app was never even opened), while the same
# flow given a longer gap set up normally in ~7-8s every time. Polling for
# Maestro's own "Running flow" log line - rather than a fixed sleep - is
# the same preference this project already has for extendedWaitUntil over
# fixed waits elsewhere.
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
  echo "Maestro's driver setup did not reach 'Running flow' within 60s - this is the startup hang investigated 2026-08-26, not a rig/timing issue on the iOS side. Aborting before running the iOS half, which would otherwise just time out waiting for a screen Android never reaches."
  kill "$ANDROID_PID" 2>/dev/null || true
  kill "$LOGCAT_PID" 2>/dev/null || true
  exit 1
fi
echo "Maestro is running ($MAESTRO_LOG)"

echo "--- Step 3: run the iOS half (trigger QR, confirm share + PIN) ---"
set +e
python3 "$SCRIPT_DIR/tc-03-ios-share.py" "$IOS_UDID" "$APPLE_TEAM_ID" "$IOS_BUNDLE_ID"
IOS_EXIT=$?
set -e

echo "--- Step 4: wait for the Android flow to reach its final assertion ---"
set +e
wait "$ANDROID_PID"
ANDROID_EXIT=$?
set -e

echo "--- Step 5: stop logcat capture and verify received attributes ---"
kill "$LOGCAT_PID" 2>/dev/null || true
trap - EXIT
sleep 1

set +e
python3 "$SCRIPT_DIR/tc-03-verify-attributes.py" "$LOGCAT_FILE"
VERIFY_EXIT=$?
set -e

if [ "$IOS_EXIT" -ne 0 ] || [ "$ANDROID_EXIT" -ne 0 ] || [ "$VERIFY_EXIT" -ne 0 ]; then
  echo "TC-03: FAIL (ios_exit=$IOS_EXIT android_exit=$ANDROID_EXIT verify_exit=$VERIFY_EXIT)."
  echo "Logcat capture kept at: $LOGCAT_FILE"
  exit 1
fi

echo "TC-03: PASS."
echo "Logcat capture kept at: $LOGCAT_FILE"

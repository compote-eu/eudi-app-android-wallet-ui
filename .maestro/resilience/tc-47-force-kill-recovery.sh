#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-47 — force-kill mid-flow recovery.
#
# Orchestrates what a pure Maestro flow structurally cannot do itself
# (same reasoning as tc-32-reinstall-wipe.sh): runs the UI-driving flow
# up to a genuine mid-flow interruption point
# (tc-47-force-kill-recovery.yaml), force-kills the app via
# `xcrun simctl terminate` (not a graceful background — an actual kill,
# simulating a crash or OS-initiated termination), relaunches it via
# `xcrun simctl launch`, then hands off to a second Maestro flow
# (tc-47-verify-recovery.yaml) to confirm the app recovers to a
# consistent state. Maestro's runScript JS engine has no process/exec
# capability at all (confirmed by decompiling maestro-client.jar's
# GraalJsEngine during TC-32's development), so the terminate/launch
# cycle structurally cannot live inside a .yaml flow itself.
#
# Precondition: chained directly after TC-01 in the same simulator
# session (PIN already set up, PID already issued, no real mDL yet). See
# tc-47-force-kill-recovery.yaml and tc-47-verify-recovery.yaml's own
# comments for the exact precondition and assertions.
#
# Usage: UDID=<udid> ./tc-47-force-kill-recovery.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UDID="${UDID:?Set UDID to the target simulator UDID}"
BUNDLE_ID="eu.europa.ec.euidi.dev"

export PATH="$HOME/.maestro/bin:$PATH"

echo "--- Step 1: drive the UI to a genuine mid-flow interruption point ---"
maestro --udid "$UDID" test "$SCRIPT_DIR/tc-47-force-kill-recovery.yaml"

echo "--- Step 2: force-kill the app (not a graceful background) ---"
xcrun simctl terminate "$UDID" "$BUNDLE_ID"

echo "--- Step 3: relaunch ---"
xcrun simctl launch "$UDID" "$BUNDLE_ID"
sleep 3

echo "--- Step 4: verify the app recovers to a consistent state ---"
maestro --udid "$UDID" test "$SCRIPT_DIR/tc-47-verify-recovery.yaml"

echo "--- TC-47 PASSED: app recovers to a consistent state after a force-kill mid-flow, no partial/corrupted document left behind ---"

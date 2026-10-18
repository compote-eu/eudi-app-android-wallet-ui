#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-05 - BLE disabled on iOS before attempting to share.
#
# Unlike every other test in this directory, this one needs NO Android
# device, no Maestro, and no camera rig at all - the interruption (BLE
# turned off) happens before any BLE session is ever attempted, so
# there is nothing on the Android side to drive or verify. This wrapper
# exists only for consistency with this directory's other tc-*.sh
# entry points (env var validation, forensics dir), not because there's
# real orchestration to do - see tc-05-ios-ble-disabled.py for the
# actual logic (it does the whole thing: toggle Bluetooth off via
# Settings, trigger a share attempt, check the resulting alert,
# restore Bluetooth, confirm recovery).
#
# Usage:
#   IOS_UDID=<udid> APPLE_TEAM_ID=<team> IOS_BUNDLE_ID=<bundle-id> \
#     EUDI_WALLET_PIN=<pin> ./tc-05-ble-disabled.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_UDID="${IOS_UDID:?Set IOS_UDID to the physical iPhone UDID}"
APPLE_TEAM_ID="${APPLE_TEAM_ID:?Set APPLE_TEAM_ID}"
IOS_BUNDLE_ID="${IOS_BUNDLE_ID:?Set IOS_BUNDLE_ID}"
: "${EUDI_WALLET_PIN:?Set EUDI_WALLET_PIN}"

RUN_TS="$(date +%Y%m%d_%H%M%S 2>/dev/null || echo run)"
FORENSICS_DIR="$SCRIPT_DIR/tc05-forensics-$RUN_TS"
mkdir -p "$FORENSICS_DIR"

python3 "$SCRIPT_DIR/tc-05-ios-ble-disabled.py" "$IOS_UDID" "$APPLE_TEAM_ID" "$IOS_BUNDLE_ID" "$FORENSICS_DIR"
EXIT=$?

echo ""
if [ "$EXIT" -eq 0 ]; then
  echo "TC-05: PASS."
else
  echo "TC-05: FAIL (exit=$EXIT) - see output above."
fi
echo "Screenshots kept at: $FORENSICS_DIR"
exit "$EXIT"

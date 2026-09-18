#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-32 — reinstall wipe: verifies that a fresh reinstall genuinely clears
# previously-issued documents at the STORAGE level, not just that the
# Documents list happens to render empty.
#
# Why this is a shell script, not a single Maestro .yaml flow: this test's
# whole point requires xcrun simctl uninstall/install/launch and a direct
# sqlite3 query against the Simulator's Keychain database between Maestro
# steps - none of which a Maestro flow can do itself. Maestro's runScript
# JS engine only exposes four bindings (http, output, env, and an internal
# maestro one - confirmed by decompiling maestro-client.jar's GraalJsEngine
# during this test's development), with no process/exec/shell capability at
# all. A pure-YAML flow structurally cannot uninstall or reinstall the app
# it's testing, or query anything outside the app's own UI. This script is
# the orchestrating layer Maestro itself can't be; it calls out to
# `maestro test` only for the one piece that genuinely is a Maestro flow
# (tc-32-verify-empty-state.yaml, the UI-level check after reinstall).
#
# Precondition: app already has documents from prior flows in this same
#   simulator session (PID from TC-01, mDL from TC-50) - this script does
#   NOT issue them itself, matching the rest of this suite's "chained
#   flows share one session" convention. Maestro Studio must be closed
#   before running this (same driver-conflict reason as every other flow
#   in this suite - see .maestro/README.md).
#
# IMPORTANT for future CI wiring (not done yet - see this test's own
# report): this script uninstalls and reinstalls the app, which wipes
# every document any OTHER flow chained after it would expect to find.
# It must run LAST in whatever session it's part of, or in its own
# dedicated job with its own fresh erase -> build -> install -> TC-01 ->
# TC-50 -> (this script) sequence - never in the middle of the existing
# TC-01 -> TC-22 -> ... -> TC-30 chain.
#
# Usage: UDID=<udid> APP_PATH=<path to .app> ./tc-32-reinstall-wipe.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UDID="${UDID:?Set UDID to the target simulator UDID}"
APP_PATH="${APP_PATH:?Set APP_PATH to the built .app to reinstall}"
BUNDLE_ID="eu.europa.ec.euidi.dev"

check_count() {
  "$SCRIPT_DIR/check-keychain-count.sh" "$UDID"
}

echo "--- Step 1: confirm the Keychain currently has documents (precondition) ---"
BEFORE_COUNT=$(check_count)
echo "Keychain rows for this access group: $BEFORE_COUNT"
if [ "$BEFORE_COUNT" -eq 0 ]; then
  echo "FAIL: expected non-zero Keychain rows before starting (precondition not met - did TC-01/TC-50 run in this session?)" >&2
  exit 1
fi

echo "--- Step 2: plain uninstall (not simctl erase) ---"
xcrun simctl uninstall "$UDID" "$BUNDLE_ID"

echo "--- Step 3: confirm the Keychain STILL has those documents after plain uninstall ---"
# This is TC-30's already-documented finding, demonstrated fresh here: a
# plain uninstall does not touch Keychain items, since they're scoped to
# the keychain-access-group, not tied to the app's own install lifecycle.
AFTER_UNINSTALL_COUNT=$(check_count)
echo "Keychain rows for this access group after uninstall: $AFTER_UNINSTALL_COUNT"
if [ "$AFTER_UNINSTALL_COUNT" -ne "$BEFORE_COUNT" ]; then
  echo "FAIL: expected the Keychain row count to be UNCHANGED after a plain uninstall (still $BEFORE_COUNT), got $AFTER_UNINSTALL_COUNT - the stale-data precondition this test relies on no longer holds." >&2
  exit 1
fi

echo "--- Step 4: reinstall and launch fresh ---"
xcrun simctl install "$UDID" "$APP_PATH"
xcrun simctl launch "$UDID" "$BUNDLE_ID"
sleep 3

echo "--- Step 5: confirm the Keychain is genuinely wiped (app data), not literally empty ---"
# This is the actual thing under test: does StartupInteractor.
# manageStorageForFirstRun() genuinely wipe the Keychain, or only
# app-level metadata while leaving stale Keychain items orphaned (the
# same "storage vs. display" gap pattern as the History tab bug)?
#
# MAX_BENIGN_KEYCHAIN_ROWS = 2, not 0: confirmed via a live
# entitlement-removal experiment (build with
# `com.apple.developer.identity-document-services.document-provider.
# mobile-document-types` temporarily stripped from EudiWallet.
# entitlements, then reverted) that a fresh reinstall of the NORMAL app
# always leaves exactly 2 Keychain rows behind (129/133/191 -> 2 across
# several runs with varying precondition document counts), while the
# SAME reinstall with that entitlement removed drops cleanly to 0. These
# 2 rows are OS-managed registration bookkeeping for
# EudiReferenceWalletIDProvider, written by the system daemon behind
# Apple's IdentityDocumentServices/ExtensionKit document-provider
# mechanism at install/launch time - confirmed to already exist BEFORE
# any user interaction (checked at T+0s immediately after `simctl
# launch`, before the app's own UI is even touched, ruling out anything
# PIN- or document-related). This is genuinely outside StartupInteractor/
# KeyChainController's control - it's the OS registering the extension
# into the same keychain-access-group, not app data surviving a wipe
# gone wrong. Anything beyond 2 is still a real failure of this test's
# own actual subject (the app's OWN wipe of document/credential data).
MAX_BENIGN_KEYCHAIN_ROWS=2
AFTER_REINSTALL_COUNT=$(check_count)
echo "Keychain rows for this access group after reinstall + launch: $AFTER_REINSTALL_COUNT (allowing up to $MAX_BENIGN_KEYCHAIN_ROWS known-benign OS-registration rows)"
if [ "$AFTER_REINSTALL_COUNT" -gt "$MAX_BENIGN_KEYCHAIN_ROWS" ]; then
  echo "FAIL: expected at most $MAX_BENIGN_KEYCHAIN_ROWS Keychain rows (known-benign IdentityDocumentServices registration bookkeeping) after a fresh reinstall, got $AFTER_REINSTALL_COUNT - StartupInteractor's wipe did not actually clear the app's own Keychain data." >&2
  exit 1
fi

echo "--- Step 6: verify the UI matches (fresh PIN setup, empty Documents) ---"
export PATH="$HOME/.maestro/bin:$PATH"
maestro --udid "$UDID" test "$SCRIPT_DIR/tc-32-verify-empty-state.yaml"

echo "--- TC-32 PASSED: reinstall genuinely wipes the Keychain, confirmed at storage level and UI level ---"

#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-46 — connection loss during remote/OpenID4VP presentation.
#
# The remote-presentation counterpart to TC-13's BLE false-positive
# check: does this app ever show "successfully shared" when the
# verifier actually received nothing, on the HTTPS/OpenID4VP transport
# instead of BLE? Confirmed live (see .maestro/presentation/README.md
# for the full write-up): no - the app fails closed with a genuine
# error, matching the verifier's own non-receipt. This script exists to
# make that a permanent regression guard, not just a one-off finding.
#
# Why this is a shell script, not a pure Maestro flow (same reasoning as
# tc-32-reinstall-wipe.sh): the actual thing under test requires taking
# the HOST machine offline at a precise moment and independently
# querying the verifier's own event log afterward - Maestro's runScript
# JS engine has no shell/process access at all (confirmed during TC-32's
# development: only http, output, env, and an internal maestro binding
# exist). A pure-YAML flow structurally cannot do either half of this
# test. This script is the orchestrating layer Maestro itself can't be:
# it fetches the presentation request and verifies server-side receipt
# itself (via curl, not Maestro's http binding, since this needs to run
# host-side anyway - see tc-46-setup-and-share.yaml's own comments on
# why output.* can't cross the two `maestro test` invocations below),
# and calls out to Maestro only for the two genuinely UI pieces
# (tc-46-setup-and-share.yaml, tc-46-finish-pin-offline.yaml), run as
# two separate `maestro test` invocations against the SAME persistent
# simulator session - app state (5 of 6 PIN digits already entered)
# carries over between them, the same pattern tc-32-reinstall-wipe.sh
# already relies on for its own reinstall+relaunch step.
#
# THE PITFALL THAT NEARLY PRODUCED A FALSE NEGATIVE, live, during this
# test's development: disabling only Wi-Fi (`networksetup
# -setairportpower en0 off`) does NOT reliably take a Mac offline. On
# the machine this was developed on, a physical iPhone was connected via
# USB with Personal Hotspot enabled (network service "iPhone USB") -
# macOS silently failed the default route over to it the instant Wi-Fi
# went down, and a `curl` to the verifier during that "interruption"
# got a completely normal response. The app's subsequent "successfully
# shared" result was CORRECT given the network was never actually
# down - it would have been wrongly read as a bug in this test's design,
# not the app, had this not been caught by independently checking
# `route get default` and a real `curl` call rather than trusting the
# Wi-Fi toggle alone. This script disables EVERY currently-enabled
# `networksetup` service (not just Wi-Fi) for exactly this reason, and
# refuses to proceed - failing loudly instead of silently passing a
# meaningless test - unless a `curl` call independently confirms the
# host is genuinely unreachable first.
#
# NOT wired into maestro-simulator.yml's main chain, deliberately - this
# script disables every network service on the host Mac to simulate
# connection loss, which would sever the self-hosted CI runner's own
# connection back to GitHub Actions mid-job (same system-wide
# host-network risk category as TC-21). Kept as a standalone,
# manually-run verified flow, same treatment as TC-21/TC-49/TC-51.
#
# Usage: UDID=<simulator udid> ./tc-46-network-loss-during-share.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UDID="${UDID:?Set UDID to the target simulator UDID}"
VERIFIER_BASE="https://dev.verifier-backend.eudiw.dev"

DISABLED_SERVICES=()

# Re-enables every service this script itself disabled - runs on ANY
# exit path (success, failure, or an interrupted run), same "restore
# regardless of outcome" discipline as TC-05's Bluetooth toggle.
restore_network() {
  if [ "${#DISABLED_SERVICES[@]}" -gt 0 ]; then
    echo "--- Restoring network services: ${DISABLED_SERVICES[*]} ---"
    for svc in "${DISABLED_SERVICES[@]}"; do
      networksetup -setnetworkserviceenabled "$svc" on || true
    done
    DISABLED_SERVICES=()
  fi
}
trap restore_network EXIT

check_online() {
  # curl itself already prints "000" for %{http_code} on a connection
  # failure (confirmed live) - a `|| echo "000"` fallback here double-
  # counts that and returns "000000" instead, since curl's own non-zero
  # exit status still triggers the fallback AFTER "000" was already
  # written. No fallback needed; default only covers a genuinely empty
  # (not "000") result.
  local code
  code=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "$VERIFIER_BASE/ui/presentations/connectivity-check" 2>/dev/null)
  echo "${code:-000}"
}

echo "--- Step 1: fetch a fresh presentation request from the live verifier (same DCQL query as TC-22) ---"
SETUP_OUTPUT="$(python3 - <<'PYSCRIPT'
import json, urllib.request, time, random
body = {
  "dcql_query": {
    "credentials": [{
      "id": "pid-cred", "format": "mso_mdoc",
      "meta": {"doctype_value": "eu.europa.ec.eudi.pid.1"},
      "claims": [{"path": ["eu.europa.ec.eudi.pid.1", "family_name"]}]
    }],
    "credential_sets": [{"options": [["pid-cred"]], "purpose": "We need to verify your identity"}]
  },
  "jar_mode": "by_reference",
  "nonce": str(int(time.time() * 1000)) + "-" + str(random.random()),
  "profile": "openid4vp",
  "intended_use_id": "TEST-01"
}
req = urllib.request.Request(
    "https://dev.verifier-backend.eudiw.dev/ui/presentations/v2",
    data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json"},
    method="POST")
with urllib.request.urlopen(req, timeout=30) as resp:
    parsed = json.loads(resp.read())
print(parsed["authorization_request_uri"], parsed["transaction_id"])
PYSCRIPT
)"
read -r PRESENTATION_URL TRANSACTION_ID <<< "$SETUP_OUTPUT"
echo "transaction_id=$TRANSACTION_ID"

echo "--- Step 2: trigger it, Share, first 5 of 6 PIN digits (Maestro, network still up) ---"
export PATH="$HOME/.maestro/bin:$PATH"
maestro --udid "$UDID" test "$SCRIPT_DIR/tc-46-setup-and-share.yaml" -e PRESENTATION_URL="$PRESENTATION_URL"

echo "--- Step 3: take the HOST genuinely offline - every enabled network service, not just Wi-Fi ---"
while IFS= read -r line; do
  case "$line" in
    \**) continue ;;  # skip services already disabled (marked with *)
  esac
  DISABLED_SERVICES+=("$line")
  networksetup -setnetworkserviceenabled "$line" off
done < <(networksetup -listallnetworkservices | tail -n +2)

sleep 1
CODE=$(check_online)
if [ "$CODE" != "000" ]; then
  echo "FAIL: expected genuinely offline (curl code 000), got $CODE - a secondary network path is still providing connectivity (Personal Hotspot/USB tethering silently taking over the default route is exactly what happened during this test's own development - see this script's header). Refusing to run a false-negative test." >&2
  exit 1
fi
echo "Confirmed genuinely offline (curl -> $CODE)"

echo "--- Step 4: submit the final PIN digit while offline, confirm the app fails closed (Maestro) ---"
maestro --udid "$UDID" test "$SCRIPT_DIR/tc-46-finish-pin-offline.yaml"

echo "--- Step 5: restore network, confirm it's genuinely back before trusting the server-side checks below ---"
restore_network
# Wi-Fi reassociation/DHCP genuinely takes a few seconds after
# re-enabling - confirmed live that a fixed 2s sleep was too short (the
# very next manual check, ~3s later, succeeded). Poll instead of
# guessing a fixed delay.
CODE="000"
for _ in $(seq 1 10); do
  sleep 1.5
  CODE=$(check_online)
  [ "$CODE" != "000" ] && break
done
if [ "$CODE" = "000" ]; then
  echo "FAIL: network did not come back after restoring services (waited 15s)" >&2
  exit 1
fi
echo "Network restored (curl -> $CODE)"

echo "--- Step 6: verify server-side that nothing was ever received (same discipline as tc-24-verify-no-receipt.js) ---"
MAIN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$VERIFIER_BASE/ui/presentations/$TRANSACTION_ID")
if [ "$MAIN_STATUS" = "200" ]; then
  echo "FAIL: verifier GET /ui/presentations/{id} returned 200 (expected non-200 for a never-submitted transaction) - data WAS received despite the connection loss." >&2
  exit 1
fi

EVENTS_JSON=$(curl -s "$VERIFIER_BASE/ui/presentations/$TRANSACTION_ID/events")
WALLET_RESPONSE_POSTED=$(python3 -c "
import json, sys
parsed = json.loads(sys.argv[1])
events = [e.get('event') for e in parsed.get('events', [])]
print('yes' if 'Wallet response posted' in events else 'no', file=sys.stdout)
print(events, file=sys.stderr)
" "$EVENTS_JSON")

if [ "$WALLET_RESPONSE_POSTED" = "yes" ]; then
  echo "FAIL: verifier recorded a 'Wallet response posted' event despite the connection being down when the wallet attempted to send - data leaked through a connection that should have failed." >&2
  exit 1
fi

echo "TC-46 PASSED: app correctly failed closed on real connection loss (OpenID4VP.PostError shown, not a false success), and the verifier's own event log independently confirms zero receipt - matching, not contradicting, the app's own error. Contrast with TC-13 (BLE), where this same class of check caught a false positive."

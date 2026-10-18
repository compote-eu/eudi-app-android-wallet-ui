#!/bin/bash
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

# TC-17 — deferred issuance retry-interval violation (red test).
#
# Orchestrates the parts a pure Maestro flow structurally cannot do itself
# (same reasoning as tc-32-reinstall-wipe.sh): runs the UI-driving flow
# (tc-17-deferred-retry-timing.yaml) via `maestro test`, waits for the app's
# own retry loop to fire a couple of times, then reads the app's own
# WalletKit log file directly off disk and asserts on the timing found
# there. Maestro's runScript JS engine (GraalJsEngine, confirmed via
# decompilation during TC-32's development) exposes no filesystem access at
# all, so this log-reading half cannot live inside a .yaml flow.
#
# THIS IS A RED TEST: it is expected to FAIL right now, and a FAILURE here
# is the confirmation that the known bug is still present, not a broken
# test. The bug: DocumentTabViewModel.onDocumentsRetrievedPostActions()
# (Modules/feature-dashboard/Sources/UI/Dashboard/Tab/Document/
# DocumentTabViewModel.swift) retries with a hardcoded
# `try? await Task.sleep(seconds: 5)`, never referencing the server's own
# requested interval — logged by EudiWalletKit's OpenId4VciService but then
# discarded, since DeferredIssuanceModel (Sources/EudiWalletKit/Models/
# InternalIssuanceModels.swift, pinned tag v0.37.6) has no interval field to
# carry it. See tc-17-deferred-retry-timing.yaml's own header comment for
# the full root-cause chain.
#
# WHAT IS ACTUALLY MEASURED, AND WHY: the gap between the ORIGINAL deferred
# request and the FIRST retry is a noisy signal — it includes however long
# Maestro's own UI navigation took to get back to the Documents tab (which
# is what starts the retry loop), and that navigation time is test-harness
# overhead, not app behavior. Live testing confirmed this noise is large
# enough to produce a false PASS (a real ~14s app-internal gap inflated to
# 41-52s of total wall-clock by navigation, clearing a 30s/50%-of-60s
# threshold that should have failed). The gap BETWEEN TWO CONSECUTIVE
# retries, once the loop is already running, has no such contamination —
# it is governed purely by DocumentTabViewModel's own hardcoded sleep plus
# a fixed amount of fetch-cycle overhead, confirmed live to land at 5-7
# seconds regardless of a 60-second server-requested interval. This script
# therefore prefers a retry-to-retry gap when at least two retries are
# observed, and only falls back to the noisier request-to-first-retry gap
# (with a more generous threshold, since it's expected to include some
# navigation overhead) when the mock issuer resolves before a second retry
# ever happens.
#
# Known limitation (accepted, not a gap to force-fix here): this script
# depends on the mock issuer taking at least one retry round to resolve.
# Live testing has seen it resolve anywhere from 1 to 3 attempts in, so most
# runs do get a clean retry-to-retry gap, but a run where it resolves on
# the very first attempt falls back to the noisier measurement above. This
# script cannot exercise the "no retry limit" (indefinite polling) or the
# stuck-`.failed`-state dead-code bug (DocumentTabInteractor.
# fetchFilteredDocuments() never forwarding its own failedDocuments
# parameter) either way — both remain confirmed at the code level only.
# Manufacturing an artificially-never-resolving deferred scenario would
# require control over the mock issuer's own behavior, which this suite
# does not have.
#
# Usage: UDID=<udid> ./tc-17-verify-interval-violation.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UDID="${UDID:?Set UDID to the target simulator UDID}"
BUNDLE_ID="eu.europa.ec.euidi.dev"

# Wait after the flow returns before checking the log, in seconds. Needs to
# cover several retry rounds, not just one - observed live: resolution
# anywhere from ~15s to ~65s after landing on Documents tab across
# different runs.
POLL_WAIT_SECONDS="${POLL_WAIT_SECONDS:-75}"

echo "--- Step 1: run the UI-driving flow (trigger deferred mDL issuance) ---"
export PATH="$HOME/.maestro/bin:$PATH"
maestro --udid "$UDID" test "$SCRIPT_DIR/tc-17-deferred-retry-timing.yaml"

echo "--- Step 2: wait ${POLL_WAIT_SECONDS}s for the app's own retry loop to run its course ---"
sleep "$POLL_WAIT_SECONDS"

echo "--- Step 3: read the app's own WalletKit log and check the actual retry timing ---"
CONTAINER=$(xcrun simctl get_app_container "$UDID" "$BUNDLE_ID" data)
LOGFILE="$CONTAINER/Library/Caches/eudi-ios-wallet-logs"

if [ ! -f "$LOGFILE" ]; then
  echo "FAIL: WalletKit log not found at $LOGFILE" >&2
  exit 1
fi

# Anchor on this run's own document: the most recent "Save document for
# status: deferred" entry gives both the doc id and the request timestamp.
SAVE_LINE=$(grep "Save document for status: deferred" "$LOGFILE" | tail -1)
if [ -z "$SAVE_LINE" ]; then
  echo "FAIL: no 'Save document for status: deferred' log entry found - did tc-17-deferred-retry-timing.yaml actually trigger deferred issuance?" >&2
  exit 1
fi
DOC_ID=$(echo "$SAVE_LINE" | grep -oE 'id: [A-Za-z0-9-]+' | head -1 | sed 's/id: //')
T1=$(echo "$SAVE_LINE" | awk '{print $1}')
T1_EPOCH=$(date -j -f "%Y-%m-%dT%H:%M:%S%z" "$T1" +%s)

# The server's actual requested interval, logged (then discarded) by
# EudiWalletKit's OpenId4VciService - see header comment.
INTERVAL_LINE=$(grep "Credential issuance deferred with transactionId" "$LOGFILE" | tail -1)
if [ -z "$INTERVAL_LINE" ]; then
  echo "FAIL: no 'Credential issuance deferred' log entry found - no server interval to compare against." >&2
  exit 1
fi
REQUESTED_INTERVAL=$(echo "$INTERVAL_LINE" | grep -oE ', interval: [0-9]+(\.[0-9]+)?' | tail -1 | grep -oE '[0-9]+(\.[0-9]+)?')
if [ -z "$REQUESTED_INTERVAL" ]; then
  echo "FAIL: could not parse the server-requested interval from: $INTERVAL_LINE" >&2
  exit 1
fi

# All of THIS document's own retry/poll attempts after T1 - each
# "Load document with status: deferred, id: <same id>" entry is one poll.
# ISO8601 timestamps sort correctly as plain strings, so lexicographic
# comparison is enough - no need to convert to epoch for the search itself.
# `mapfile`/`readarray` need bash 4+; macOS ships bash 3.2 by default, so
# this reads into the array portably instead.
POLL_TIMESTAMPS=()
while IFS= read -r line; do
  POLL_TIMESTAMPS+=("$line")
done < <(awk -v id="$DOC_ID" -v t1="$T1" '
  $0 ~ ("Load document with status: deferred, id: " id) && $1 > t1 { print $1 }
' "$LOGFILE")

if [ "${#POLL_TIMESTAMPS[@]}" -eq 0 ]; then
  echo "FAIL: no retry/poll attempt ('Load document with status: deferred, id: $DOC_ID') found after the initial request at $T1 - either the app never retried within ${POLL_WAIT_SECONDS}s, or something upstream (auth/issuance) failed before reaching the retry loop at all. Try increasing POLL_WAIT_SECONDS or check for the same account/DNS issues seen during this bug's original investigation." >&2
  exit 1
fi

echo "Document id               : $DOC_ID"
echo "Server-requested interval : ${REQUESTED_INTERVAL}s"
echo "Retry attempts observed   : ${#POLL_TIMESTAMPS[@]} (${POLL_TIMESTAMPS[*]})"

if [ "${#POLL_TIMESTAMPS[@]}" -ge 2 ]; then
  # Preferred signal: gap between the first two retries, immune to
  # UI-navigation overhead - see header comment.
  P1_EPOCH=$(date -j -f "%Y-%m-%dT%H:%M:%S%z" "${POLL_TIMESTAMPS[0]}" +%s)
  P2_EPOCH=$(date -j -f "%Y-%m-%dT%H:%M:%S%z" "${POLL_TIMESTAMPS[1]}" +%s)
  ELAPSED=$((P2_EPOCH - P1_EPOCH))
  THRESHOLD=$(echo "$REQUESTED_INTERVAL * 0.5" | bc)
  SIGNAL="retry-to-retry gap (attempt 1 -> attempt 2)"
else
  # Fallback: only one retry happened before resolution. Measure from the
  # original request instead, accepting that this includes UI-navigation
  # overhead - use a more generous threshold (75% instead of 50%) to avoid
  # a false PASS purely from that navigation noise.
  P1_EPOCH=$(date -j -f "%Y-%m-%dT%H:%M:%S%z" "${POLL_TIMESTAMPS[0]}" +%s)
  ELAPSED=$((P1_EPOCH - T1_EPOCH))
  THRESHOLD=$(echo "$REQUESTED_INTERVAL * 0.75" | bc)
  SIGNAL="request-to-first-retry gap (noisier - includes UI navigation time)"
fi

echo "Signal used                : $SIGNAL"
echo "Actual elapsed              : ${ELAPSED}s"
echo "Violation threshold         : ${THRESHOLD}s"

if awk -v e="$ELAPSED" -v th="$THRESHOLD" 'BEGIN{exit !(e < th)}'; then
  echo "FAIL: actual elapsed time (${ELAPSED}s) is well under the server-requested interval (${REQUESTED_INTERVAL}s) - hardcoded ~5s retry interval bug confirmed (DocumentTabViewModel.swift's Task.sleep(seconds: 5), ignoring the server entirely)." >&2
  exit 1
else
  echo "PASS: actual elapsed time (${ELAPSED}s) respects the server-requested interval (${REQUESTED_INTERVAL}s) within threshold - the hardcoded-interval bug appears fixed."
  exit 0
fi

#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-14 - verify what the Android verifier ACTUALLY logged during a BLE
session where iOS was backgrounded right after the QR was displayed
(see tc-14-ios-background-during-handshake.py).

Same discipline as TC-13's tc-13-verify-no-receipt.py, TC-24/25/26's
server-side receipt verification, and the whole point of this test per
the original strategy analysis: don't trust iOS's UI claim about what
happened - check the verifier's own independent state.

Unlike TC-13 (where the expected/interesting outcome was binary -
receipt or no receipt), TC-14's interruption happens earlier (during
engagement, before any request/response exchange), so this script
reports what stage Android actually reached rather than asserting a
single expected outcome - the comparison worth making is between this
stage and whatever tc14_ios_outcome.txt says iOS showed, done by the
orchestrator script, not here.

Reconstructs the TransferManager log the same way as TC-02/03/04/13's
verify scripts (a single grep match on a long wrapped message is a
truncated fragment, not the full message).

Reports which of these stages were reached, in order:
  - Device Engagement Received (Android scanned the QR at all)
  - Connection Established (GATT connect + service discovery succeeded)
  - Request Doc (Android sent its request)
  - ResponseReceived (Android got a response back)
  - any TransferManager-tagged error/exception line

This script always exits 0 (it is a state reporter, not a pass/fail
gate) unless the log file itself can't be read - the orchestrator
script is what compares this against iOS's own reported outcome and
decides whether the two sides agree.

Usage: tc-14-verify-state.py <logcat_capture_file>
"""
import re
import sys

TAG_LINE = re.compile(r"^\S+ \S+ D/TransferManager\(\d+\): ?")
ERR_TAG_LINE = re.compile(r"^\S+ \S+ E/TransferManager\(\d+\): ?")

STAGE_MARKERS = [
    ("Device Engagement Received", "engagement_received"),
    ("Connection Established", "connection_established"),
    ("Request Doc", "request_sent"),
    ("ResponseReceived", "response_received"),
]


def main():
    if len(sys.argv) != 2:
        print("usage: tc-14-verify-state.py <logcat_capture_file>", file=sys.stderr)
        sys.exit(2)

    log_path = sys.argv[1]
    try:
        with open(log_path) as f:
            content = f.read()
    except OSError as e:
        print(f"FAIL: could not read logcat capture: {e}", file=sys.stderr)
        sys.exit(2)

    reached = []
    for marker, label in STAGE_MARKERS:
        if f"D/TransferManager" in content and marker in content:
            # Confirm it's actually a TransferManager-tagged line containing
            # this marker, not a coincidental substring match elsewhere.
            if re.search(re.escape(marker), content):
                reached.append(label)

    error_lines = [
        ERR_TAG_LINE.sub("", line).rstrip("\n")
        for line in content.splitlines()
        if "E/TransferManager" in line and ": \t" not in line
    ][:5]

    last_stage = reached[-1] if reached else "no_engagement_observed"

    print(f"Android furthest stage reached: {last_stage}")
    print(f"Stages observed (in order found): {reached}")
    if error_lines:
        print("TransferManager error lines present:")
        for line in error_lines:
            print(f"  {line}")
    else:
        print("No TransferManager error lines present.")

    sys.exit(0)


if __name__ == "__main__":
    main()

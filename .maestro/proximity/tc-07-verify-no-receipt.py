#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-07 - verify what the Android verifier ACTUALLY received (or, more
precisely, did NOT receive) when iOS deselected every requested field
and Share correctly stayed disabled, so no response was ever sent (see
tc-07-ios-zero-selection.py).

Same discipline as TC-13/14's independent Android-side verification -
the ground truth this test exists to check iOS's own enabled/disabled
claim against, not just iOS's own reported state. Logic is identical to
tc-13-verify-no-receipt.py (a different trigger - Share never tapped at
all here, vs. Bluetooth disabled after tapping Share in TC-13 - but the
same expected Android-side signature: request sent, response never
received), kept as its own file per this project's existing convention
of self-contained per-TC scripts.

Reconstructs the TransferManager "Request Doc" and "ResponseReceived"
log messages the same way as TC-02/03/04/13/14's verify scripts (a
single grep match on a long wrapped message is a truncated fragment,
not the full message).

Asserts:
  - the outgoing "Request Doc" WAS sent (confirms the BLE session did
    reach an established, request-sent state - otherwise this run
    proves nothing about the zero-selection scenario at all, just an
    engagement failure)
  - NO "ResponseReceived" message ever appears (confirms the verifier
    genuinely received zero bytes - proving the app didn't send a
    malformed empty response, and didn't silently bypass its own
    disabled-Share gating)

This script's PASS/FAIL is about whether Android's independent state is
consistent with "nothing was ever sent," not about whether the app's
own UI behaved correctly - that judgment compares this result against
tc07_ios_outcome.txt (see tc-07-ble-zero-selection.sh), and is reported
separately.

Usage: tc-07-verify-no-receipt.py <logcat_capture_file>
"""
import re
import sys

TAG_LINE = re.compile(r"^\S+ \S+ D/TransferManager\(\d+\): ?")


def _reconstruct(log_path, marker):
    capturing = False
    parts = []
    with open(log_path) as f:
        for line in f:
            if "D/TransferManager" in line and marker in line:
                capturing = True
            if capturing:
                if "D/TransferManager" not in line:
                    break
                parts.append(TAG_LINE.sub("", line).rstrip("\n"))
    if not parts:
        return None
    full = "".join(parts)
    idx = full.find(marker + " ")
    if idx == -1:
        return None
    return full[idx + len(marker + " ") :]


def main():
    if len(sys.argv) != 2:
        print("usage: tc-07-verify-no-receipt.py <logcat_capture_file>", file=sys.stderr)
        sys.exit(2)

    log_path = sys.argv[1]

    request_content = _reconstruct(log_path, "Request Doc")
    if request_content is None:
        print(
            "FAIL: no 'Request Doc' message found - the BLE session never even reached "
            "request-sent state, so this run does not test the zero-selection scenario "
            "(likely an engagement/rig issue, not app behavior)",
            file=sys.stderr,
        )
        sys.exit(1)
    print("Confirmed: Android sent the request (BLE session was genuinely established).")

    response_content = _reconstruct(log_path, "ResponseReceived")
    if response_content is not None:
        print(
            "FAIL: a 'ResponseReceived' message WAS found - something was sent despite "
            "zero fields being selected. Either Share was not actually disabled, or a "
            "response was sent through some other path. This is exactly the malformed/"
            "empty-response scenario this test exists to rule out.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(
        "PASS: request was sent, and Android's verifier genuinely received ZERO response "
        "bytes (no 'ResponseReceived' anywhere in the capture) - consistent with Share "
        "correctly staying disabled and never being sendable."
    )
    sys.exit(0)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-13 - verify what the Android verifier ACTUALLY received (or, more
precisely, did NOT receive) during a BLE session whose response
transfer was interrupted by disabling the verifier's own Bluetooth
right after iOS tapped Share (see tc-13-ios-interrupt-share.py).

Same discipline as TC-24/25/26's server-side receipt verification
(`tc-24-verify-no-receipt.js` etc.) applied to the BLE verifier's own
logcat instead of a remote server response - this is the ground truth
this test exists to check iOS's UI claim against, independent of
whatever iOS's screen says.

Reconstructs the TransferManager "Request Doc" and "ResponseReceived"
log messages the same way as TC-02/03/04's verify scripts (a single
grep match on a long wrapped message is a truncated fragment, not the
full message).

Asserts:
  - the outgoing "Request Doc" WAS sent (confirms the BLE session did
    reach an established, request-sent state before the interruption -
    otherwise this test proves nothing about a "mid-transfer"
    interruption at all, just an engagement failure)
  - NO "ResponseReceived" message ever appears (confirms the verifier
    genuinely received zero response bytes - the ground truth this
    script exists to establish)

This script's PASS/FAIL is about whether the interruption was real and
verifiable, not about whether the app behaved correctly - that
judgment compares this result against tc13_ios_outcome.txt (see
tc-13-ble-mid-transfer-disconnect.sh), and is reported separately.

Usage: tc-13-verify-no-receipt.py <logcat_capture_file>
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
        print("usage: tc-13-verify-no-receipt.py <logcat_capture_file>", file=sys.stderr)
        sys.exit(2)

    log_path = sys.argv[1]

    request_content = _reconstruct(log_path, "Request Doc")
    if request_content is None:
        print(
            "FAIL: no 'Request Doc' message found - the BLE session never even reached "
            "request-sent state, so this run does not test a mid-transfer interruption "
            "(likely an engagement/rig issue, not app behavior)",
            file=sys.stderr,
        )
        sys.exit(1)
    print("Confirmed: Android sent the request (BLE session was genuinely established).")

    response_content = _reconstruct(log_path, "ResponseReceived")
    if response_content is not None:
        print(
            "FAIL: a 'ResponseReceived' message WAS found - the interruption did not "
            "actually prevent the transfer (Bluetooth may have been disabled too late, "
            "or re-enabled too early). This run does not demonstrate a genuine "
            "mid-transfer failure - re-run and check timing.",
            file=sys.stderr,
        )
        sys.exit(1)

    with open(log_path) as f:
        content = f.read()
    disconnect_evidence = "onConnectionStateChange" in content or "GATT_CLOSE" in content or "STATE_OFF" in content

    print(
        "PASS: request was sent, and Android's verifier genuinely received ZERO response "
        f"bytes (no 'ResponseReceived' anywhere in the capture). Disconnect/radio-off "
        f"evidence also present in logcat: {disconnect_evidence}."
    )
    sys.exit(0)


if __name__ == "__main__":
    main()

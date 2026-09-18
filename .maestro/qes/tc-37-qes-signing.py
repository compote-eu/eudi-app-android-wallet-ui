#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-37/38/39 — QES / remote-qualified-signing flow (physical device only).

Drives: Home -> Sign document -> select a PDF from Files -> Wallet-Centric
signing service -> Service authorization (OpenID4VP PID share #1) ->
Select signing certificate -> Credential authorization (OpenID4VP PID
share #2) -> actual signing call.

See README.md in this directory for the full writeup of why this needs a
physical device (Appium/XCUITest, not Maestro/simulator), how the one-time
PDF fixture is seeded via iCloud Drive, and why every "OAuth" step in this
flow is actually a same-device OpenID4VP round trip through Safari and
straight back into this app, never real external web content.

Deliberately does NOT assert the final signing call succeeds: that step
depends on the live dev QTSP backend (walletcentric.signer.dev.eudiw.dev),
which has been observed to fail with a graceful, non-crashing "RQES Signing
service is unavailable" error - confirmed persistent across attempts spaced
minutes apart, not a one-off blip (see README.md). This mirrors how
tc-01/tc-50 accept dev-issuer flakiness as a known, external category of
failure rather than building special retry logic for it. Everything up to
and including the certificate-selection "Proceed" tap - the entire part of
the flow this app's own code is responsible for - IS asserted.

Requires env var EUDI_WALLET_PIN. Requires the PDF fixture already synced
to the device's Files app via iCloud Drive (see README.md's "Fixture
seeding" section) - this script does not create or seed it.

Usage: tc-37-qes-signing.py <udid> <apple-team-id> <bundle-id> <fixture-basename>
  fixture-basename: the PDF's filename without extension, e.g.
  "tc37-qes-test2" for a fixture at .../com~apple~CloudDocs/tc37-qes-test2.pdf
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

APPIUM_URL = "http://127.0.0.1:4723"
REQUEST_TIMEOUT = 30


def _request(method, path, body=None):
    url = f"{APPIUM_URL}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        url, data=data, method=method, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read())


def new_session(udid, team_id, bundle_id):
    caps = {
        "capabilities": {
            "alwaysMatch": {
                "platformName": "iOS",
                "appium:automationName": "XCUITest",
                "appium:udid": udid,
                "appium:xcodeOrgId": team_id,
                "appium:xcodeSigningId": "iPhone Developer",
                "appium:usePrebuiltWDA": True,
                "appium:bundleId": bundle_id,
                # Long default: several steps in this flow (PDF hashing,
                # the credential-list fetch, the final signing call) take
                # multiple seconds of real network/CPU time, not just UI
                # transition time - a short timeout has torn down live
                # sessions mid-step during investigation of this exact flow.
                "appium:newCommandTimeout": 3600,
            }
        }
    }
    resp = _request("POST", "/session", caps)
    if "value" not in resp or "sessionId" not in resp.get("value", {}):
        raise RuntimeError(f"session creation failed: {resp}")
    return resp["value"]["sessionId"]


def find(session_id, using, value):
    resp = _request(
        "POST", f"/session/{session_id}/element", {"using": using, "value": value}
    )
    val = resp.get("value", {})
    return val.get("ELEMENT") if isinstance(val, dict) else None


def click(session_id, element_id):
    _request("POST", f"/session/{session_id}/element/{element_id}/click")


def set_value(session_id, element_id, text):
    _request(
        "POST",
        f"/session/{session_id}/element/{element_id}/value",
        {"text": text},
    )


def screenshot(session_id, out_path):
    resp = _request("GET", f"/session/{session_id}/screenshot")
    with open(out_path, "wb") as f:
        f.write(base64.b64decode(resp["value"]))


def wait_for(session_id, using, value, timeout=25, interval=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        elem = find(session_id, using, value)
        if elem:
            return elem
        time.sleep(interval)
    raise TimeoutError(f"Timed out waiting for {using}={value!r}")


def tap_label(session_id, label, exact=True, timeout=25):
    predicate = (
        f'label == "{label}"' if exact else f'label CONTAINS "{label}"'
    )
    elem = wait_for(session_id, "-ios predicate string", predicate, timeout=timeout)
    click(session_id, elem)


def enter_pin(session_id, pin):
    for digit in pin:
        elem = wait_for(session_id, "accessibility id", digit, timeout=15)
        click(session_id, elem)


def wait_for_launch(session_id, pin, timeout=20, interval=1):
    """Same splash-screen race documented in tc-02-ios-share.py's
    wait_for_launch - a single point-in-time check right after session
    creation can land during the ~3s launch splash and see neither the PIN
    field nor the home screen.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find(session_id, "accessibility id", "pin_text_field_0"):
            enter_pin(session_id, pin)
            return
        if find(session_id, "accessibility id", "home_tab_screen_authorization_button"):
            return
        time.sleep(interval)
    raise TimeoutError("Timed out waiting for the app to finish launching")


def handle_wallet_bounce(session_id, timeout=20):
    """Every 'OAuth' leg in this flow (twice per round trip: once handing
    off to what LOOKS like an external URL, once handing back) is actually
    Safari intercepting a custom-scheme URL and immediately asking to hand
    it straight back to this same app - never real external web content.
    See README.md's 'wallet-centric OpenID4VP loop' section. This taps
    through that system alert's 'Open' button.
    """
    tap_label(session_id, "Open", exact=True, timeout=timeout)


def share_pid_and_confirm(session_id, pin, rec):
    """One full 'Data sharing request' round: Share -> Confirm request PIN
    -> Done. Used identically for both the Service-authorization and
    Credential-authorization OpenID4VP round trips - they render the same
    native screens.
    """
    wait_for(session_id, "-ios predicate string", 'label == "Data sharing request"', timeout=20)
    rec("Data sharing request screen confirmed")
    tap_label(session_id, "Share", timeout=15)
    wait_for(session_id, "accessibility id", "pin_text_field_0", timeout=15)
    enter_pin(session_id, pin)
    rec("Confirm-request PIN entered")
    tap_label(session_id, "Done", timeout=20)


def main():
    if len(sys.argv) != 5:
        print(
            "usage: tc-37-qes-signing.py <udid> <apple-team-id> <bundle-id> <fixture-basename>",
            file=sys.stderr,
        )
        sys.exit(2)
    udid, team_id, bundle_id, fixture_basename = sys.argv[1:5]

    pin = os.environ.get("EUDI_WALLET_PIN")
    if not pin:
        print("FAIL: EUDI_WALLET_PIN env var not set", file=sys.stderr)
        sys.exit(2)

    out_dir = os.environ.get("TC37_OUTPUT_DIR", ".")

    t_start = time.time()

    def rec(msg):
        print(f"[t={time.time() - t_start:6.1f}s] {msg}")

    session_id = new_session(udid, team_id, bundle_id)
    rec(f"session created: {session_id}")

    try:
        wait_for_launch(session_id, pin)
        rec("app launched / authenticated")

        # --- Sign document -> select the pre-seeded PDF fixture ---
        tap_label(session_id, "Sign document")
        tap_label(session_id, "Select document")
        search_elem = wait_for(session_id, "accessibility id", "Search", timeout=15)
        click(session_id, search_elem)
        set_value(session_id, search_elem, fixture_basename)
        tap_label(session_id, fixture_basename, exact=False, timeout=15)
        wait_for(
            session_id,
            "-ios predicate string",
            f'label CONTAINS "{fixture_basename}.pdf"',
            timeout=15,
        )
        rec(f"document selected: {fixture_basename}.pdf")

        # --- Select signing service: only "Wallet-Centric" is configured ---
        tap_label(session_id, "Select signing service", exact=False)
        tap_label(session_id, "Wallet-Centric")
        tap_label(session_id, "Proceed")
        rec("signing service selected, proceeding to service authorization")

        # --- Service authorization: OpenID4VP round trip #1 ---
        handle_wallet_bounce(session_id)
        share_pid_and_confirm(session_id, pin, rec)
        handle_wallet_bounce(session_id)
        rec("service authorization complete")

        # --- Select signing certificate (only one dev-QTSP test credential exists) ---
        tap_label(session_id, "Select signing certificate", exact=False)
        # The credential list is fetched live from the QTSP after tapping
        # in - confirmed to take several seconds, not an instant render.
        cert_elem = wait_for(
            session_id,
            "-ios predicate string",
            'label CONTAINS "credential for tests"',
            timeout=20,
        )
        click(session_id, cert_elem)
        tap_label(session_id, "Proceed")
        rec("certificate selected, proceeding to credential authorization")
        # --- This is the exact point where an uncaught PoDoFo C++
        # exception (document-hash computation) crashed the whole process
        # when the selected PDF was malformed - see README.md's dedicated
        # crash finding. A well-formed PDF passes this point cleanly. ---

        # --- Credential authorization: OpenID4VP round trip #2 ---
        handle_wallet_bounce(session_id, timeout=30)
        share_pid_and_confirm(session_id, pin, rec)
        handle_wallet_bounce(session_id, timeout=30)
        rec("credential authorization complete - everything this app's own "
            "code is responsible for has now run without error")

        # --- Final signing call: NOT asserted - depends on live QTSP backend ---
        time.sleep(3)
        shot_path = os.path.join(out_dir, "tc37_final_state.png")
        screenshot(session_id, shot_path)
        src = _request("GET", f"/session/{session_id}/source").get("value", "")

        if "RQES Signing service is unavailable" in src:
            rec(
                "KNOWN LIMITATION reproduced: dev QTSP backend rejected the "
                "final signing call ('RQES Signing service is unavailable'). "
                "Everything this app is responsible for passed. Not a "
                "script failure - see README.md."
            )
            rec(f"screenshot saved: {shot_path}")
            sys.exit(0)

        if "Data shared" in src or "successfully" in src.lower():
            rec(
                "UNEXPECTED SUCCESS: the final signing call appears to have "
                "gone through this time (dev QTSP backend was up). This is "
                "a good outcome, not a failure - re-check README.md, this "
                "may mean the known backend limitation has cleared."
            )
            rec(f"screenshot saved: {shot_path}")
            sys.exit(0)

        rec(
            "UNEXPECTED STATE at the final step - neither the known "
            "'service unavailable' error nor a recognizable success screen. "
            "This is worth investigating, not silently accepting."
        )
        rec(f"screenshot saved: {shot_path}")
        with open(os.path.join(out_dir, "tc37_final_state_source.xml"), "w") as f:
            f.write(src)
        sys.exit(1)
    finally:
        _request("DELETE", f"/session/{session_id}")
        rec("session deleted")


if __name__ == "__main__":
    main()

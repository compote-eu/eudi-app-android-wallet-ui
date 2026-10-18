#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-02 - iOS half: trigger BLE proximity share (Home -> Authenticate ->
In person), confirm the resulting consent screen, tap Share, confirm the
follow-up PIN screen, and close out the success screen.

Talks to a locally-running Appium server (default http://127.0.0.1:4723)
directly over its REST API using only the standard library - no
appium-python-client dependency, matching how this was verified live
during TC-02's investigation.

Requires env var EUDI_WALLET_PIN (never hardcode a real device PIN into a
committed script, even for a dev/test device).

Usage: tc-02-ios-share.py <physical-iphone-udid> <apple-team-id> <bundle-id>
"""
import json
import os
import sys
import time
import urllib.request

APPIUM_URL = "http://127.0.0.1:4723"


def _request(method, path, body=None):
    url = f"{APPIUM_URL}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        url, data=data, method=method, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        # Appium's W3C protocol returns a non-2xx HTTP status (404 for
        # "no such element") with a JSON error body, not a 200 - confirmed
        # live, urlopen raises before the body is otherwise reachable.
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
                # A short default here has repeatedly torn down the session
                # (and, once, backgrounded the app) during any real-world
                # pause - e.g. a human confirming camera alignment - between
                # actions. Long enough that a slow human step never trips it.
                "appium:newCommandTimeout": 3600,
            }
        }
    }
    resp = _request("POST", "/session", caps)
    return resp["value"]["sessionId"]


def find(session_id, using, value):
    resp = _request(
        "POST", f"/session/{session_id}/element", {"using": using, "value": value}
    )
    val = resp.get("value", {})
    return val.get("ELEMENT") if isinstance(val, dict) else None


def click(session_id, element_id):
    _request("POST", f"/session/{session_id}/element/{element_id}/click")


def wait_for(session_id, using, value, timeout=90, interval=4, pin=None):
    """Poll for an element. If `pin` is given, also watch for the app
    having relocked mid-wait and re-authenticate automatically.

    Root-caused live (this is NOT the concurrent-Maestro or memory-pressure
    theory floated earlier - both were ruled out by reproducing this with
    Maestro not even running): app launch shows a splash screen for ~3s
    before the PIN screen renders. A single find() taken immediately after
    session creation lands during that splash window and finds neither
    the PIN field nor the target element, which is fine on its own - but
    if that gap is what a caller uses to decide "must already be past
    login," it wrongly skips the real, one-time initial PIN entry, and
    THEN this loop's own relock-detection finds the PIN field on its next
    poll and treats a legitimate first login as a "relock." That alone
    is harmless (it enters the PIN either way) - the actual corruption
    came from immediately re-polling in the same iteration afterward,
    with zero settle time for the post-PIN-entry navigation transition,
    which could catch the PIN screen's own fade-out and misfire a second,
    garbled entry. Fixed by giving the transition a beat before re-polling
    (see the sleep right after enter_pin below) - confirmed this stops
    the loop, doesn't just relocate it.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        elem = find(session_id, using, value)
        if elem:
            return elem
        if pin is not None:
            pin_field = find(session_id, "accessibility id", "pin_text_field_0")
            if pin_field:
                print("PIN screen present - authenticating...")
                enter_pin(session_id, pin)
                time.sleep(interval)  # let the post-PIN navigation settle
                continue
        time.sleep(interval)
    raise TimeoutError(f"Timed out waiting for {using}={value!r}")


def enter_pin(session_id, pin):
    for digit in pin:
        elem = wait_for(session_id, "accessibility id", digit, timeout=20, interval=2)
        click(session_id, elem)


def wait_for_launch(session_id, pin, timeout=20, interval=1):
    """Wait out the app's launch splash screen, then authenticate if the
    PIN screen is what's actually there. A single point-in-time find()
    right after session creation is what raced the splash screen in the
    first place (see wait_for's docstring) - this polls instead of
    checking once, specifically for the two valid post-splash outcomes.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find(session_id, "accessibility id", "pin_text_field_0"):
            enter_pin(session_id, pin)
            # Same settle-time protection wait_for's own relock-recovery
            # path already has after its enter_pin call (see that
            # docstring) - missing here until confirmed live (2026-08-25,
            # this same device, running concurrently with the Android
            # Maestro flow): returning immediately let the very next
            # wait_for(..., pin=pin) call for home_tab_screen_authorization_
            # button poll while this PIN screen was still mid-transition,
            # misread that as a relock, and fired a second, colliding PIN
            # entry into a not-yet-settled view - producing exactly the
            # "Timed out waiting for accessibility id='0'" failure this
            # was meant to prevent in the first place.
            time.sleep(2)
            return
        if find(session_id, "accessibility id", "home_tab_screen_authorization_button"):
            return
        time.sleep(interval)
    raise TimeoutError("Timed out waiting for the app to finish launching")


def main():
    if len(sys.argv) != 4:
        print(
            "usage: tc-02-ios-share.py <udid> <apple-team-id> <bundle-id>",
            file=sys.stderr,
        )
        sys.exit(2)
    udid, team_id, bundle_id = sys.argv[1:4]

    pin = os.environ.get("EUDI_WALLET_PIN")
    if not pin:
        print("FAIL: EUDI_WALLET_PIN env var not set", file=sys.stderr)
        sys.exit(2)

    session_id = new_session(udid, team_id, bundle_id)
    try:
        # Waits out the ~3s launch splash screen before checking whether a
        # PIN is needed - checking once, immediately, races the splash
        # screen (see wait_for's docstring for the corruption that caused).
        wait_for_launch(session_id, pin)

        auth_button = wait_for(
            session_id,
            "accessibility id",
            "home_tab_screen_authorization_button",
            pin=pin,
        )
        click(session_id, auth_button)

        in_person = wait_for(
            session_id,
            "accessibility id",
            "home_tab_screen_dialog_in_person_button",
            timeout=10,
        )
        click(session_id, in_person)

        # Not passing pin= here deliberately: a relock while WAITING for
        # the share/consent screen can't be recovered with a plain PIN
        # re-entry alone - that only lands back on Home, not back on this
        # in-progress proximity screen. Re-triggering the whole
        # Authenticate -> In person navigation would be needed too, which
        # isn't implemented here since this specific wait has not been
        # observed to relock in practice (only the very first, immediately
        # post-launch wait for auth_button has) - see wait_for's docstring.
        print(
            "QR displayed - waiting for consent screen "
            "(engagement + BLE connect happen passively on the Android side)..."
        )
        share_button = wait_for(
            session_id, "accessibility id", "request_screen_share_button", timeout=90
        )
        click(session_id, share_button)

        # Share is followed by a second, separate "Confirm request" PIN
        # screen - confirmed live, not assumed.
        wait_for(session_id, "accessibility id", "pin_text_field_0", timeout=15)
        enter_pin(session_id, pin)

        # Accessibility-id lookup for this button failed live ("Done" is
        # not its id) - a predicate match on the visible label is what
        # actually worked.
        done_button = wait_for(
            session_id,
            "-ios predicate string",
            'label == "Done" AND type == "XCUIElementTypeButton"',
            timeout=30,
        )
        click(session_id, done_button)
        print("PASS: iOS side completed - Share confirmed, PIN entered, Done tapped")
    finally:
        _request("DELETE", f"/session/{session_id}")


if __name__ == "__main__":
    main()

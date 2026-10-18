#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""Precondition check for tc-37-qes-signing.py: confirm the iCloud
Drive-seeded PDF fixture is actually visible in the device's file
picker, and fail clearly and immediately if it isn't - rather than
letting tc-37-qes-signing.py fail confusingly deep inside its own
document-selection step with a generic TimeoutError.

This is a real, load-bearing precondition, not just a nice-to-have: the
fixture is seeded manually, once, via iCloud Drive sync (see README.md's
"Fixture seeding" section) - there is no CI-automatable way to recreate
it, so a missing fixture is expected to happen occasionally (e.g. a
fresh runner, a wiped device, or iCloud Drive sync state changing) and
should be reported as exactly that, not as a mysterious flow failure.

Does not run the signing flow itself - opens the picker, searches, and
reports found/not-found, then backs out to Home cleanly either way.

Requires env var EUDI_WALLET_PIN.

Usage: tc-37-check-fixture.py <udid> <apple-team-id> <bundle-id> <fixture-basename>
"""
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
                "appium:newCommandTimeout": 600,
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


def clear(session_id, element_id):
    _request("POST", f"/session/{session_id}/element/{element_id}/clear")


def set_value(session_id, element_id, text):
    # WDA's /value APPENDS to whatever the field already contains rather
    # than replacing it - confirmed live (typing a second search term
    # after a first left both concatenated together) - so this must
    # clear first, unlike a fresh, guaranteed-empty field.
    clear(session_id, element_id)
    _request(
        "POST",
        f"/session/{session_id}/element/{element_id}/value",
        {"text": text},
    )


def wait_for(session_id, using, value, timeout=20, interval=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        elem = find(session_id, using, value)
        if elem:
            return elem
        time.sleep(interval)
    return None


def tap_label(session_id, label, timeout=20):
    elem = wait_for(
        session_id, "-ios predicate string", f'label == "{label}"', timeout=timeout
    )
    if elem:
        click(session_id, elem)
    return elem


def enter_pin(session_id, pin):
    for digit in pin:
        elem = wait_for(session_id, "accessibility id", digit, timeout=15)
        if elem:
            click(session_id, elem)


def wait_for_launch(session_id, pin, timeout=20, interval=1):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find(session_id, "accessibility id", "pin_text_field_0"):
            enter_pin(session_id, pin)
            return
        if find(session_id, "accessibility id", "home_tab_screen_authorization_button"):
            return
        time.sleep(interval)
    raise TimeoutError("Timed out waiting for the app to finish launching")


def main():
    if len(sys.argv) != 5:
        print(
            "usage: tc-37-check-fixture.py <udid> <apple-team-id> <bundle-id> <fixture-basename>",
            file=sys.stderr,
        )
        sys.exit(2)
    udid, team_id, bundle_id, fixture_basename = sys.argv[1:5]

    pin = os.environ.get("EUDI_WALLET_PIN")
    if not pin:
        print("FAIL: EUDI_WALLET_PIN env var not set", file=sys.stderr)
        sys.exit(2)

    session_id = new_session(udid, team_id, bundle_id)
    try:
        wait_for_launch(session_id, pin)

        if not tap_label(session_id, "Sign document"):
            print("FIXTURE CHECK INCONCLUSIVE: 'Sign document' tile not found on "
                  "Home - can't reach the file picker at all to check.")
            sys.exit(1)
        if not tap_label(session_id, "Select document"):
            print("FIXTURE CHECK INCONCLUSIVE: 'Select document' not found on the "
                  "Sign document screen.")
            sys.exit(1)

        search_elem = wait_for(session_id, "accessibility id", "Search", timeout=15)
        if not search_elem:
            print("FIXTURE CHECK INCONCLUSIVE: file picker's Search field never "
                  "appeared.")
            sys.exit(1)
        click(session_id, search_elem)
        set_value(session_id, search_elem, fixture_basename)

        # type == "XCUIElementTypeCell" is load-bearing, not decorative:
        # the picker's own "Name Contains "<term>"" instruction row is a
        # separate XCUIElementTypeStaticText whose label literally embeds
        # the search term as a substring - confirmed live that a plain
        # `label CONTAINS "<anything>"` predicate (no type restriction)
        # matches THAT row even for a deliberately bogus, guaranteed-absent
        # fixture name, i.e. it's a false positive independent of whether
        # the file actually exists. Real result rows are a distinct
        # XCUIElementTypeCell, confirmed via a live source dump (label
        # "tc37-qes-test2, pdf, Yesterday, 14 KB" - note the ", pdf", not
        # ".pdf", which is also why matching the bare basename rather than
        # "<name>.pdf" is correct here).
        found = wait_for(
            session_id,
            "-ios predicate string",
            f'type == "XCUIElementTypeCell" AND label CONTAINS "{fixture_basename}"',
            timeout=15,
        )

        if found:
            print(f"FIXTURE PRESENT: {fixture_basename}.pdf found in the file "
                  f"picker via Search.")
            result = 0
        else:
            print(
                f"FIXTURE MISSING: {fixture_basename}.pdf was not found in the "
                f"file picker's search results.\n"
                f"This fixture is seeded manually, once, via iCloud Drive sync "
                f"(see .maestro/qes/README.md's 'Fixture seeding' section) - "
                f"it is NOT something this workflow can recreate automatically. "
                f"Re-seed it on the Mac this runner uses and re-run."
            )
            result = 1

        # Back out to Home cleanly either way, so a re-run (or the real
        # flow, if this check is chained before it) starts from a known
        # state rather than mid-picker.
        close_elem = find(session_id, "accessibility id", "Search")
        if close_elem:
            # Dismiss the picker via its own Cancel/X control if present;
            # falling back to the Sign document screen's X otherwise.
            cancel_elem = find(session_id, "-ios predicate string", 'label == "Cancel"')
            if cancel_elem:
                click(session_id, cancel_elem)
        x_elem = find(session_id, "-ios predicate string", 'label == "X"') or \
            find(session_id, "-ios predicate string",
                 'type == "XCUIElementTypeButton" AND name == "Close"')
        if x_elem:
            click(session_id, x_elem)

        sys.exit(result)
    finally:
        _request("DELETE", f"/session/{session_id}")


if __name__ == "__main__":
    main()

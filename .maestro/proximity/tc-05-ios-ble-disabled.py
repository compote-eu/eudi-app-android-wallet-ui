#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-05 - BLE disabled on iOS before attempting to share.

Unlike TC-02/03/04/13/14, this test needs NO Android/Maestro
orchestration at all - the interruption happens before any BLE session
is ever attempted, so there is nothing on the Android side to drive or
verify. This is a pure iOS-side test.

Code-grounded expected behavior (confirmed by reading the source before
writing this script, not assumed):
  - `HomeTabViewModel.onShare()` (Modules/feature-dashboard/Sources/UI/
    Dashboard/Tab/Home/HomeTabViewModel.swift:101-118) calls
    `interactor.getBleAvailability()` BEFORE navigating anywhere. On
    `.noPermission` or `.disabled` it calls `toggleBleModal()` instead
    of pushing the proximity/QR route.
  - `getBleAvailability()` (HomeTabInteractor.swift ->
    ReachabilityController.swift) wraps a `BluetoothKit` `BKCentral`
    observer over `CBCentralManager` state; `.poweredOff` maps to
    `.disabled` (ReachabilityController.swift's `mapAvailability`).
    Turning Bluetooth off via iOS Settings (not Control Center - see
    below) is what actually produces `CBCentralManager.state ==
    .poweredOff`.
  - The resulting modal (`HomeTabView.swift`, `.dialogCompat(...)`) is a
    genuine native `.alert` on this device's iOS version (26.6, >= the
    `#available(iOS 26, *)` branch in DialogCompat.swift) - a real
    UIAlertController, fully queryable via standard predicate matching
    (no accessibility-merging concerns like the proximity consent
    screen's checkboxes had). Expected text (from
    Localizable.xcstrings, confirmed before writing this script):
      title:   "Enable bluetooth?"
      message: "Enable bluetooth to share your information using the
                QR/TAP option"
      buttons: "Enable" (-> onBleSettings(), opens iOS Settings) and
               "Cancel" (dismisses only)

Why Settings, not Control Center, to toggle Bluetooth off: confirmed
live that Settings.app's own Bluetooth toggle switch is reachable and
tappable via Appium (a completely ordinary system-app UI, automatable
like any other app). Control Center's quick-toggle is deliberately NOT
used here even though it's arguably more "real user" - since iOS 11,
that toggle only disconnects currently-connected accessories and hides
the device from other accessories, it does NOT actually power off the
Bluetooth radio or change `CBCentralManager.state` to `.poweredOff` -
using it would not exercise the code path this test is targeting at
all. A full Settings > Bluetooth toggle-off is the only way to
genuinely reach `.disabled` here.

This script runs as FOUR separate, sequential Appium sessions (each
bound to a different bundle id / app state), rather than one long
session juggling cross-app switches, since each phase is independent
and this keeps failure modes isolated:
  1. Settings.app: toggle Bluetooth OFF, confirm the switch reports off.
  2. Wallet app: tap Authenticate -> In person, confirm the BLE-disabled
     alert appears with the expected text, dismiss via Cancel, confirm
     the app returns to a normal Home state (not stuck/crashed).
  3. Settings.app: toggle Bluetooth back ON (cleanup/restore - runs
     even if phase 2 failed, via a top-level try/finally).
  4. Wallet app: tap Authenticate -> In person again, confirm the
     BLE-disabled alert does NOT appear this time (i.e. the app
     recovered and behaves normally once Bluetooth is back) - a
     screenshot is saved for visual confirmation of what appeared
     instead (the QR/connectivity screen, which has no distinct
     accessibility locator of its own - see TC-14's investigation of
     ProximityConnectionView).

Talks to a locally-running Appium server (default http://127.0.0.1:4723)
directly over its REST API using only the standard library - no
appium-python-client dependency, matching TC-02/03/04/13/14.

Requires env var EUDI_WALLET_PIN (never hardcode a real device PIN into
a committed script, even for a dev/test device).

Usage: tc-05-ios-ble-disabled.py <iphone-udid> <apple-team-id> <wallet-bundle-id> <out-dir>
"""
import base64
import json
import os
import sys
import time
import urllib.request

APPIUM_URL = "http://127.0.0.1:4723"
SETTINGS_BUNDLE_ID = "com.apple.Preferences"


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
                "appium:newCommandTimeout": 3600,
            }
        }
    }
    resp = _request("POST", "/session", caps)
    if "value" not in resp or "sessionId" not in resp.get("value", {}):
        raise RuntimeError(f"session creation failed for bundleId={bundle_id}: {resp}")
    return resp["value"]["sessionId"]


def delete_session(session_id):
    _request("DELETE", f"/session/{session_id}")


def find(session_id, using, value):
    resp = _request(
        "POST", f"/session/{session_id}/element", {"using": using, "value": value}
    )
    val = resp.get("value", {})
    return val.get("ELEMENT") if isinstance(val, dict) else None


def find_all(session_id, using, value):
    resp = _request(
        "POST", f"/session/{session_id}/elements", {"using": using, "value": value}
    )
    val = resp.get("value", [])
    if isinstance(val, list):
        return [v.get("ELEMENT") for v in val if isinstance(v, dict) and v.get("ELEMENT")]
    return []


def click(session_id, element_id):
    _request("POST", f"/session/{session_id}/element/{element_id}/click")


def get_attribute(session_id, element_id, name):
    resp = _request("GET", f"/session/{session_id}/element/{element_id}/attribute/{name}")
    return resp.get("value")


def wait_for(session_id, using, value, timeout=20, interval=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        elem = find(session_id, using, value)
        if elem:
            return elem
        time.sleep(interval)
    raise TimeoutError(f"Timed out waiting for {using}={value!r}")


def enter_pin(session_id, pin):
    for digit in pin:
        elem = wait_for(session_id, "accessibility id", digit, timeout=20, interval=2)
        click(session_id, elem)


def wait_for_launch(session_id, pin, timeout=20, interval=1):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find(session_id, "accessibility id", "pin_text_field_0"):
            enter_pin(session_id, pin)
            time.sleep(2)
            return
        if find(session_id, "accessibility id", "home_tab_screen_authorization_button"):
            return
        time.sleep(interval)
    raise TimeoutError("Timed out waiting for the app to finish launching")


def save_screenshot(session_id, out_dir, name):
    shot = _request("GET", f"/session/{session_id}/screenshot")
    path = os.path.join(out_dir, name)
    with open(path, "wb") as f:
        f.write(base64.b64decode(shot["value"]))
    print(f"Saved screenshot: {path}")
    return path


def set_bluetooth(udid, team_id, desired_on, out_dir, label):
    """Navigate Settings.app -> Bluetooth and set the toggle to the
    desired state. Returns the switch's reported value AFTER the change
    (as a string, "1" or "0" per WDA's XCUIElementTypeSwitch convention)."""
    session_id = new_session(udid, team_id, SETTINGS_BUNDLE_ID)
    try:
        # IMPORTANT (found the hard way, live): a bare
        # `type == "XCUIElementTypeSwitch"` predicate is NOT safe to use
        # on the Settings root list - Airplane Mode's own row is ALSO
        # exposed as an XCUIElementTypeSwitch there (name=
        # "com.apple.settings.airplaneMode"), and being first in
        # document order, it silently absorbed both the read and the
        # tap meant for Bluetooth in an earlier version of this script -
        # Bluetooth was never actually touched, and Airplane Mode was
        # nearly toggled by accident. The real Bluetooth switch, once on
        # its own detail page, has the distinct name "BLUETOOTH" (all
        # caps, confirmed via a page source dump) - always match on
        # that, never a bare type check, regardless of which page this
        # session happens to land on.
        def find_named_switch():
            return find(session_id, "-ios predicate string", 'name == "BLUETOOTH" AND type == "XCUIElementTypeSwitch"')

        named = find_named_switch()
        if not named:
            # Not already on the Bluetooth detail page - navigate there
            # from the root list. That row is an XCUIElementTypeButton
            # (nested inside a non-accessible XCUIElementTypeCell), with
            # its label combining the row's value - "Bluetooth, On" or
            # "Bluetooth, Off", never plain "Bluetooth" - confirmed live
            # via a page source dump after an initial predicate guess
            # (`label == "Bluetooth" AND type == "XCUIElementTypeCell"`)
            # failed to match anything.
            bt_cell = wait_for(
                session_id, "-ios predicate string",
                'label BEGINSWITH "Bluetooth" AND type == "XCUIElementTypeButton"', timeout=15
            )
            click(session_id, bt_cell)
            time.sleep(1.5)
            named = wait_for(session_id, "-ios predicate string", 'name == "BLUETOOTH" AND type == "XCUIElementTypeSwitch"', timeout=10)

        # IMPORTANT (also found the hard way, live): the row actually
        # renders as TWO nested XCUIElementTypeSwitch elements - an
        # OUTER one spanning the whole row (this is the one matched by
        # `name == "BLUETOOTH"` above - reliable for reading state) and
        # an INNER one with no `name` at all, positioned exactly at the
        # visible toggle knob (x=339, width=63, vs the outer's x=40,
        # width=360 - confirmed via `/rect` on both). Clicking the OUTER
        # element silently does NOT toggle the actual control -
        # confirmed by tapping it repeatedly and re-reading the value
        # unchanged every time. Only the INNER element's tap actually
        # flips the real switch, but it has no name to filter on
        # directly - a bare, page-scoped `type ==
        # "XCUIElementTypeSwitch"` query here is safe (unlike on the
        # Settings root list, THIS page has no Airplane-Mode-style
        # collision) and returns both; take whichever isn't the named
        # (outer) one.
        current = get_attribute(session_id, named, "value")
        is_on = current in ("1", "true", True)
        print(f"[{label}] Bluetooth switch currently {'ON' if is_on else 'OFF'} (raw={current!r})")

        if is_on != desired_on:
            both = find_all(session_id, "-ios predicate string", 'type == "XCUIElementTypeSwitch"')
            inner = next((s for s in both if s != named), None)
            if inner is None:
                raise RuntimeError(f"[{label}] could not locate the inner tappable Bluetooth switch (found {len(both)} switch elements)")
            click(session_id, inner)
            time.sleep(2)
            # Re-fetch fresh rather than reusing the pre-tap reference -
            # WDA reports a "stale element reference" for it once the
            # underlying control's value changes.
            fresh = find_named_switch()
            new_val = get_attribute(session_id, fresh, "value") if fresh else None
            print(f"[{label}] toggled -> raw={new_val!r}")
        else:
            new_val = current
            print(f"[{label}] already in desired state, no tap needed")

        save_screenshot(session_id, out_dir, f"tc05_{label}.png")
        return new_val
    finally:
        delete_session(session_id)


def check_ble_disabled_alert(udid, team_id, bundle_id, pin, out_dir, expect_alert):
    """Launch the wallet, tap Authenticate -> In person, and check
    whether the BLE-disabled alert appears. Returns a dict describing
    what was observed."""
    session_id = new_session(udid, team_id, bundle_id)
    result = {"alert_appeared": False, "title": None, "message": None, "buttons": None}
    try:
        wait_for_launch(session_id, pin)

        auth_button = wait_for(
            session_id, "accessibility id", "home_tab_screen_authorization_button", timeout=15
        )
        click(session_id, auth_button)

        in_person = wait_for(
            session_id, "accessibility id", "home_tab_screen_dialog_in_person_button", timeout=10
        )
        click(session_id, in_person)
        time.sleep(2)  # let onShare()'s async getBleAvailability() resolve

        title_elem = find(session_id, "-ios predicate string", 'label == "Enable bluetooth?"')
        if title_elem:
            result["alert_appeared"] = True
            result["title"] = "Enable bluetooth?"  # matched via exact predicate above
            msg_elem = find(
                session_id, "-ios predicate string",
                'label == "Enable bluetooth to share your information using the QR/TAP option"'
            )
            result["message"] = "present" if msg_elem else "MISSING"
            enable_btn = find(session_id, "-ios predicate string", 'label == "Enable" AND type == "XCUIElementTypeButton"')
            cancel_btn = find(session_id, "-ios predicate string", 'label == "Cancel" AND type == "XCUIElementTypeButton"')
            result["buttons"] = {"Enable": bool(enable_btn), "Cancel": bool(cancel_btn)}

            save_screenshot(session_id, out_dir, "tc05_ble_disabled_alert.png")

            if not expect_alert:
                print("UNEXPECTED: alert appeared when it should NOT have (Bluetooth should be back on)")

            if cancel_btn:
                click(session_id, cancel_btn)
                time.sleep(1)
        else:
            save_screenshot(session_id, out_dir, "tc05_no_alert_screen.png")
            if expect_alert:
                print("UNEXPECTED: no alert appeared when Bluetooth should have been off")

        # Confirm the app is still in a sane, usable state - but "sane"
        # means something different depending on what happened above:
        #   - if the alert appeared and was dismissed via Cancel, the
        #     app should be back on Home (HomeTabView's alert dismissal
        #     doesn't navigate anywhere - confirmed by reading
        #     HomeTabView.swift).
        #   - if no alert appeared (Bluetooth was on, onShare() took the
        #     `.available` branch), the app CORRECTLY proceeds INTO the
        #     proximity flow and is expected to have left Home already -
        #     requiring home_tab_screen_authorization_button here would
        #     be asserting the wrong thing (confirmed the hard way live:
        #     a real, correct QR-code screen was misclassified as
        #     "unrecovered" before this fix, because it doesn't have a
        #     dedicated accessibility locator of its own - see TC-14's
        #     investigation of ProximityConnectionView).
        if result["alert_appeared"]:
            recovered = bool(find(session_id, "accessibility id", "home_tab_screen_authorization_button"))
        else:
            qr_caption = find(
                session_id, "-ios predicate string",
                'label CONTAINS "Show this QR code" OR label CONTAINS "Hold your phone near object to scan"'
            )
            recovered = bool(qr_caption)
        result["home_recovered"] = recovered
        if not recovered:
            save_screenshot(session_id, out_dir, "tc05_UNRECOVERED_state.png")

        return result
    finally:
        delete_session(session_id)


def main():
    if len(sys.argv) != 5:
        print(
            "usage: tc-05-ios-ble-disabled.py <udid> <apple-team-id> <wallet-bundle-id> <out-dir>",
            file=sys.stderr,
        )
        sys.exit(2)
    udid, team_id, bundle_id, out_dir = sys.argv[1:5]
    os.makedirs(out_dir, exist_ok=True)

    pin = os.environ.get("EUDI_WALLET_PIN")
    if not pin:
        print("FAIL: EUDI_WALLET_PIN env var not set", file=sys.stderr)
        sys.exit(2)

    overall_ok = True
    try:
        print("--- Step 1: disable Bluetooth via Settings ---")
        val = set_bluetooth(udid, team_id, desired_on=False, out_dir=out_dir, label="step1_disable")
        if val not in ("0", "false", False):
            print(f"FAIL: could not confirm Bluetooth is off (switch value={val!r})", file=sys.stderr)
            overall_ok = False

        print("--- Step 2: attempt to share with Bluetooth off, expect the BLE-disabled alert ---")
        result = check_ble_disabled_alert(udid, team_id, bundle_id, pin, out_dir, expect_alert=True)
        print(f"Result: {json.dumps(result, indent=2)}")
        if not result["alert_appeared"]:
            print("FAIL: expected alert did not appear", file=sys.stderr)
            overall_ok = False
        if result["message"] == "MISSING":
            print("FAIL: alert appeared but message text did not match expected", file=sys.stderr)
            overall_ok = False
        if not (result.get("buttons") or {}).get("Enable") or not (result.get("buttons") or {}).get("Cancel"):
            print("FAIL: alert missing expected Enable/Cancel buttons", file=sys.stderr)
            overall_ok = False
        if not result.get("home_recovered"):
            print("FAIL: app did not return to a recognizable Home state after dismissing the alert", file=sys.stderr)
            overall_ok = False

    finally:
        print("--- Step 3: re-enable Bluetooth via Settings (cleanup, runs regardless of step 2's outcome) ---")
        val = set_bluetooth(udid, team_id, desired_on=True, out_dir=out_dir, label="step3_enable")
        if val not in ("1", "true", True):
            print(f"WARNING: could not confirm Bluetooth is back on (switch value={val!r}) - check the device manually", file=sys.stderr)

    print("--- Step 4: confirm the app behaves normally now that Bluetooth is back on ---")
    result2 = check_ble_disabled_alert(udid, team_id, bundle_id, pin, out_dir, expect_alert=False)
    print(f"Result: {json.dumps(result2, indent=2)}")
    if result2["alert_appeared"]:
        print("FAIL: BLE-disabled alert still appeared even with Bluetooth back on", file=sys.stderr)
        overall_ok = False
    if not result2.get("home_recovered"):
        print("FAIL: app did not proceed normally into the proximity/QR flow with Bluetooth back on", file=sys.stderr)
        overall_ok = False

    if overall_ok:
        print("PASS: TC-05 - BLE-disabled alert appeared with correct content, app recovered cleanly, and behaved normally once Bluetooth was restored.")
        sys.exit(0)
    else:
        print("FAIL: see above", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()

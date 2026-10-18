#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-14 - iOS half: trigger BLE proximity share (Home -> Authenticate ->
In person), then background the app (NOT force-kill - see TC-47 for
that) almost immediately after the QR is displayed - during Android's
engagement/GATT-connect/request-send sequence, well before iOS would
normally reach its consent screen - hold it backgrounded, then
foreground it again and observe what iOS shows.

Why this timing: TC-13's live baseline (`adb logcat -v threadtime`
during an uninterrupted TC-03 run) showed Android's entire
engagement -> GATT connect -> service discovery -> request-send
sequence completes in about 2 seconds after the QR is scanned.
Backgrounding right after the QR appears and holding for
BACKGROUND_HOLD_SECONDS comfortably spans that whole sequence while
iOS's process is suspended, then foregrounding checks what iOS does
with whatever happened (or didn't) while it wasn't running.

Code-grounded reason this is worth checking (not assumed): per
`ProximityConnectionViewModel.swift`, the QR image is generated exactly
once on the `.prepareQr` state and there is no code path that
regenerates or re-checks it when the app returns to foreground - the
view model just sits subscribed to the same coordinator publisher it
was on before backgrounding. Also confirmed live (2026-08-27, this
checkout): no `UIBackgroundModes` key (bluetooth-central/
bluetooth-peripheral) is declared anywhere in this app, so CoreBluetooth
activity should NOT be expected to continue while backgrounded - this
is a genuine interruption, not a no-op.

This script captures BOTH sides' state at each phase (matching TC-13's
"never trust one side's UI alone" discipline, which is why that test
caught a real bug): an iOS screenshot right before backgrounding, an
ANDROID screenshot taken via adb at the end of the hold period (while
iOS is still backgrounded - captures what the verifier's own screen
shows at that moment), then an iOS screenshot after foregrounding, and
a final Android screenshot after giving iOS a moment to react. Ground
truth for what Android's verifier actually logged is NOT this script's
job - see tc-14-verify-state.py, which checks the Android-side adb
logcat capture independently, same as TC-13's tc-13-verify-no-receipt.py.

Talks to a locally-running Appium server (default http://127.0.0.1:4723)
directly over its REST API using only the standard library - no
appium-python-client dependency, matching TC-02/03/04/13. The
background/foreground control uses Appium's `mobile: backgroundApp` /
`mobile: activateApp` extension commands, invoked the same way any
other WebDriver script would (`POST /session/{id}/execute/sync` with
`script: "mobile: <name>"`) - this is the standard, documented way to
invoke Appium mobile extensions without a client library.

Requires env var EUDI_WALLET_PIN (never hardcode a real device PIN into
a committed script, even for a dev/test device) - not actually needed
for this specific flow (it never reaches PIN entry if backgrounding
prevents the consent screen from appearing), but kept for parity with
the other tc-*-ios-*.py scripts and in case the app DOES recover and
reach a PIN screen.

Usage: tc-14-ios-background-during-handshake.py <iphone-udid> <apple-team-id> <bundle-id> <android-serial> <out-dir>
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.request

APPIUM_URL = "http://127.0.0.1:4723"

# How long to hold the app backgrounded, covering Android's ~2s
# engagement+connect+request-send sequence (see module docstring) with
# generous margin.
BACKGROUND_HOLD_SECONDS = 10


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
    return resp["value"]["sessionId"]


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


def execute_mobile(session_id, command, args=None):
    body = {"script": f"mobile: {command}", "args": [args or {}]}
    resp = _request("POST", f"/session/{session_id}/execute/sync", body)
    if isinstance(resp.get("value"), dict) and resp["value"].get("error"):
        print(f"  WARNING: mobile: {command} returned an error: {resp['value']}", file=sys.stderr)
    return resp


def background_app(session_id, seconds):
    """seconds=-1 backgrounds indefinitely until explicitly re-activated."""
    return execute_mobile(session_id, "backgroundApp", {"seconds": seconds})


def activate_app(session_id, bundle_id):
    return execute_mobile(session_id, "activateApp", {"bundleId": bundle_id})


def wait_for(session_id, using, value, timeout=90, interval=4, pin=None):
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
                time.sleep(interval)
                continue
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
    print(f"Saved iOS screenshot: {path}")
    return path


def save_android_screenshot(android_serial, out_dir, name):
    path = os.path.join(out_dir, name)
    try:
        with open(path, "wb") as f:
            result = subprocess.run(
                ["adb", "-s", android_serial, "exec-out", "screencap", "-p"],
                stdout=f, timeout=15,
            )
        if result.returncode == 0 and os.path.getsize(path) > 0:
            print(f"Saved Android screenshot: {path}")
            return path
    except Exception as e:
        print(f"WARNING: failed to capture Android screenshot: {e}", file=sys.stderr)
    return None


def describe_ios_state(session_id):
    """Best-effort classification of what iOS shows - NOT the ground
    truth (Android's logcat is, checked separately), just a same-run
    observation to compare against it."""
    share_button = find(session_id, "accessibility id", "request_screen_share_button")
    if share_button:
        return "CONSENT_SCREEN (recovered - request_screen_share_button present, same as a normal successful handshake)"

    done_button = find(session_id, "-ios predicate string", 'label == "Done" AND type == "XCUIElementTypeButton"')
    if done_button:
        return "SUCCESS_SCREEN (unexpected at this point - a 'Done' button is present)"

    ok_alert = find(session_id, "-ios predicate string", 'label == "OK" AND type == "XCUIElementTypeButton"')
    if ok_alert:
        return "ALERT_PRESENT (an OK-button alert is showing - likely an error dialog)"

    pin_field = find(session_id, "accessibility id", "pin_text_field_0")
    if pin_field:
        return "PIN_SCREEN (unexpected at this point in the flow)"

    in_person_button = find(session_id, "accessibility id", "home_tab_screen_dialog_in_person_button")
    if in_person_button:
        return "HOME_DIALOG (back at the Authenticate dialog - the QR/connection screen was abandoned)"

    home_button = find(session_id, "accessibility id", "home_tab_screen_authorization_button")
    if home_button:
        return "HOME_SCREEN (back at Home entirely - the whole proximity flow was abandoned)"

    static_texts = find_all(session_id, "-ios predicate string", 'type == "XCUIElementTypeStaticText"')
    return f"LIKELY_STILL_ON_QR_OR_UNKNOWN ({len(static_texts)} static text elements found, none matched)"


def main():
    if len(sys.argv) != 6:
        print(
            "usage: tc-14-ios-background-during-handshake.py <udid> <apple-team-id> <bundle-id> <android-serial> <out-dir>",
            file=sys.stderr,
        )
        sys.exit(2)
    udid, team_id, bundle_id, android_serial, out_dir = sys.argv[1:6]
    os.makedirs(out_dir, exist_ok=True)

    pin = os.environ.get("EUDI_WALLET_PIN")
    if not pin:
        print("FAIL: EUDI_WALLET_PIN env var not set", file=sys.stderr)
        sys.exit(2)

    session_id = new_session(udid, team_id, bundle_id)
    try:
        wait_for_launch(session_id, pin)

        auth_button = wait_for(
            session_id, "accessibility id", "home_tab_screen_authorization_button", pin=pin
        )
        click(session_id, auth_button)

        in_person = wait_for(
            session_id, "accessibility id", "home_tab_screen_dialog_in_person_button", timeout=10
        )
        click(session_id, in_person)

        # Give the QR a brief moment to actually render before
        # backgrounding - confirmed live this app has no distinct
        # accessibility marker for "QR now showing" (see
        # ProximityConnectionViewModel.swift - no locator on that view),
        # so this is a short fixed wait, not a wait_for.
        time.sleep(1.5)
        save_screenshot(session_id, out_dir, "tc14_before_background.png")

        print(f"Backgrounding the app now (holding {BACKGROUND_HOLD_SECONDS}s - "
              "Android's engagement/connect/request-send sequence should happen during this window)...")
        background_app(session_id, -1)
        time.sleep(BACKGROUND_HOLD_SECONDS)

        print("Capturing Android's screen state WHILE iOS is still backgrounded...")
        save_android_screenshot(android_serial, out_dir, "tc14_android_during_background.png")

        print("Foregrounding the app again...")
        activate_app(session_id, bundle_id)
        time.sleep(2)  # let the UI settle after returning to foreground

        outcome = describe_ios_state(session_id)
        print(f"iOS state immediately after foregrounding: {outcome}")
        save_screenshot(session_id, out_dir, "tc14_after_foreground.png")

        # Give it a further window in case it recovers/progresses after
        # a beat (e.g. the request was actually received while
        # backgrounded and just needed the UI to catch up), polling
        # rather than assuming the immediate post-foreground state is
        # final.
        deadline = time.time() + 30
        while time.time() < deadline:
            new_outcome = describe_ios_state(session_id)
            if new_outcome != outcome:
                print(f"iOS state changed: {outcome} -> {new_outcome}")
                outcome = new_outcome
            if outcome.startswith("CONSENT_SCREEN") or outcome.startswith("SUCCESS_SCREEN"):
                break
            time.sleep(3)

        final_outcome = describe_ios_state(session_id)
        print(f"FINAL iOS outcome: {final_outcome}")
        save_screenshot(session_id, out_dir, "tc14_final_state.png")
        save_android_screenshot(android_serial, out_dir, "tc14_android_final_state.png")

        with open(os.path.join(out_dir, "tc14_ios_outcome.txt"), "w") as f:
            f.write(final_outcome + "\n")

        print("PASS: iOS side observation complete (see FINAL iOS outcome above - this is NOT a pass/fail verdict on the app)")
    except Exception:
        try:
            save_screenshot(session_id, out_dir, "tc14_EXCEPTION_screen.png")
            save_android_screenshot(android_serial, out_dir, "tc14_android_EXCEPTION_screen.png")
        except Exception as screenshot_err:
            print(f"(also failed to capture a failure screenshot: {screenshot_err})", file=sys.stderr)
        raise
    finally:
        _request("DELETE", f"/session/{session_id}")


if __name__ == "__main__":
    main()

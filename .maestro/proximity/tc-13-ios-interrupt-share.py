#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-13 - iOS half: trigger BLE proximity share (Home -> Authenticate ->
In person), tap Share, then IMMEDIATELY disable the Android verifier's
Bluetooth via adb - before the actual response transfer happens - and
observe what iOS's UI ends up showing.

Why "right after Share", not timed to the transfer itself: a live
baseline capture (2026-08-27, this rig, verbose `adb logcat -v
threadtime` during an uninterrupted TC-03 run) showed the entire GATT
write burst for a full-PID response is six ~512-byte chunks landing in
"08-27 12:10:07.570" to "...07.625" - under 55ms - occurring roughly
6.2s AFTER Android's "Connection Established"/request-sent log line,
which itself lines up with iOS reaching the consent screen. That 6s+
gap is dominated by this script's own UI navigation + PIN entry
latency, not the transfer. A ~55ms window is not reliably hittable by
any reactive adb command (network+shell round-trip alone dominates
that budget) - but disabling the verifier's Bluetooth radio right after
Share, and holding it off through PIN entry, guarantees the radio is
already down by the time the app's SDK gets around to actually writing
the response (PIN entry alone takes several real seconds via this
script's digit-by-digit tap loop), without needing any precise timing
at all. This tests "the response could never be delivered because the
link was down for the whole attempt" - a strictly harder case than
grazing the write mid-burst, and the one directly relevant to the
question this test exists to answer: does the app's success screen
require an actual delivered response, or just a local SDK status that
might fire before/without one.

Code-grounded reason this is worth checking (not assumed): per a
same-day investigation of BaseRequestViewModel/ProximityLoadingViewModel
in this repo, the success navigation is driven purely by
`PresentationState.responseSent`, itself derived from the (unvendored,
third-party) EudiWalletKit SDK's own `$status` publisher -
`ProximitySessionCoordinator.sendResponse()` calls the SDK's
`session.sendResponse(...)` then `session.waitForDisconnect()`; nothing
in this app's own code independently confirms the verifier actually
received/reassembled the full response. ISO 18013-5 BLE has no
built-in "verifier acked full receipt" handshake at the transport
level, so if the SDK reports `.responseSent` based on local
write-queue completion rather than a genuine peer acknowledgment, the
app could show success while the verifier received partial or zero
data. This script's job is to find out empirically what actually
happens - it does not assume the answer either way.

Ground truth for "what did Android actually receive" is NOT this
script's job - see tc-13-verify-no-receipt.py, which checks the
Android-side adb logcat capture independently (same discipline as
TC-24/25/26's server-side receipt verification, applied to the BLE
verifier's own logs instead of a remote server).

Talks to a locally-running Appium server (default http://127.0.0.1:4723)
directly over its REST API using only the standard library - no
appium-python-client dependency, matching TC-02/03/04.

Requires env var EUDI_WALLET_PIN (never hardcode a real device PIN into
a committed script, even for a dev/test device).

Usage: tc-13-ios-interrupt-share.py <iphone-udid> <apple-team-id> <bundle-id> <android-serial> <out-dir>
"""
import base64
import json
import os
import subprocess
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
    print(f"Saved screenshot: {path}")
    return path


def disable_android_bluetooth(android_serial):
    t0 = time.time()
    result = subprocess.run(
        ["adb", "-s", android_serial, "shell", "svc", "bluetooth", "disable"],
        capture_output=True, text=True, timeout=10,
    )
    elapsed = time.time() - t0
    print(f"adb bluetooth disable: exit={result.returncode} elapsed={elapsed:.2f}s stdout={result.stdout.strip()!r}")
    return result.returncode == 0


def enable_android_bluetooth(android_serial):
    try:
        subprocess.run(
            ["adb", "-s", android_serial, "shell", "svc", "bluetooth", "enable"],
            capture_output=True, text=True, timeout=10,
        )
        print("Re-enabled Android Bluetooth (cleanup).")
    except Exception as e:
        print(f"WARNING: failed to re-enable Android Bluetooth: {e}", file=sys.stderr)


def describe_final_state(session_id):
    """Best-effort classification of what's on screen after the
    interrupted send attempt - NOT the ground truth (that's Android's
    logcat, checked separately), just a same-run observation to compare
    against it."""
    done_button = find(session_id, "-ios predicate string", 'label == "Done" AND type == "XCUIElementTypeButton"')
    if done_button:
        return "SUCCESS_SCREEN (found a 'Done' button, same as a normal completed share)"

    error_texts = find_all(session_id, "-ios predicate string", 'type == "XCUIElementTypeStaticText"')
    ok_alert = find(session_id, "-ios predicate string", 'label == "OK" AND type == "XCUIElementTypeButton"')
    if ok_alert:
        return "ALERT_PRESENT (an OK-button alert is showing - likely an error dialog)"

    pin_field = find(session_id, "accessibility id", "pin_text_field_0")
    if pin_field:
        return "PIN_SCREEN_STILL_SHOWING (unexpected at this point in the flow)"

    return f"UNKNOWN ({len(error_texts)} static text elements found, no Done/alert/PIN matched)"


def main():
    if len(sys.argv) != 6:
        print(
            "usage: tc-13-ios-interrupt-share.py <udid> <apple-team-id> <bundle-id> <android-serial> <out-dir>",
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

        print("QR displayed - waiting for consent screen...")
        share_button = wait_for(
            session_id, "accessibility id", "request_screen_share_button", timeout=90
        )

        print("Tapping Share...")
        click(session_id, share_button)

        print("Disabling Android verifier's Bluetooth NOW (immediately after Share)...")
        if not disable_android_bluetooth(android_serial):
            print("FAIL: could not disable Android Bluetooth via adb", file=sys.stderr)
            sys.exit(1)

        # PIN entry proceeds normally - it's local wallet authorization,
        # independent of the (now-dead) BLE link. The actual send attempt
        # happens after this, on the loading screen.
        wait_for(session_id, "accessibility id", "pin_text_field_0", timeout=15)
        enter_pin(session_id, pin)
        print("PIN entered. Watching for the outcome of the (now-guaranteed-to-fail) send attempt...")

        # Poll for up to 90s, screenshotting periodically, rather than
        # waiting for one specific expected element - we don't know in
        # advance whether this ends in a success screen, an error alert,
        # or a hang, and forcing a single wait_for() on the "happy path"
        # Done button would misreport a hang as a script bug.
        deadline = time.time() + 90
        last_shot = 0
        outcome = None
        while time.time() < deadline:
            state = describe_final_state(session_id)
            if not state.startswith("UNKNOWN") and not state.startswith("PIN_SCREEN"):
                outcome = state
                break
            if time.time() - last_shot > 15:
                save_screenshot(session_id, out_dir, f"tc13_watch_{int(time.time())}.png")
                last_shot = time.time()
            time.sleep(3)

        if outcome is None:
            outcome = f"TIMED_OUT_AFTER_90s (last state: {describe_final_state(session_id)})"

        print(f"OUTCOME: {outcome}")
        save_screenshot(session_id, out_dir, "tc13_final_state.png")

        with open(os.path.join(out_dir, "tc13_ios_outcome.txt"), "w") as f:
            f.write(outcome + "\n")

        print("PASS: iOS side observation complete (see OUTCOME above - this is NOT a pass/fail verdict on the app)")
    except Exception:
        try:
            save_screenshot(session_id, out_dir, "tc13_EXCEPTION_screen.png")
        except Exception as screenshot_err:
            print(f"(also failed to capture a failure screenshot: {screenshot_err})", file=sys.stderr)
        raise
    finally:
        _request("DELETE", f"/session/{session_id}")
        enable_android_bluetooth(android_serial)


if __name__ == "__main__":
    main()

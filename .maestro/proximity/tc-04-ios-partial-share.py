#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-04 - iOS half: trigger BLE proximity share (Home -> Authenticate ->
In person), but on the resulting consent screen, DESELECT two requested
fields before tapping Share - the original manual-testing freeze repro
(Android requests the full PID, iOS shares only a partial subset).

Identical to TC-02/TC-03's iOS half up through reaching the consent
screen. What's new is the deselection step, which turned out to need
more than a live-calibrated coordinate (confirmed the hard way,
2026-08-27, this device):

  1. The document section renders COLLAPSED by default ("View details"
     chevron) - individual field rows aren't in the tree until the
     section header (which DOES have its own accessibility id,
     "request_screen_requested_document_0") is tapped to expand it.
  2. Every field row inside that section is merged into ONE opaque
     accessibility element by `.accessibilityElement(children: .combine)`
     (see BaseRequestView.swift / CombineAccessibilityModifier.swift) -
     individual rows have no accessibility id or predicate to find them
     by, so deselecting a specific field needs a raw coordinate tap -
     the same fundamental workaround TC-02's Android checkbox needed,
     for a different underlying reason (accessibility merging here vs.
     a genuinely separate unlabeled widget there).
  3. Unlike Android's checkboxes (fixed, reusable coordinates confirmed
     once for TC-02), a coordinate calibrated from ONE session's
     screenshot did NOT reliably transfer to the next: a first live run
     using coordinates read off a screenshot had literally zero effect;
     a second run using coordinates re-derived by precise color-blob
     detection on that SAME screenshot still landed on the wrong row
     (the nested, non-checkbox "Birth Place" group) when tried on a
     FRESH session. Root cause: this app's PID documents are a
     one-time-use BATCH (see README.md - "the wallet holds multiple
     stored PID (MSO Mdoc) instances... a request matches ALL of them"),
     so each new presentation can render a different stored credential
     instance's card, and nothing guarantees identical field content or
     row layout between separate sessions.

Because of (3), this script does NOT hardcode tap coordinates. Instead
it re-detects checkbox row positions FRESH on every run: it screenshots
the expanded consent screen, scans for the checked-checkbox's blue fill
color in a right-edge vertical strip, clusters matching rows, and taps
two of the DETECTED rows (skipping the first, so at least one field
stays selected even before considering canShare()'s own >=1 rule, and
skipping any cluster that overlaps the Share button's known screen
region). Each tap is then verified via before/after screenshot diff -
a genuine checkbox toggle produces a small, localized change; a miss
(e.g. hitting a divider, or a nested/non-checkbox row) produces no
diff or a large cascading one, either of which triggers a retry rather
than blind trust.

Talks to a locally-running Appium server (default http://127.0.0.1:4723)
directly over its REST API using only the standard library, plus
Pillow (PIL) for the screenshot diffing/color detection above - no
appium-python-client dependency, matching TC-02/TC-03's REST approach.

Requires env var EUDI_WALLET_PIN (never hardcode a real device PIN into
a committed script, even for a dev/test device).

Usage: tc-04-ios-partial-share.py <physical-iphone-udid> <apple-team-id> <bundle-id> <out-dir>
"""
import base64
import io
import json
import os
import sys
import time
import urllib.request

from PIL import Image, ImageChops

APPIUM_URL = "http://127.0.0.1:4723"

# How many field rows to deselect (skipping the first detected row -
# see module docstring). Two is enough to prove a genuine partial share
# without going anywhere near canShare()'s ">=1 selected" edge case.
FIELDS_TO_DESELECT = 2

# Checked checkboxes render as this bright blue fill (screenshot pixel
# space, this device: 1320x2868px / 3x scale for a 440x956pt window).
# Confirmed live (2026-08-27) via direct pixel sampling of a saved
# screenshot - not a guessed color.
CHECKBOX_X_RANGE_PX = (1200, 1310)


def _is_checkbox_blue(r, g, b):
    return 60 <= r <= 130 and 150 <= g <= 210 and 220 <= b <= 255


def find_checkbox_rows_px(img):
    """Scan the right-edge strip for the checked-checkbox blue fill,
    cluster matching scanlines into rows, return [(y0, y1), ...] in
    screenshot pixel space, top to bottom."""
    w, h = img.size
    px = img.load()
    x_lo, x_hi = CHECKBOX_X_RANGE_PX
    x_hi = min(x_hi, w)
    rows_with_blue = []
    for y in range(0, h):
        count = 0
        for x in range(x_lo, x_hi, 3):
            r, g, b = px[x, y]
            if _is_checkbox_blue(r, g, b):
                count += 1
        if count > 5:
            rows_with_blue.append(y)

    clusters = []
    cur = []
    for y in rows_with_blue:
        if cur and y - cur[-1] > 5:
            clusters.append((cur[0], cur[-1]))
            cur = []
        cur.append(y)
    if cur:
        clusters.append((cur[0], cur[-1]))
    return clusters


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


def tap_point(session_id, x, y):
    """Raw coordinate tap via the W3C Actions API - needed for the
    per-field deselection since individual rows have no accessibility id
    (see module docstring). x/y are in Appium's point coordinate space
    (screenshot pixels / 3 on this device)."""
    _request(
        "POST",
        f"/session/{session_id}/actions",
        {
            "actions": [
                {
                    "type": "pointer",
                    "id": "finger1",
                    "parameters": {"pointerType": "touch"},
                    "actions": [
                        {"type": "pointerMove", "duration": 0, "x": x, "y": y},
                        {"type": "pointerDown", "button": 0},
                        {"type": "pause", "duration": 100},
                        {"type": "pointerUp", "button": 0},
                    ],
                }
            ]
        },
    )


def wait_for(session_id, using, value, timeout=90, interval=4, pin=None):
    """Poll for an element. If `pin` is given, also watch for the app
    having relocked mid-wait and re-authenticate automatically. Same
    fix as TC-02/TC-03's wait_for - see those scripts' docstrings for
    the root cause this protects against.
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
                time.sleep(interval)
                continue
        time.sleep(interval)
    raise TimeoutError(f"Timed out waiting for {using}={value!r}")


def enter_pin(session_id, pin):
    for digit in pin:
        elem = wait_for(session_id, "accessibility id", digit, timeout=20, interval=2)
        click(session_id, elem)


def wait_for_launch(session_id, pin, timeout=20, interval=1):
    """Same splash-screen protection as TC-02/TC-03 - see those scripts'
    docstrings for the root cause this avoids."""
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


def save_screenshot_bytes(raw_png_bytes, out_dir, name):
    path = os.path.join(out_dir, name)
    with open(path, "wb") as f:
        f.write(raw_png_bytes)
    print(f"Saved screenshot: {path}")
    return path


def screenshot_raw(session_id):
    shot = _request("GET", f"/session/{session_id}/screenshot")
    return base64.b64decode(shot["value"])


def screenshot_image(session_id):
    return Image.open(io.BytesIO(screenshot_raw(session_id))).convert("RGB")


def dismiss_gate_alert_if_present(session_id, timeout=3):
    """The very FIRST tap on any claim row in a freshly-loaded request
    screen does NOT toggle a checkbox - confirmed live, 2026-08-27, the
    hard way (two "misses" that were actually this, not bad
    coordinates). BaseRequestViewModel.onCombinationItemClick has a
    branch gated on `showMissingCredentials` (true from a fresh
    resetState()/init): while true, the first row tap only sets
    itemsChanged=true (driving BaseRequestView's native SwiftUI .alert,
    "Your selection of data to be shared may impact the service") and
    flips showMissingCredentials false - it does NOT call
    toggleSelection. Only the SECOND and later row taps actually
    toggle. The alert's dimmed backdrop produces a huge screenshot
    diff that looks exactly like a mis-tap (e.g. hitting the nested
    "Birth Place" row) unless this is specifically handled - dismiss it
    (native alert, so a normal accessibility-id/predicate find works
    fine even though the row content underneath is accessibility-merged)
    before doing any real diff-based verification.
    """
    ok_button = find(session_id, "-ios predicate string", 'label == "OK" AND type == "XCUIElementTypeButton"')
    if ok_button:
        print("  dismissing one-time 'may impact the service' warning alert...")
        click(session_id, ok_button)
        time.sleep(0.8)
        return True
    return False


def deselect_detected_rows(session_id, out_dir):
    """Detect checkbox rows fresh (see module docstring for why this
    can't be a hardcoded coordinate), tap FIELDS_TO_DESELECT of them
    (skipping the first), verifying each tap via screenshot diff before
    moving on. Returns True if all deselections were confirmed."""
    before_img = screenshot_image(session_id)
    w, h = before_img.size
    clusters = find_checkbox_rows_px(before_img)
    print(f"Detected {len(clusters)} checkbox rows in this session's card.")

    # Exclude anything close to the bottom of the screen (Share button
    # is also this same blue and would otherwise show up as a cluster).
    share_zone_px = h * 0.85
    field_clusters = [c for c in clusters if c[0] < share_zone_px]

    if len(field_clusters) < FIELDS_TO_DESELECT + 1:
        print(
            f"FAIL: only found {len(field_clusters)} field checkbox(es) - need at least "
            f"{FIELDS_TO_DESELECT + 1} (to deselect {FIELDS_TO_DESELECT} and leave >=1 selected)",
            file=sys.stderr,
        )
        save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc04_detect_FAILED.png")
        return False

    # Skip the first row (leave it selected) - target the next
    # FIELDS_TO_DESELECT rows after it.
    targets = field_clusters[1 : 1 + FIELDS_TO_DESELECT]
    print(f"Targeting rows at px_y centers: {[int((a + b) / 2) for a, b in targets]}")

    # Consume the one-time "gate" tap on the first target row (see
    # dismiss_gate_alert_if_present's docstring) - this tap alone does
    # NOT toggle anything, it only unlocks real toggling for every
    # subsequent tap this session.
    gate_y0, gate_y1 = targets[0]
    gate_x_pt = (CHECKBOX_X_RANGE_PX[0] + CHECKBOX_X_RANGE_PX[1]) / 2 / 3
    gate_y_pt = (gate_y0 + gate_y1) / 2 / 3
    print(f"  gate tap at pt=({gate_x_pt:.0f},{gate_y_pt:.0f}) (consumes the one-time selection gate)")
    tap_point(session_id, gate_x_pt, gate_y_pt)
    time.sleep(0.8)
    if not dismiss_gate_alert_if_present(session_id):
        print(
            "FAIL: expected the 'may impact the service' alert after the first row tap "
            "but it did not appear - app behavior may have changed",
            file=sys.stderr,
        )
        save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc04_gate_FAILED.png")
        return False

    for i, (y0, y1) in enumerate(targets):
        center_px = (y0 + y1) / 2
        x_px = (CHECKBOX_X_RANGE_PX[0] + CHECKBOX_X_RANGE_PX[1]) / 2
        # Convert to points (Appium's coordinate space) - this device is
        # 3x scale, confirmed via GET /session/{id}/window/rect.
        x_pt, y_pt = x_px / 3, center_px / 3

        before = screenshot_image(session_id)
        tap_point(session_id, x_pt, y_pt)
        time.sleep(0.6)
        after = screenshot_image(session_id)
        # Crop out the status bar before diffing - its clock can tick
        # over between the before/after screenshots and inflate the
        # diff bounding box into covering nearly the whole screen even
        # though the actual checkbox change was small and correct
        # (confirmed live, 2026-08-27: a real, correct deselection was
        # rejected by this check before the crop was added).
        content_top_px = 150
        diff = ImageChops.difference(
            before.crop((0, content_top_px, before.width, before.height)),
            after.crop((0, content_top_px, after.width, after.height)),
        )
        bbox = diff.getbbox()
        if bbox is None:
            print(f"FAIL: tap #{i} at pt=({x_pt:.0f},{y_pt:.0f}) produced no visible change", file=sys.stderr)
            save_screenshot_bytes(screenshot_raw(session_id), out_dir, f"tc04_deselect_FAILED_{i}.png")
            return False
        dx0, dy0, dx1, dy1 = bbox
        diff_height_pt = (dy1 - dy0) / 3
        if diff_height_pt > 60:
            print(
                f"FAIL: tap #{i} at pt=({x_pt:.0f},{y_pt:.0f}) produced a large diff "
                f"(height={diff_height_pt:.0f}pt) - likely hit the wrong row",
                file=sys.stderr,
            )
            save_screenshot_bytes(screenshot_raw(session_id), out_dir, f"tc04_deselect_FAILED_{i}.png")
            return False
        print(f"  tap #{i} at pt=({x_pt:.0f},{y_pt:.0f}) confirmed (diff height={diff_height_pt:.0f}pt)")

    return True


def main():
    if len(sys.argv) != 5:
        print(
            "usage: tc-04-ios-partial-share.py <udid> <apple-team-id> <bundle-id> <out-dir>",
            file=sys.stderr,
        )
        sys.exit(2)
    udid, team_id, bundle_id, out_dir = sys.argv[1:5]
    os.makedirs(out_dir, exist_ok=True)

    pin = os.environ.get("EUDI_WALLET_PIN")
    if not pin:
        print("FAIL: EUDI_WALLET_PIN env var not set", file=sys.stderr)
        sys.exit(2)

    session_id = new_session(udid, team_id, bundle_id)
    try:
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

        print(
            "QR displayed - waiting for consent screen "
            "(engagement + BLE connect happen passively on the Android side)..."
        )
        wait_for(session_id, "accessibility id", "request_screen_share_button", timeout=90)

        # Expand the document section - individual field rows aren't in
        # the tree until this is tapped (see module docstring).
        doc_header = wait_for(
            session_id, "accessibility id", "request_screen_requested_document_0", timeout=15
        )
        click(session_id, doc_header)
        time.sleep(1.5)  # let the expand animation settle

        print(f"Deselecting {FIELDS_TO_DESELECT} fields before sharing (detected + diff-verified per run)...")
        if not deselect_detected_rows(session_id, out_dir):
            print("FAIL: could not confirm field deselection", file=sys.stderr)
            sys.exit(1)

        # Forensic evidence of what was actually left selected right
        # before tapping Share - valuable regardless of whether this run
        # freezes (see tc-04-ble-full-pid-partial-share.sh for why this
        # matters for TC-04 specifically: the original bug had no such
        # evidence and was never reproduced a second time).
        save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc04_pre_share_screen.png")

        # Re-find Share - confirms canShare() is still true (>=1 field
        # left selected) after deselection, matching this app's actual
        # gating logic (BaseRequestViewModel.canShare()).
        share_button = wait_for(
            session_id, "accessibility id", "request_screen_share_button", timeout=10
        )
        click(session_id, share_button)

        # Share is followed by a second, separate "Confirm request" PIN
        # screen - confirmed live in TC-02/TC-03, unchanged here.
        wait_for(session_id, "accessibility id", "pin_text_field_0", timeout=15)
        enter_pin(session_id, pin)

        done_button = wait_for(
            session_id,
            "-ios predicate string",
            'label == "Done" AND type == "XCUIElementTypeButton"',
            timeout=30,
        )
        click(session_id, done_button)
        print("PASS: iOS side completed - partial share confirmed, PIN entered, Done tapped")
    except Exception:
        # If anything hangs/times out, the most valuable diagnostic is
        # what the screen actually looked like at that moment - this is
        # the exact freeze-forensics ask for TC-04. Best-effort: the
        # session/WDA may itself be unresponsive if the app has frozen.
        try:
            save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc04_FAILURE_screen.png")
        except Exception as screenshot_err:
            print(f"(also failed to capture a failure screenshot: {screenshot_err})", file=sys.stderr)
        raise
    finally:
        _request("DELETE", f"/session/{session_id}")


if __name__ == "__main__":
    main()

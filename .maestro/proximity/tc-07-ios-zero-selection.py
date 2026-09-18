#!/usr/bin/env python3
# ⚠️ PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,
# navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
# attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.
#

"""TC-07 - iOS half: trigger BLE proximity share (Home -> Authenticate ->
In person), then deselect EVERY requested field on the consent screen
(not just some, like TC-04's partial scenario) and confirm the Share
button becomes disabled rather than letting an empty response be sent.

Reuses TC-04's proven per-run checkbox detection (color-blob scan for
the checked-checkbox blue fill - this app's one-time-use PID batch
design means row content/order isn't guaranteed stable between
sessions, so coordinates are never hardcoded, only detected fresh) and
its "one-time selection gate" handling (the first tap on any claim row
in a freshly-loaded request screen only dismisses a native "may impact
the service" warning and does not itself toggle anything - see
tc-04-ios-partial-share.py's dismiss_gate_alert_if_present docstring
for the full explanation). See that script for anything not re-derived
here.

What's different from TC-04, beyond deselecting every row instead of
two:

1. This card turned out to be scrollable (confirmed live - TC-04 never
   needed to scroll since it only ever touched the first couple of
   rows). Rows below the fold aren't in any screenshot until scrolled
   into view, so detection has to repeat after each scroll rather than
   running once.
2. This device's actual field set includes NESTED/expandable groups
   (e.g. "Birth Place", "Nationality" - shown with a chevron, not a
   checkbox) whose own children carry independently-selectable
   checkboxes. Confirmed by reading `RequestDataUIModel.swift`'s
   `canShare()`/`filterSelectedRows()`: both recurse into a `.nested`
   item's `expanded` children when tallying/filtering selections, and
   `filterSelectedRows()` drops a nested group entirely from the
   outgoing payload only if none of its children remain selected. So a
   genuine zero-selection test MUST expand these groups and deselect
   their children too, not just the flat top-level rows - confirmed
   live that skipping them left Share enabled (1 real run: deselecting
   only the 7 flat rows left it `enabled=True` throughout, because 2
   nested groups' children were still selected the whole time).
   Chevron icons turned out to use the SAME blue color as a checked
   checkbox (confirmed live via direct pixel sampling) but a much
   NARROWER horizontal span (~18px vs. a checkbox's ~70px, sampled at
   the same 3x-scale pixel space) - classifying by blob width, not
   just color, is what lets one scan tell rows apart from each other
   and from the (wider still, ~800px+) Share button.

After EACH deselection this script reads the Share button's `enabled`
state directly (WebDriver's standard element `/enabled` endpoint - the
actual accessibility-level enabled/disabled flag, not just a visual
inference from a screenshot). Code-grounded expected behavior (confirmed
by reading the source before writing this script): `BaseRequestView.
swift`'s `shareButton()` binds `isEnabled: canShare`, where `canShare` is
`viewState.allowShare && (registrationWarning == nil ||
isRiskAcknowledged)` (`BaseRequestView.swift:94`), and `allowShare`
comes from `BaseRequestViewModel.canShare(with:)` -> `items.canShare()`
(`RequestDataUIModel.swift:237-269`), which is `false` exactly when
`checkboxSelections.contains(true)` is false - i.e. zero fields
selected, checked across the WHOLE tree including nested children. So
Share should visibly stay enabled after every deselection except the
very last one, and should be reported as NOT enabled once zero fields
remain - this script checks that transition, not just the end state.

IMPORTANT lesson repeated the hard way while investigating the above:
never open a second Appium session while a flow is mid-session - it
force-relaunches the target app and kills the in-progress Android BLE
session underneath it. Already documented once in TC-04's own
docstring; this script's own development re-triggered it once more by
mistake (a quick "peek" session to re-measure a coordinate reset the
whole run back to the PIN screen). Everything here runs in exactly one
continuous session from launch to the final back-navigation.

After confirming Share is disabled with zero selections, this script
also attempts to tap it anyway (to confirm it's genuinely inert, not
just visually greyed out with a working tap underneath), then cleanly
backs out via the toolbar's back button ("back_button" -
ToolbarLocators.chevronLeft) rather than leaving the app stuck mid-flow.

Ground truth for "did Android actually receive anything" is NOT this
script's job - see tc-07-verify-no-receipt.py, which checks the
Android-side adb logcat capture independently, same discipline as
TC-13/14.

Talks to a locally-running Appium server (default http://127.0.0.1:4723)
directly over its REST API using only the standard library, plus
Pillow (PIL) for the screenshot color-detection above - no
appium-python-client dependency, matching TC-02/03/04/13/14.

Requires env var EUDI_WALLET_PIN (never hardcode a real device PIN into
a committed script, even for a dev/test device) - kept for parity with
the other tc-*-ios-*.py scripts even though this flow is not expected
to ever reach a PIN screen.

Usage: tc-07-ios-zero-selection.py <iphone-udid> <apple-team-id> <bundle-id> <out-dir>
"""
import base64
import io
import json
import os
import sys
import time
import urllib.request

from PIL import Image

APPIUM_URL = "http://127.0.0.1:4723"

# Checked checkboxes AND chevron icons both render in this same bright
# blue (screenshot pixel space, this device: 1320x2868px / 3x scale for
# a 440x956pt window) - confirmed live via direct pixel sampling of a
# saved screenshot, not a guessed color. What distinguishes them is
# horizontal SPAN, not color: a checkbox's filled/outlined square spans
# ~70px, a chevron glyph spans ~18px, and the Share button (also this
# blue) spans 800px+. The x-range below is wide enough to catch both a
# checkbox (previously scanned as 1200-1310px) and a chevron (confirmed
# live to start around x=1190) in one pass.
ROW_ICON_X_RANGE_PX = (1150, 1310)
CHECKBOX_SPAN_PX = (50, 110)
# Confirmed live across two different chevrons (Nationality's own row:
# 18px: the document section header's own collapse arrow: 46px) - wide
# enough to cover both while staying clear of a checkbox's 50px floor.
CHEVRON_SPAN_PX = (8, 48)


def _is_row_blue(r, g, b):
    return 60 <= r <= 130 and 150 <= g <= 210 and 220 <= b <= 255


def find_rows_px(img):
    """Scan for the blue icon strip (checkbox OR chevron), cluster
    matching scanlines into rows, classify each by the horizontal span
    of blue pixels actually found in it. Returns a list of
    {"y0", "y1", "kind"} dicts, top to bottom, kind in
    {"checkbox", "chevron", "other"} ("other" covers the Share button
    and anything else this wide - always excluded by callers).

    IMPORTANT (found the hard way, live): span alone can't tell a
    CHECKED checkbox from an UNCHECKED (outline-only) one - an outline
    square's top/bottom border rows are solid blue across its FULL
    width, giving the exact same span as a genuinely filled square,
    even though its middle rows are mostly empty. Without a fill-density
    check, an already-deselected checkbox kept getting reported as
    "still a checkbox present" and re-tapped forever (confirmed live: 40
    consecutive taps on the same row, alternating select/deselect,
    never making progress). Fixed by additionally requiring most rows
    WITHIN the cluster (not just its top/bottom edge) to be mostly blue
    - true for a fill, false for an outline whose interior rows only
    touch blue at the two side-border columns.
    """
    w, h = img.size
    px = img.load()
    x_lo, x_hi = ROW_ICON_X_RANGE_PX
    x_hi = min(x_hi, w)
    rows_with_blue = {}
    for y in range(0, h):
        xs = [x for x in range(x_lo, x_hi, 2) if _is_row_blue(*px[x, y])]
        if len(xs) >= 3:
            rows_with_blue[y] = (min(xs), max(xs), len(xs))

    ys = sorted(rows_with_blue)
    clusters = []
    cur = []
    for y in ys:
        if cur and y - cur[-1] > 5:
            clusters.append(cur)
            cur = []
        cur.append(y)
    if cur:
        clusters.append(cur)

    results = []
    for cluster_ys in clusters:
        x_min = min(rows_with_blue[y][0] for y in cluster_ys)
        x_max = max(rows_with_blue[y][1] for y in cluster_ys)
        span = x_max - x_min
        # Sampled every 2px, so a row that's blue across its full
        # measured span should report a count near span/2 - an outline's
        # interior rows (blue only at the two side borders) report a
        # tiny fixed count regardless of span.
        expected_full_count = max(1, span / 2)
        full_rows = sum(1 for y in cluster_ys if rows_with_blue[y][2] >= 0.6 * expected_full_count)
        fill_ratio = full_rows / len(cluster_ys)
        if CHECKBOX_SPAN_PX[0] <= span <= CHECKBOX_SPAN_PX[1] and fill_ratio < 0.4:
            # Outline-only (unchecked) square - already deselected,
            # nothing to do here.
            continue
        if CHECKBOX_SPAN_PX[0] <= span <= CHECKBOX_SPAN_PX[1]:
            kind = "checkbox"
        elif CHEVRON_SPAN_PX[0] <= span <= CHEVRON_SPAN_PX[1]:
            kind = "chevron"
            # IMPORTANT (also found the hard way, live): a chevron glyph
            # tapers - "chevron.down" (collapsed, actionable) is wide at
            # its top row and narrows to a point at the bottom; "chevron.
            # up" (already expanded) is the mirror image. Without
            # checking this, an already-expanded nested group's chevron
            # looks identical to an unexpanded one by span alone, and
            # kept getting re-tapped forever, alternately
            # collapsing/re-expanding it with nothing left to do each
            # time (confirmed live: 30+ consecutive taps on the same
            # nested group, no progress). Comparing the width of the
            # cluster's FIRST row (top) against its LAST row (bottom) -
            # confirmed live on both a genuinely collapsed and a
            # genuinely expanded chevron - reliably tells them apart.
            top_width = rows_with_blue[cluster_ys[0]][1] - rows_with_blue[cluster_ys[0]][0]
            bottom_width = rows_with_blue[cluster_ys[-1]][1] - rows_with_blue[cluster_ys[-1]][0]
            if bottom_width > top_width:
                # Already expanded (chevron.up) - not a valid target.
                continue
        else:
            kind = "other"
        results.append({"y0": cluster_ys[0], "y1": cluster_ys[-1], "kind": kind, "span": span})
    return results


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


def click(session_id, element_id):
    _request("POST", f"/session/{session_id}/element/{element_id}/click")


def is_enabled(session_id, element_id):
    resp = _request("GET", f"/session/{session_id}/element/{element_id}/enabled")
    return resp.get("value")


def tap_point(session_id, x, y):
    """Raw coordinate tap via the W3C Actions API - needed for the
    per-field deselection since individual rows have no accessibility id
    (see TC-04's docstring for the full accessibility-merging reason)."""
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


def swipe_up(session_id, start_y=700, end_y=200, x=200):
    """Drag from start_y to end_y (points) to scroll the card's content
    up, revealing rows further down. Confirmed live this card genuinely
    scrolls (TC-04 never needed to - it only ever touched the first
    couple of rows)."""
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
                        {"type": "pointerMove", "duration": 0, "x": x, "y": start_y},
                        {"type": "pointerDown", "button": 0},
                        {"type": "pointerMove", "duration": 300, "x": x, "y": end_y},
                        {"type": "pointerUp", "button": 0},
                    ],
                }
            ]
        },
    )


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


def dismiss_gate_alert_if_present(session_id):
    """See tc-04-ios-partial-share.py's docstring on this exact function
    for the full explanation - the first tap on any claim row in a
    fresh request screen only dismisses this warning, it doesn't toggle
    anything."""
    ok_button = find(session_id, "-ios predicate string", 'label == "OK" AND type == "XCUIElementTypeButton"')
    if ok_button:
        print("  dismissing one-time 'may impact the service' warning alert...")
        click(session_id, ok_button)
        time.sleep(0.8)
        return True
    return False


def _row_x_pt(row):
    return (ROW_ICON_X_RANGE_PX[0] + ROW_ICON_X_RANGE_PX[1]) / 2 / 3


def _row_y_pt(row):
    return (row["y0"] + row["y1"]) / 2 / 3


def deselect_all_and_track_share_state(session_id, out_dir, max_actions=40):
    """Iteratively detect and deselect EVERY checkbox on the card,
    expanding nested groups (Birth Place, Nationality, ...) as needed and
    scrolling down when nothing actionable remains in the current
    viewport - never hardcoding a row count, since neither the field set
    nor which fields are flat vs. nested is guaranteed stable between
    sessions (see module docstring). Re-detects from scratch every
    iteration rather than tracking absolute positions, since expanding a
    nested group shifts everything below it.

    Records the Share button's enabled/disabled state after each real
    checkbox deselection. Returns the list of (label, enabled_after)
    tuples, or None on a detection/gate failure.
    """
    gate_consumed = False
    results = []
    # The document section's OWN collapse chevron ("PID (MSO Mdoc)" /
    # "View details") uses the exact same blue chevron glyph as a
    # field-level nested group's chevron (Birth Place, Nationality,
    # ...) and sits above every field row - confirmed live via pixel
    # sampling (span=46px, same range as a real field chevron). Tapping
    # it would collapse the whole section, hiding everything. Guarded
    # against by recording the first checkbox's y-position (Birth Date
    # is always the first field, confirmed live) as a floor - any
    # chevron above that, minus a half-pitch margin, is presumed to be
    # this header, not a real field, and is never treated as a target.
    header_floor_pt = None

    for action_num in range(max_actions):
        img = screenshot_image(session_id)
        rows = [r for r in find_rows_px(img) if r["kind"] in ("checkbox", "chevron")]

        checkbox_rows = [r for r in rows if r["kind"] == "checkbox"]
        chevron_rows = [r for r in rows if r["kind"] == "chevron"]

        if header_floor_pt is None and checkbox_rows:
            header_floor_pt = _row_y_pt(checkbox_rows[0]) - 50
        if header_floor_pt is not None:
            chevron_rows = [r for r in chevron_rows if _row_y_pt(r) > header_floor_pt]

        target = checkbox_rows[0] if checkbox_rows else (chevron_rows[0] if chevron_rows else None)

        if target is None:
            # Nothing actionable visible - try scrolling for more.
            before_scroll = img.tobytes()
            swipe_up(session_id)
            time.sleep(0.8)
            after_img = screenshot_image(session_id)
            if after_img.tobytes() == before_scroll:
                print("  scroll produced no change - reached the bottom, nothing left to deselect.")
                break
            print("  scrolled down to look for more rows...")
            continue

        x_pt, y_pt = _row_x_pt(target), _row_y_pt(target)

        if not gate_consumed:
            print(f"  gate tap at pt=({x_pt:.0f},{y_pt:.0f}) (consumes the one-time selection gate, kind={target['kind']})")
            tap_point(session_id, x_pt, y_pt)
            time.sleep(0.8)
            if not dismiss_gate_alert_if_present(session_id):
                print(
                    "FAIL: expected the 'may impact the service' alert after the first row tap "
                    "but it did not appear - app behavior may have changed",
                    file=sys.stderr,
                )
                save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc07_gate_FAILED.png")
                return None
            gate_consumed = True
            continue  # re-scan - this same row still needs its real tap

        if target["kind"] == "chevron":
            print(f"  expanding nested group at pt=({x_pt:.0f},{y_pt:.0f})")
            tap_point(session_id, x_pt, y_pt)
            time.sleep(1.0)
            continue  # re-scan to find newly-revealed children

        # Real checkbox deselection.
        tap_point(session_id, x_pt, y_pt)
        time.sleep(0.8)
        share_button = find(session_id, "accessibility id", "request_screen_share_button")
        enabled = is_enabled(session_id, share_button) if share_button else None
        print(f"  deselected checkbox at pt=({x_pt:.0f},{y_pt:.0f}) - Share enabled={enabled}")
        results.append((len(results), enabled))
    else:
        print(f"FAIL: hit the {max_actions}-action safety cap without finishing", file=sys.stderr)
        save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc07_detect_FAILED.png")
        return None

    if not results:
        print("FAIL: found zero real checkboxes to deselect", file=sys.stderr)
        save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc07_detect_FAILED.png")
        return None

    return results


def main():
    if len(sys.argv) != 5:
        print(
            "usage: tc-07-ios-zero-selection.py <udid> <apple-team-id> <bundle-id> <out-dir>",
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
    overall_ok = True
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

        print(
            "QR displayed - waiting for consent screen "
            "(engagement + BLE connect happen passively on the Android side)..."
        )
        wait_for(session_id, "accessibility id", "request_screen_share_button", timeout=90)

        doc_header = wait_for(
            session_id, "accessibility id", "request_screen_requested_document_0", timeout=15
        )
        click(session_id, doc_header)
        time.sleep(1.5)  # let the expand animation settle

        print("Deselecting EVERY detected field (tracking Share's enabled state after each)...")
        results = deselect_all_and_track_share_state(session_id, out_dir)
        if results is None:
            print("FAIL: could not complete deselection", file=sys.stderr)
            sys.exit(1)

        # Boundary check: every deselection except the LAST should leave
        # Share enabled (>=1 field still selected); only the final one
        # (zero fields left) should show it disabled.
        for i, enabled in results[:-1]:
            if enabled is not True:
                print(f"FAIL: Share reported enabled={enabled!r} after row {i}, expected True (fields still remained selected)", file=sys.stderr)
                overall_ok = False
        last_index, last_enabled = results[-1]
        if last_enabled is not False:
            print(f"FAIL: Share reported enabled={last_enabled!r} with ZERO fields selected, expected False", file=sys.stderr)
            overall_ok = False
        else:
            print("Confirmed: Share button reports enabled=False with zero fields selected.")

        save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc07_zero_selected_screen.png")

        # Confirm it's genuinely inert, not just visually greyed out -
        # tap it anyway and confirm nothing happens (still on the
        # consent screen, no PIN screen, no navigation).
        share_button = find(session_id, "accessibility id", "request_screen_share_button")
        if share_button:
            click(session_id, share_button)
            time.sleep(2)
        pin_appeared = find(session_id, "accessibility id", "pin_text_field_0")
        if pin_appeared:
            print("FAIL: tapping the disabled Share button navigated to the PIN screen anyway", file=sys.stderr)
            overall_ok = False
            save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc07_UNEXPECTED_pin_screen.png")
        else:
            print("Confirmed: tapping the disabled Share button had no effect (still on consent screen).")

        # Clean exit rather than leaving the app stuck mid-flow.
        back_button = find(session_id, "accessibility id", "back_button")
        if back_button:
            click(session_id, back_button)
            time.sleep(1)

        with open(os.path.join(out_dir, "tc07_ios_outcome.txt"), "w") as f:
            f.write(("PASS" if overall_ok else "FAIL") + "\n")

        if overall_ok:
            print("PASS: iOS side confirmed Share disables at zero selection and stays inert.")
        else:
            print("FAIL: see above", file=sys.stderr)
            sys.exit(1)
    except Exception:
        try:
            save_screenshot_bytes(screenshot_raw(session_id), out_dir, "tc07_EXCEPTION_screen.png")
        except Exception as screenshot_err:
            print(f"(also failed to capture a failure screenshot: {screenshot_err})", file=sys.stderr)
        raise
    finally:
        _request("DELETE", f"/session/{session_id}")


if __name__ == "__main__":
    main()

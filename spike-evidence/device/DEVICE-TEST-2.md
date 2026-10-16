# DEVICE-TEST-2: real screens on the iPhone (M5b, commit e837f53a7)

Precondition: phone in LIGHT mode (dark mode is out of scope). App already onboarded; PIN unchanged.
The orchestrator launches the app with the deep link and a script. Everything is PROVISIONAL copy (sk).
Interpreted from the bundle: connecting, auth, verification-code, sending, success, error-expired (and the other outcome screens).
Hand-written: request only (PresRequestScreen). Check on every screen: layout (no clipping, spacing, safe areas), copy (complete, no placeholder text like a tagged "⟦...⟧"), theme (light, colours, app-bar), readability (size, contrast).

## 1. Happy path (script `happy`)
Sequence: connecting (about 0.8 s, may be missed) -> request -> [Continue] -> auth (may flash by) -> verification-code -> [Confirm] -> sending -> success -> [Close] -> Home.

| Screen | Expect | Look at |
|---|---|---|
| connecting | progress indicator + short hero text | centred, readable; spoken/live text |
| request | Sheet with app bar (menu, title, notifications); title; verifier "Spike Verifier" with Trusted badge, service "Spike demo service", reason; privacy link (external hint); intro "PID"; instructions; attribute list: given name Ján and family name Novák (mandatory, cannot be turned off), birth date 12. 4. 1985 and age over 18 (optional, toggles) | layout of the list, mandatory vs optional labels, toggle state visible; Continue and Reject buttons reachable. Select optional: tap the row/toggle (tap again deselects). Consent: tap Continue (id `continue`); Reject declines |
| auth | system auth / short wait | not stuck; fast with this script |
| verification-code | code 4711, portal link, Confirm | code large and legible, Confirm enabled when Ready |
| sending | progress, short text | layout, no buttons that mislead |
| success | success hero, Close | icon, copy, Close button |
| Home | Home returns, no flow remnants | no back stack leftovers |

## 2. Expired path (script `expired-at-request`)
Sequence: connecting -> error-expired -> [Close] -> Home.

| Screen | Expect | Look at |
|---|---|---|
| connecting | as above | as above |
| error-expired | error hero, message, Retry and Close (HELP link does not show: known gap, no source) | copy, error colour, buttons both visible |
| Home | after Close | returned cleanly |

## 3. Swipe in request / auth / sending (script `slow`)
Delays: connecting 4 s, request until you act, auth 12 s, sending 45 s. Do an edge swipe (from the left edge) on each screen, one launch per swipe or continuing the flow.

| Screen | Do | Expect | Look at |
|---|---|---|---|
| request | left-edge swipe | back policy per bundle (leaves the flow or asks); no crash | what happens, any flicker or stuck screen |
| auth (12 s) | left-edge swipe | back policy per bundle | same |
| sending (45 s) | left-edge swipe | back policy per bundle (likely blocked or cancel) | same |

Simulator reference (D1 drill): spike-evidence/M5b/d1-*-before/after-swipe.png in /Users/roman/code/eudi-kmp-spike. Compare.

## Results

| # | Script | Screen | OK / Issue | Layout | Copy | Theme | Readability | Notes |
|---|---|---|---|---|---|---|---|---|
| 1 | happy | connecting | | | | | | |
| 2 | happy | request | | | | | | |
| 3 | happy | auth | | | | | | |
| 4 | happy | verification-code | | | | | | |
| 5 | happy | sending | | | | | | |
| 6 | happy | success | | | | | | |
| 7 | expired | error-expired | | | | | | |
| 8 | slow | request swipe | | | | | | |
| 9 | slow | auth swipe | | | | | | |
| 10 | slow | sending swipe | | | | | | |

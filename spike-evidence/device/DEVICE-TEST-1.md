# DEVICE-TEST-1: spike flow on Roman's iPhone (iPhone Air, iOS 27.0.1)

Build: detached worktree `/Users/roman/code/eudi-kmp-device` at `d1ec06cf5`, Debug, bundle id `eu.compote.euidi.dev`, team D2YJRYD9TA.

**The flow screens are generic placeholders**: each shows the state name plus one button per event of that state. The visual screens come with M5b. This test checks plumbing (deep link, back handling, process death, switch), not looks.

Commands marked AGENT are run by the agent when you say "go". The UDID is 00008150-000C55591188401C (`$D` below).

## Before you start (once)
1. On the phone: Settings > General > VPN & Device Management > Developer App > "Apple Development: Roman Hauptvogel" > Trust. (Needed if the app refuses to open with "Untrusted Developer".)
2. Keep the phone unlocked and connected by cable. Disable Auto-Lock if you can.
3. The app is installed. It was launched once with `--ux-spike happy`, so the switch is ON (persisted).

## A. First run: onboarding and PIN
A1. Open the "EUDI" wallet app (dev). Expect: the welcome/intro screen with a "Continue" button. If you see the PIN unlock screen or Home instead, onboarding is already done: go to B.
A2. Tap Continue. Expect: "set PIN" screen with a 6-digit field.
A3. Enter 123456 (any 6 digits; remember them). Expect: a confirm-PIN screen.
A4. Enter the same PIN again. Expect: Home (dashboard). Nothing flow-related is visible; the switch does not change Home.

## B. Happy path via deep link
Link: `openid4vp://?client_id=spike&request_uri=https://example.invalid/r`
B1. Say "go". AGENT runs:
`xcrun devicectl device process launch --device $D --terminate-existing --payload-url 'openid4vp://?client_id=spike&request_uri=https://example.invalid/r' eu.compote.euidi.dev --ux-spike happy`
B2. Expect: possibly an iOS "Open in EUDI?" dialog: tap Open. Then the PIN unlock screen: enter your PIN.
B3. Expect: placeholder showing state `connecting` for about 1 second, then state `request` with buttons (CONTINUE, REJECT, SYS_EXPIRED, SYS_ERROR; TOGGLE_ITEM disabled).
B4. Tap CONTINUE. Expect: state `auth` (the fake auth resolves by itself in about 1 to 2 seconds), then state `verification-code`.
B5. Tap CONFIRM. Expect: state `sending`, then state `success` after a moment.
B6. Tap CLOSE. Expect: back on Home. No flow screen left.
(If the dialog or the link does nothing, tell the agent: it can re-run the command; fallback is to paste the link in Notes and tap it.)

## C. Expired script
C1. Say "go". AGENT runs the same command with `--ux-spike expired-at-request` instead of `happy`.
C2. Open (if asked), unlock. Expect: `connecting`, then state `error-expired` with buttons RETRY, CLOSE, HELP.
C3. Tap CLOSE. Expect: Home.

## D. Swipe-back (edge swipe from the left edge of the screen to the right, like iOS back)
Uses the `slow` script so each state stays long enough: `connecting` 4 s, `auth` 12 s, `sending` 45 s.
D0. Say "go". AGENT runs the link command with `--ux-spike slow`. Open, unlock.
D1. Wait for state `request` (about 4 s). Do an edge swipe from the very left edge. Expect: the flow is cancelled/declined (state `cancelled`, button CLOSE). Tap CLOSE: Home.
D2. Say "go" again (same command, `slow`). Reach `request`, tap CONTINUE, now in `auth` (12 s window). Edge swipe. Expect: back to state `request` (the auth is cancelled).
D3. From that `request` tap CONTINUE, wait for `verification-code`, tap CONFIRM. In `sending` (45 s window) edge swipe. Expect: BLOCKED: stays in `sending`, nothing happens. After about 45 s it moves to `success` on its own. Tap CLOSE: Home.
Note: previous agent runs could not synthesize an edge swipe on the simulator, so these are the first real swipe tests. If a swipe seems to do nothing in D1/D2, try again from the exact screen edge and note it.

## E. Kill in the middle and relaunch
E1. Say "go" (script `slow`). Reach `auth` (CONTINUE from `request`).
E2. Swipe up from the bottom to open the app switcher; swipe the app card up to kill it.
E3. Tap the app icon to relaunch. Expect: PIN unlock, then Home. No flow screen, nothing restored.
E4. Repeat E1 to E3 once in `request` and once in `sending` (after CONFIRM; `sending` has 45 s). Same expectation. Optionally screen-record.

## F. Switch off
F1. Say "go". AGENT runs: `xcrun devicectl device process launch --device $D --terminate-existing eu.compote.euidi.dev --ux-spike off`
F2. Expect: PIN unlock, Home as usual.
F3. Say "go". AGENT runs the link command without `--ux-spike` (switch is now off). Expect: unlock, then the app's normal presentation path (the normal request/loading screen or an error for the fake link, NOT a placeholder with a state name).
F4. To turn it back on later: launch with `--ux-spike happy` (or `slow`, `expired-at-request`). Scripts available: happy, slow, expired-at-request, expired-at-sending, untrusted, doc-missing, off.

## Results
| Step | Expected | Seen | ok / not ok |
|---|---|---|---|
| A1-A4 | intro, PIN twice, Home | | |
| B3 | connecting, then request | | |
| B4 | auth, then verification-code | | |
| B5 | sending, then success | | |
| B6 | Close returns to Home | | |
| C2 | connecting, then error-expired | | |
| C3 | Close returns to Home | | |
| D1 | swipe in request: cancelled | | |
| D2 | swipe in auth: back to request | | |
| D3 | swipe in sending: blocked, stays | | |
| E (request) | kill, relaunch: unlock, Home, nothing restored | | |
| E (auth) | same | | |
| E (sending) | same | | |
| F2 | off: Home as usual | | |
| F3 | off + link: normal path, no placeholder | | |

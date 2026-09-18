> ⚠️ **PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,**
> navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
> attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.

# Proximity flows (BLE, ISO 18013-5)

> ## ⚠️ READ FIRST: unresolved trust/safety finding (TC-13)
> The app can show **"successfully shared"** on iOS while the verifier
> received **zero bytes** of the response (confirmed, reproduced 2/2,
> both sides' evidence captured). Not yet fixed. Full writeup, repro
> steps, and root cause: **[`SECURITY-FINDING-TC-13.md`](SECURITY-FINDING-TC-13.md)**.

## Physical prerequisite: camera rig (one-time setup, not a script step)

TC-02 (and any future BLE proximity test) needs the physical Android
verifier device's camera aimed at the physical iPhone's screen location,
close enough and in-focus enough to read a QR code, for the entire test
run. This is **not** something any of these scripts do - it's a one-time
hardware setup:

- Mount/position both devices so the Android camera has a stable,
  focused, glare-free view of where the iPhone's screen will be.
- Confirmed live: the Android Verifier app's "Device Engagement" scan
  screen is fully passive - continuous per-frame ZXing decoding,
  confirmed via a live hierarchy dump showing the only clickable element
  on that screen is "Go Back". Once the rig is aligned, no further
  per-run camera interaction is needed - the QR just needs to be
  somewhere in frame when iOS displays it.
- Also confirmed live: once the QR is decoded, the BLE GATT connection
  and full data transfer happen automatically and fast (engagement to
  connected in well under a second; a full 9KB response received and
  parsed in ~1.4s once triggered) - no Android-side taps required, and a
  several-minute idle gap mid-session (while the human/iOS side caught
  up) did not disturb the open connection.

If engagement repeatedly fails ("The scanned QR code is not a supported
device engagement code", or the scanner just times out), check the rig's
physical alignment before suspecting a protocol/software issue. A
genuine software failure and a misaligned-camera failure look identical
from the error message alone - the way to tell them apart live is the
same continuous ZXing-decode logcat evidence used during TC-02's own
investigation (a `NotFoundException` loop that never resolves means the
camera isn't seeing anything decodable at all, which is a positioning
problem, not a protocol one).

## Why iOS is driven by Appium, not Maestro

Maestro's physical-iOS support requires manually patching its own
bundled driver Xcode project (a confirmed, open upstream packaging bug -
`MaestroDriverLib` missing from the packaged JAR) and still lacks
automatic port-forwarding to its on-device runner (a genuinely random
ephemeral port per launch, no `devicectl`-based tunnel). Appium's
XCUITest driver handles both natively (WDA binds a fixed, well-known
port; RemoteXPC tunneling is built in) and was confirmed working
end-to-end against the same physical iPhone, including real tap/type
interaction against the app's actual accessibility IDs. See the TC-37
investigation for the full comparison.

## Files

- `tc-02-ble-custom-attributes.yaml` - Android/Maestro half: create a
  custom PID request (all fields except Portrait), open the scanner,
  wait for the transfer to complete, dismiss.
- `tc-02-ios-share.py` - iOS/Appium half: trigger the BLE share screen,
  confirm the consent screen and PIN, dismiss. Talks to a local Appium
  server directly over its REST API (stdlib only, no
  appium-python-client dependency).
- `tc-02-verify-attributes.py` - reconstructs Android's wrapped
  multi-line `TransferManager` logcat JSON (a single grep match is
  always a truncated fragment, not the full message) and asserts the
  received PID document(s) match the requested custom subset.
- `tc-02-ble-custom-attributes.sh` - orchestrates all three: starts
  logcat capture, runs the Android flow in the background, runs the iOS
  script in the foreground, waits for both, then verifies.
- `tc-03-ble-full-pid.yaml` / `tc-03-ios-share.py` /
  `tc-03-verify-attributes.py` / `tc-03-ble-full-pid.sh` - the same
  structure as TC-02's four files, for a full PID request (no
  exclusions) instead of a custom subset. `tc-03-ios-share.py` is an
  intentional copy of TC-02's already-fixed script, not a shared
  module - confirmed live that full vs. custom PID changes nothing on
  the iOS side, and this project's existing `.maestro/*/tc-*.py`
  scripts are each self-contained already. Keep both files' fixes in
  sync if either needs another one.
- `tc-13-ble-mid-transfer-disconnect.yaml` / `tc-13-ios-interrupt-share.py`
  / `tc-13-verify-no-receipt.py` / `tc-13-ble-mid-transfer-disconnect.sh` -
  BLE connection interrupted mid-transfer (Android's own Bluetooth is
  disabled via adb right after iOS taps Share). **This is the test that
  found the unresolved trust/safety bug documented in
  [`SECURITY-FINDING-TC-13.md`](SECURITY-FINDING-TC-13.md) - read that
  file, not just this summary, before touching this flow.**
  `tc-13-verify-no-receipt.py` independently confirms Android's actual
  received state from its own logcat (same discipline as
  TC-02/03/04's verify scripts), which is what caught the mismatch
  against iOS's UI claim.
- `tc-14-ble-background-during-handshake.yaml` /
  `tc-14-ios-background-during-handshake.py` / `tc-14-verify-state.py` /
  `tc-14-ble-background-during-handshake.sh` - app backgrounded during
  BLE engagement/handshake, before the consent screen appears. Found a
  confirmed, reproducible (but intermittent) freeze bug - see below.

## Known bug — TC-14: intermittent freeze on foreground-return during BLE handshake (~25% reproduction rate)

**Status: confirmed, reproduced 1/4 runs. Not yet fixed. Reliability bug,
not a trust/safety finding** (unlike TC-13 - no false claim of success is
involved here; when this bug hits, iOS shows no progress at all rather
than a wrong "success," so it doesn't get a `SECURITY-FINDING-*.md` of
its own).

**Trigger:** background the iOS app (via Appium's `mobile: backgroundApp`
- a real backgrounding, not the force-kill TC-47 already covers) about
1.5s after the QR/engagement screen is displayed, i.e. during Android's
engagement → GATT-connect → request-send sequence and before iOS's
consent screen would normally appear. Hold backgrounded for 10s (TC-13's
live baseline showed that whole Android-side sequence completes in
~2s, so 10s is generous margin), then foreground again via `mobile:
activateApp` and observe both sides.

**Code-level confirmation this is a genuine interruption, not a no-op:**
this app declares no `UIBackgroundModes` key anywhere (checked
`Wallet/Wallet.plist` and both `.entitlements` files - no
`bluetooth-central`/`bluetooth-peripheral` background mode), so
CoreBluetooth activity has no license to continue while backgrounded.
Separately, `ProximityConnectionViewModel.swift`'s QR image is generated
exactly once, on the `.prepareQr` state (`onQRGeneration()`, called from
`subscribeToCoordinatorPublisher()`), with no code path that
regenerates it or re-checks connection state when the app returns to
foreground - the view model just sits subscribed to whatever the
coordinator's publisher does next. If Android completes engagement while
iOS is suspended, nothing here would necessarily refresh what's on
screen when iOS comes back.

**What was observed, run by run (identical procedure each time):**

| Run | iOS after foreground | Android's own screen/log |
|---|---|---|
| 1 | **Frozen** - pixel-identical QR screen before backgrounding and 32+ seconds after foregrounding. No error, no progress, no recovery within the observation window. | "Transfer Status / Requesting Full PID / **Status: Connected**" the whole time; logs show `engagement_received` → `connection_established` → `request_sent` all succeeded. |
| 2 | Recovered within a few seconds to a valid, correctly-populated consent screen. | Same "Connected" state; consistent with iOS's recovery. |
| 3 | Recovered the same way. | Same stages reached, plus a `Peer disconnected without proper session termination` error logged afterward - did not affect iOS's already-correct recovery. |
| 4 | Recovered the same way. | Same stages reached, no error logged. |

**1 failure in 4 identical attempts (~25%).** This is a real,
reproducible bug, not a one-off fluke - but it's intermittent, not
deterministic, so a single clean run (or even three) doesn't clear it.

**Evidence:** `tc14-forensics-20260827_132818/` (the failing run - iOS
screenshots `tc14_before_background.png`/`tc14_after_foreground.png`/
`tc14_final_state.png` are pixel-identical; Android screenshots and
`android_logcat.txt` show "Connected" throughout) and
`tc14-forensics-20260827_133024/` (a recovering run, for contrast - same
Android state, but iOS's `tc14_final_state.png` shows the proper
consent screen) are kept locally alongside these scripts as supporting
material for whoever picks this up.

**Worth investigating together someday (not now) - possible shared root
cause with TC-04:** TC-04's original manual-testing freeze bug (see
`.maestro/proximity/tc-04-*` and its own commit history) had the same
"sometimes freezes, sometimes doesn't, recovery-path-dependent"
character - never reproduced a second time manually, and only
confirmed via repeated automated attempts here too. Both bugs sit in
the same general area (this app's proximity/BLE session state machine
reacting to a resumed/re-entered UI state after some external
interruption), which makes a shared root cause in that state machine
plausible, but this has not been investigated - noting the connection
for whoever looks at either bug next, not concluding anything about a
common fix here.

## TC-06 — blocked: no way to construct an untrusted-verifier BLE presentation

**Status: investigated, blocked. No flow file exists for this and none
should be added under the current infrastructure** - same treatment as
TC-23/27/28: a real, thoroughly-verified limitation, not a gap to force
with custom fixture infrastructure.

**What TC-06 was meant to test:** present to a verifier whose
reader-authentication can't be cryptographically verified (untrusted/
unverified reader), confirming the app shows a warning rather than
silently proceeding - a scenario flagged in an earlier code
investigation but never live-tested.

**Corrected code path** (the earlier investigation had the method name
slightly wrong): `ProximityRequestViewModel`'s `.notSecuredRequest` case
calls `onTrustBlocked()` (`Modules/feature-common/Sources/UI/Request/
BaseRequestViewModel.swift:229-242`), not `onVerifierNotTrusted()` -
that method sets `isTrustBlockedAlertShowing = true`, which
`BaseRequestView.swift:68-74` renders as a native alert with title
`.presentationBlockedTitle` and message `.presentationBlockedMessage`.
This is reached when `ProximityInteractor.swift:152` sees
`error.isTrustBlocked == true`, defined in
`Modules/logic-core/Sources/Extension/Error+IssuerTrust.swift:24-38` -
true for a `WRPRCError`, a `RegistrationRefusedError`, or a
`WalletError` with code `.trustError`/`.invalidWrprc`. All three
originate deep inside the unvendored `EudiWalletKit`/`MdocSecurity18013`
SDKs (same as the SDK-trust-boundary problem already documented in
`SECURITY-FINDING-TC-13.md`) - there is no lever in this app's own code
to fire this condition on demand.

**Investigated whether the Android reference verifier
(`eu.europa.ec.euidi.verifier.dev`) could produce this from its own
side** (checked before assuming custom infrastructure was needed, per
instruction) - walked its entire UI live, physical device:

- **Settings screen** (Menu → Settings): `Retain data on device`, `Use
  L2CAP if available`, `Clear BLE Service Cache`, `BLE central client
  mode`, `BLE peripheral server mode`. Confirmed complete (scrolled to
  the `Cancel` button at the bottom, nothing further) - no reader-auth,
  certificate, or trust-related setting anywhere.
- **"Documents to request" screen**: only document-type selection
  (Full/Custom PID, Full/Custom mDL, Employee ID) - no per-request
  reader-auth toggle.
- **Custom PID field-selection screen**: scrolled through the entire
  field list end to end (`Family Name(s)` through `Trust Anchor` -
  itself just a requestable PID *claim* field, not a certificate
  control, matching the `trust_anchor` field already seen in TC-02/03's
  captured request payloads). No reader-authentication option exists
  here either.

**Conclusion:** nothing in the Android reference verifier's exposed UI
can make it present as an unverified/untrusted reader, and nothing in
this app's own code can trigger `.notSecuredRequest` independent of
what the (unvendored) SDK decides about the incoming request's reader
auth. Constructing this scenario would require modifying or rebuilding
one of the two apps (e.g. a verifier build with a self-signed or
stripped reader-auth certificate) - genuinely out of scope for this
black-box E2E rig, the same category of blocker as TC-23's Universal
Link investigation. Marked blocked; revisit only if a
reader-auth-stripped verifier build becomes available from elsewhere.

## Start from a known-clean document state on the physical device

Unlike the simulator flows, nothing here resets the iPhone's stored
documents between runs. Confirmed live (2026-08-25): after a run of
signing-config changes and PIN mixups on the same physical device (app
extension re-signed, wallet PIN changed), the stored PID documents
became unreadable - the app surfaced "Houston we have a problem! The
specified item could not be found in the keychain." mid-presentation,
after the BLE transfer itself had already completed successfully.
Deleting the stale documents and re-issuing fresh ones from the app's
normal UI resolved it immediately. If a run fails with a keychain error
at the consent/success screen rather than at the BLE transfer itself,
suspect stale documents left over from a prior signing/PIN change
before suspecting a script or protocol regression - start from freshly
issued documents, the same way the simulator flows start from a known
app state.

## Maestro's Android driver can hang on setup if started too soon after a prior run

Confirmed live (2026-08-26): a `maestro test` run against the same
Android device, started only ~90s after a previous run had finished,
hung indefinitely during its own driver connection setup - its
`maestro.log` never got past `Selected device...` to `Running flow...`,
meaning no flow command (not even the initial app launch) ever ran. The
same flow, given a longer gap, set up normally in ~7-8s every time.
`tc-02-ble-custom-attributes.sh` now polls for Maestro's own "Running
flow" log line (with a 60s timeout) before starting the iOS half,
instead of assuming Maestro is ready as soon as its process is
launched - the same preference this project already has for
`extendedWaitUntil` over fixed sleeps. If a run still fails with "did
not reach 'Running flow' within 60s", that's this same driver-setup
hang, not a rig/timing issue on the iOS side.

## "Full PID" vs "Custom PID" on the Verifier's request-creation screen

Confirmed live (2026-08-26): "Full PID" is a separate button right next
to "Custom PID" on the "Documents to request" screen, not a state of
the same button - tapping it selects every PID field with no per-field
checkbox subscreen, so getting back to Home needs only ONE "Done" tap
(TC-02's Custom PID flow needs two: one for the checkbox subscreen, one
for this screen). See `tc-03-ble-full-pid.yaml`'s header comment for
the exact button bounds this was confirmed against.

## This device's actual PID credential doesn't contain a "portrait" field

Confirmed live (2026-08-26), and worth knowing before writing any
verification that assumes a "full" request yields a visibly larger
response: TC-02's custom-PID-minus-portrait response and TC-03's
full-PID response contain the *exact same 9 fields* (family_name,
given_name, birth_date, expiry_date, issuing_country,
issuing_authority, nationality, issuance_date, place_of_birth) - this
device's issued PID credential simply never had a portrait (or several
other optional fields like sex/document_number) to begin with, so the
response is capped by the credential's actual content regardless of
what's requested. `tc-03-verify-attributes.py` therefore checks the
*outgoing request's* nameSpaces map for "portrait" (proving nothing was
excluded from what was asked for) rather than the response - a
response-side check would have failed even on a fully correct run,
for a reason that has nothing to do with either script.

## Known app behavior to expect, not treat as a bug

- The wallet holds multiple stored PID (MSO Mdoc) instances (this
  project's one-time-use PID batch design). A "Custom PID" or "Full PID"
  request matches *all* of them - one consent card per instance, and all
  are shared together in a single presentation. `tc-02-verify-attributes.py`
  asserts "at least one document, each matching the expected attribute
  subset," not an exact document count, deliberately - forcing the
  wallet down to a single stored PID first would mask this real,
  intended behavior rather than test against it.
- On the Verifier's "Custom PID request" screen, every field's checkbox
  is a separate clickable element from its text label (confirmed via a
  live hierarchy dump - the label `TextView`s are `clickable=false`).
  `tapOn: "<Field Name>"` matches only the label and silently does
  nothing to the checkbox; toggling a specific field needs a point tap
  on the checkbox itself.

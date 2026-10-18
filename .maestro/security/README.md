> ⚠️ **PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,**
> navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
> attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.

# Security flows

## TC-37 — complete QES flow (enroll for remote qualified signing + sign a
document via CSC v2 QTSP): not automatable on simulator; no flow file
exists and none should be added under simulator automation.

TC-37 was proposed as: verify the app can enroll for remote qualified
signing (CSC v2 / QTSP) and sign a document end-to-end. Investigated at
the code level (app-side interactor/view model, plus the vendored
`eudi-lib-ios-rqes-ui`/`-kit`/`-csc-swift` SPM packages, read directly
from their DerivedData `SourcePackages/checkouts` sources) and partially
live on simulator, before concluding this can't be automated here.

**Entry point (confirmed via code)**: Home tab → "Sign document" tile
(behind a one-time `alertSignDocumentsSafely` confirmation) →
`SignDocumentView` → a native `.fileImporter` restricted to
`allowedContentTypes: [.pdf]` (`SignDocumentView.swift`). There is no
bundled/sample document to sign - the user must already have a real PDF
sitting somewhere the system file picker can see (iCloud Drive / On My
iPhone / Shared). `SignDocumentViewModel.onFileSelection` reads the
picked file and hands its URL to `DocumentSignInteractor
.initiateSigning(url:)`, which drives the vendored `EudiRQESUi` SDK for
everything from there on.

**Enrollment is not a separate one-time step - it's redone on every
signing attempt.** `EudiRQESUi.initiate()` always resets its internal
state machine to `.initial` and clears cached credentials/tokens on each
call (`resetCache()`); no token/credential persistence of any kind was
found anywhere in the three RQES packages or the app (no Keychain, no
UserDefaults). So each attempt is expected to redo, in full:
- **Service authorization** - OAuth to the QTSP account
  (`ServiceSelectionViewModel.openAuthorization()`)
- **Credential authorization** - a second, separate OAuth/consent
  specific to the signing operation
  (`CredentialSelectionViewModel.openAuthorization()`, CSC v2's
  per-signature authorization step)

**Both OAuth legs leave the app entirely - a different, more fragile
mechanism than TC-22's.** Both view models call `UIApplication.shared
.openURLIfPossible(authorizationUrl)`, which opens real Safari, not an
in-app `ASWebAuthenticationSession`/`WKWebView` (TC-22's mechanism). The
redirect back (`rqes://oauth/callback?code=...`) is handled by the
app's existing `DeepLinkController`, which has a dedicated `.rqes` case
(extracts the `code` query param, calls `EudiRQESUi.instance()
.resume(...)`) - so this is a genuine cross-app round trip (app → Safari
→ back to app via custom scheme + the same one-time "Open in EUDI
Wallet?" system dialog TC-22 already handles), happening **twice** per
signing attempt (once for service authorization, once for credential
authorization).

**Strong, but live-unconfirmed, hypothesis: the QTSP itself loops back
into the wallet via OpenID4VP.** The configured DEV QTSP is
`https://walletcentric.signer.dev.eudiw.dev/csc/v2` ("Wallet-Centric").
Probing it live, its `.well-known/openid-configuration` doesn't return
JSON - it serves an HTML page titled "rQES Relying Party". That's
consistent with a relying-party web app that authenticates the user by
requesting a wallet presentation (deep-linking back into this same app
for an OpenID4VP share of the PID, then continuing the OAuth flow) - the
same class of mechanism already proven automatable in TC-22/24/25/26.
This was **not** confirmed end-to-end live - the flow never got that far
(see below) - so treat it as a plausible hypothesis, not a demonstrated
fact.

**Why this can't be automated here - the actual blocker, hit live, at
the very first step**: getting a real PDF into the file picker in the
first place has no working `simctl`/Maestro path.
- The Files app's "On My iPhone" is **not** backed by
  `com.apple.DocumentsApp`'s own sandbox `Documents` folder - a test PDF
  copied there directly did not appear under "On My iPhone" (confirmed
  live via Files-app screenshots taken through Maestro).
- The real backing store is the `group.com.apple.FileProvider
  .LocalStorage` App Group's `File Provider Storage` folder - but this
  uses Apple's File Provider extension architecture (its own
  domain/metadata database, requiring proper registration and
  enumerator signaling), not a plain directory listing. That folder was
  empty, and a raw file dropped into it was not picked up - consistent
  with File Provider's design, not something a plain file copy,
  `xcrun simctl`, or Maestro can drive.
- There is no `simctl` equivalent of `addmedia` (which works for Photos)
  for generic Files-app documents.
- iCloud Drive as an alternate seeding route was considered but not
  pursued - it would require a signed-in iCloud account on the
  simulator, which is its own significant setup problem for local/CI use
  and not obviously more tractable.

**Conclusion**: classified the same way as TC-53 - a real, code-confirmed
gap in what's automatable on a simulator, blocking at the very first
step of the flow (before enrollment or signing themselves can even be
reached), not a documentation-only limitation. No flow file exists for
it and none should be added under simulator automation; this needs a
real device (with real Files-app content and, likely, a real QTSP
account) to exercise end-to-end.

**Update - confirmed automatable on a physical device.** This conclusion
still holds for simulator/Maestro specifically, but does not hold in
general: on a physical device with Appium (the same infrastructure
already proven for TC-02's BLE work in `proximity/`), the file-picker
blocker above does not exist - iCloud Drive syncs a seeded PDF straight
into the device's Files picker - and the full flow (including both CSC v2
OAuth legs, confirmed to be same-device OpenID4VP round trips rather than
real external web content, exactly as hypothesized above) runs
end-to-end up to a live dev-QTSP backend limitation. See
`.maestro/qes/README.md` for the full writeup, the confirmed findings,
and `tc-37-qes-signing.py` for the actual flow. That directory also
documents a real, separately-tracked crash (uncaught C++ exception in
PoDoFo, SIGABRT) found incidentally while building this - fixture-
specific, not a general defect, but worth its own fix.

TC-38 (QTSP-side cancellation) and TC-39 (signature/timestamp
verification) were investigated as follow-ups once TC-37 itself was
confirmed automatable. Both were answered directly from the same vendored
SDK code rather than left blocked: TC-38 turned out not to be a
UI-distinguishable, testable scenario at all (every remote-call failure
mode collapses into the same generic error TC-37 already tolerates), and
TC-39 surfaced a genuine, code-confirmed product gap (no in-app way to
verify a signed document's timestamp or validity exists). See
`.maestro/qes/README.md`'s own TC-38 and TC-39 sections for the full
writeups.

## TC-41 — biometry failure → PIN fallback on real hardware: confirmed working, blocked from automated testing by Apple's own security design

Investigated on the physical device via Appium (same tooling as TC-02/
TC-37/TC-52) specifically to observe real Face ID hardware behavior,
not the simulator's `simctl`-injected approximation. TC-40's own
`BiometryViewModel.viewState.isLockedOut` polling-cache finding
(documented in `CLAUDE.md`) came out of the same view model this
investigation covers.

**Auto-trigger behavior - confirmed via code, then live, matching
exactly.** `BiometryViewModel.initialize()` fires biometry automatically
about 250ms after the screen appears - no tap required - whenever
`shouldInitializeBiometricOnCreate` and `areBiometricsEnabled` are both
true:

```swift
if viewState.config.shouldInitializeBiometricOnCreate, viewState.areBiometricsEnabled, !viewState.autoBiometryInitiated {
  setState { $0.copy(autoBiometryInitiated: true) }
  DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(AUTO_VERIFY_ON_APPEAR_DELAY)) {
    self.onBiometry()
  }
}
```

It is not two separate screens (biometry, then a PIN fallback screen) -
`BiometryView`/`BiometryViewContainer` render a single screen with both
at once: the PIN field is present the whole time, just not
auto-focused while biometrics are enabled (`canFocus:
.constant(!viewState.areBiometricsEnabled && !viewState.isLockedOut)`
in `PinTextFieldView`, which only gates the *automatic* on-appear
focus - the field's `.focused($focused)` binding is applied
unconditionally, so a manual tap plausibly still works, though this
specific detail was not confirmed live since no real failure state was
reached, see below). Confirmed live three separate times on this device:
enabling "Authenticate with biometrics" in the app's own Settings screen,
then relaunching, then relaunching again, then even just toggling the
setting back off - every single one triggered the real system Face ID
scan automatically, once caught directly mid-animation (the actual black
rounded-square Face ID glyph overlay, not app UI), and it matched
successfully within about a second each time.

**Both realistic ways to force a biometry *failure* for testing are
confirmed blocked - not assumed, checked directly against this
device:**

- **Settings → Face ID & Passcode → "Use Face ID for" (the real,
  documented per-app iOS toggle)** exists exactly as expected, but
  tapping into it immediately demands the **device's own unlock
  passcode** ("Enter the passcode you use to unlock this iPhone") -
  confirmed live. This is a completely different credential from the
  wallet app's own test PIN, and entering a real device passcode via
  automation is a credential-handling boundary this investigation
  doesn't cross regardless of whether the passcode is known. Backed out
  without entering anything.
- **Physically obstructing the sensor** is the only other real
  mechanism, and there is no software/API path to it: Appium has no
  actuator that can cover a camera. Every attempt to reach the biometry
  screen during this investigation (cold relaunch, terminate+relaunch,
  even the Settings toggle-off action itself) completed as a real,
  successful Face ID match within about a second, because a face was in
  front of the sensor throughout - there is no code-level lever to
  prevent that.

**Conclusion: this is not a gap to work around, it's the intended
design.** Unlike the simulator, which exposes `simctl`-level hooks to
inject a matching or non-matching biometry result on demand
specifically *for* automated testing, real hardware deliberately
exposes no equivalent - forcing a real device's Face ID/Touch ID to
report failure requires either the device owner's own passcode or a
human physically defeating the sensor in real time, and neither is
something software-only automation should or can do. Classified the
same way as TC-52: a real, confirmed-working feature, blocked from
automated coverage by something entirely outside this app's (or this
investigation's) control - not a documentation-only limitation, and not
something to keep pushing on. No flow file exists for it and none
should be added; a human tester following a manual procedure (enable
biometry, relaunch, physically cover the sensor on cue, confirm the PIN
field becomes usable) remains the only way to exercise this path.

**Re-offer finding, independent of the above and fully confirmed
live**: biometry does **not** get disabled after any particular
attempt or event - it stays enabled and auto-triggers successfully
indefinitely. Confirmed across four full cycles today (initial enable,
a cold app resume, a full terminate+relaunch, and a second
terminate+relaunch), every one landing straight on Home with no PIN
screen ever shown. This matches `KeyChainControllerImpl
.clearKeyChainBiometry()` (called from `SystemBiometryControllerImpl
.requestBiometricUnlock()`'s catch block on any failure), which only
removes one specific Keychain item (`eu.europa.ec.euidi.biometric
.access`) - it does not touch the persisted `biometryEnabled`
preference that `BiometryInteractor.isBiometryEnabled()` reads, and the
next `requestBiometricUnlock()` call recreates that Keychain item fresh
via `setBiometricKey()` regardless. So a single failure (of any kind)
would not permanently fall back to PIN-only for the rest of the
session or on future launches - the next attempt, whenever it happens,
gets a clean biometry prompt again.

Two reusable Appium/XCUITest notes from this investigation, in the same
spirit as TC-52's Safari-driving notes above:

- **A Settings list row's combined label can contain a non-obvious
  literal character.** Confirmed via a hex dump of raw `/source` output:
  the space between "Face" and "ID" in the row labeled "Use Face ID
  for, Face ID & Passcode" is `U+00A0` (non-breaking space), not the
  ordinary `U+0020` a screenshot or a plain-text `grep` would assume.
  Matching or searching this text without accounting for that can
  silently miss an element a screenshot clearly shows on screen.
- **Building an Appium `-ios predicate string` payload containing a
  literal `&` via raw `curl -d '...'` in bash was unreliable** in this
  same investigation - several otherwise-correctly-formed predicates
  failed outright with no useful error, and switching to Python
  (`json.dumps` + `urllib.request`) instead of a bash-quoted curl
  one-liner made them succeed immediately. Worth trying as a first fix
  whenever a predicate value containing `&`, `"`, or similar characters
  behaves inexplicably from bash - though whether the two notes above
  are the same underlying cause or two separate issues was not
  disentangled here.

## TC-49 — device clock skew during PID issuance (DPoP freshness): fully tested, no vulnerability reproduced

Investigated whether iOS has an analog to a Client Attestation JWT
timing bug found early in this project on Android (a race caused by a
device clock/`nbf` mismatch). Unlike TC-41/TC-52 above, this is not a
"blocked, couldn't test" result - the scenario was fully exercised live
on the physical device and came back negative.

**Code-level: the exact Android bug class isn't reachable on iOS,
because the feature it needs is unused.** The structural analog -
OAuth 2.0 Attestation-Based Client Authentication, where a client-held
JWT's `iat`/`exp` are stamped from the device's own clock
(`ClientAttestationPoPBuilder` in the vendored `eudi-lib-ios-openid4vci-
swift`) and checked against the server's clock - is dead code in this
app: `WalletKitConfig.swift`'s `issuersConfig` never constructs
`Client.attested(...)` or supplies a `clientAttestationPoPBuilder`;
this app authenticates as a plain OAuth public client. The repository-
level WUA/Key Attestation flow (`WalletAttestationRepositoryImpl`,
`WalletKitAttestationProviderImpl`) doesn't have a local-clock-
dependent claim either - it just relays a JWK/nonce to the wallet-
provider backend and forwards back whatever attestation JWT it
returns, with no local generation or validation of `nbf`/`exp`/`iat`
against device time.

**The actual live analog is DPoP.** Every issuer config in
`WalletKitConfig.swift` sets `requireDpop: true`, and
`DPoPConstructor.jwt()` (same vendored package) stamps every DPoP
proof's `iat` from `Date()` - the device's own clock - on every
PAR/token/credential request during real issuance. This is the one
code path in this app that's actually shaped like Android's bug
(a client-generated freshness claim, checked by the server's clock),
so it's what TC-49 targeted.

**Confirmed live: a real device build with a personal signing
override, on the physical device.** Reused the same infrastructure as
TC-37/41/52 (Appium/XCUITest). `project.pbxproj`'s Debug Dev
configuration already carried an uncommitted personal-signing override
from earlier real-device work (`eu.europa.ec.euidi.dev.mcekan` /
team `LYTPZGMU55`, `CODE_SIGN_STYLE = Automatic`) - built and installed
via `xcodebuild`/`xcrun devicectl` with no changes needed.

**Main finding: at ~5 minutes of forward clock skew, PID issuance
completed identically to the unskewed baseline - no error, no hang, no
rejection.** Skewed the device's clock forward via Settings ("Set
Automatically" off, then the Date & Time picker wheels), confirmed the
actual offset independently via `ideviceinfo -k TimeIntervalSince1970`
against the host Mac's own clock (not just the Settings UI's display),
then drove the full PID-issuance flow by hand (PIN → Documents → + →
PID Combined → FormEU identity-proofing form → Review & Send →
Authorize) - the same PAR/token/credential exchange that carries the
DPoP proofs above - while the skew was active throughout. Result was
byte-for-byte the same "successfully added to your wallet" outcome as
a clean-clock baseline run performed immediately before it, and the
app was left in a normal, fully usable state (Documents list showing
all four issued credentials, nothing stuck or corrupted). Deliberately
did not escalate to a larger (tens-of-minutes) skew: past a certain
point the dominant variable becomes TLS certificate validity, not DPoP
freshness, which would be testing something else entirely. A 5-minute
skew already exceeds typical DPoP `iat` freshness tolerances (commonly
well under a minute) by a wide enough margin that this is a meaningful
negative result, not an under-powered one.

**Three side findings surfaced while building this, independent of the
main result:**

- **Switching to `com.apple.Preferences` via a second Appium session and
  back re-locked the wallet and discarded in-progress flow state - but
  this is very unlikely to be a real backgrounding-duration re-lock
  feature, and re-verifying it against `tc-42-background-relock.yaml`
  showed why.** The original plan was to skew the clock via a second
  Appium session bound to `com.apple.Preferences` right before tapping
  "Authorize" on an already-filled-in Review & Send screen. Confirmed
  live that this doesn't work: switching to Settings and back landed on
  the PIN-entry "Welcome back!" screen, not the in-progress flow -
  re-entering the PIN dropped straight to Home, with the entire
  uncommitted PID issuance (external ASWebAuthenticationSession state
  and all) gone. At the time this read as "backgrounding always
  re-locks," but a follow-up investigation (reading every
  `onChange(of: scenePhase)` site in the codebase - `Wallet/
  Application.swift`, `HomeTabViewModel`, `DashboardViewModel` - the
  only three that exist) found none of them make a lock/navigation
  decision at all; the PIN screen can only reappear via a genuine
  process restart. `tc-42-background-relock.yaml` independently confirms
  a real Home-button background (process alive, `launchApp: {stopApp:
  false}`) does NOT re-lock - consistent with that code reading. The far
  more likely explanation for what happened here: Appium/XCUITest's
  default session-creation behavior terminates an already-running app
  before relaunching it (even with `noReset: true`, which only preserves
  on-disk storage, not the live process) - i.e., this hit the same
  process-kill mechanism TC-47 already documents, just reached
  implicitly via session creation rather than an explicit `simctl
  terminate`. Not re-verified live with an actual PID check (no physical
  device connected during that follow-up), so treat as the well-
  supported explanation, not a proven certainty. Either way, the
  practical constraint stands: any *future* test that needs the wallet
  to hold an active flow state while something else gets driven in
  Settings/another app cannot rely on a plain app-switch surviving -
  skew/toggle whatever's needed *before* starting the flow instead (this
  test's own fix), or find a way to change it without leaving the app.
  See `CLAUDE.md`'s Security observations for the fuller reconciliation.
- **A real-device uninstall+reinstall genuinely clears Keychain state
  - unlike the Simulator, where TC-32 (`tc-32-reinstall-wipe.sh`)
  already proved a plain uninstall leaves it untouched.** Hit this
  live: the personal-signing bundle id (`eu.europa.ec.euidi.dev
  .mcekan`) already had a PIN set from earlier real-device sessions,
  and a plain `devicectl` reinstall was enough to land back on a
  fresh, first-run "Welcome to your wallet" PIN-setup screen - no
  Keychain-clearing workaround needed on real hardware, in contrast to
  the Simulator-specific behavior TC-32 documents.
- **Manually changing the device's clock briefly knocked it off the
  USB/`lockdownd` pairing.** Confirmed live and reproducible: right
  after applying the skew, `idevice_id -l` stopped listing the device
  and the Appium/WDA tunnel failed with "socket hang up" / "Device...
  not found" for about 10 seconds before self-recovering with no
  further action taken. Plausible cause is that trust tickets for the
  developer/remote-service pairing are validated against the device's
  own (now-jumped) clock. Not an app bug - a tooling/OS-level side
  effect worth expecting if any future test manually sets this
  device's clock via Appium.

## TC-35/TC-36 — document revocation detection (poll) and presentation of a revoked document: not automatable, blocked by a confirmed issuer-side dead end before either behavior is reachable

TC-35 (does `RevocationWorkManager`'s poll surface a revoked-document
modal/badge as designed) and TC-36 (what happens when a revoked
document is presented to a verifier) both require an actual revoked
document to test against. Neither could be reached this session: the
revocation trigger itself - which lives entirely outside the app, on
the DEV issuer's own self-service portal - fails before "Authorize
Revocation" is ever submitted.

**No revocation trigger exists anywhere in the app's own code.**
Confirmed by reading `WalletKitController`'s full revocation surface
(`isDocumentRevoked`, `fetchRevokedDocuments`, `storeRevokedDocuments`,
`removeRevokedDocument`, `getDocumentStatus`) - all read/local-storage
operations against `RevocationWorkManagerImpl`'s own poll results (see
`CLAUDE.md`), none of them a revoke call. The actual revoke action is
the issuer's own self-service portal,
`https://backend.dev.issuer.eudiw.dev/revocation/revocation_choice`
(linked from `ec.dev.issuer.eudiw.dev`'s homepage, the same DEV-issuer
host TC-01 uses), whose own `revocation_guide.html` documents the
intended flow: select credential(s) → Submit → Request screen →
deep-link into the wallet → OpenID4VP share (proof of possession) →
PIN → "Authorize Revocation."

**Confirmed live, twice independently, for both issued PID formats:
the wallet's own "Data sharing request" screen fails closed with a
claim-resolution error before the share can even be reviewed.**

- PID (MSO Mdoc): `Claim not found: eu.europa.ec.eudi.pid.1/family_name_birth`
- PID (SD-JWT VC): `Claim not found: address`

Both failures happened after already clearing the portal's checkbox
selection, Submit, the Request screen, the "Open in EUDI Wallet?"
system deep-link dialog, and the resulting app relaunch (PIN
re-entry, "Welcome back!" - the same implicit process-kill-on-deep-
link already documented for TC-47/TC-49, reconfirmed here). The app
itself behaves safely - a clear, non-crashing error screen, not a
hang or a silent pass-through - but the flow never reaches
Share/PIN/Authorize, so no revoke call is ever actually made. A
follow-up check of the Documents tab confirmed all three credentials
(mDL, both PIDs) remained normal, "Valid until 01 Dec 2026," no
revoked badge.

**Suspected root cause, well-supported but not proven:** TC-01's mock
identity-proofing form (FormEU) only ever fills Family Name, Given
Name, Nationality, and Place of Birth - never an address or a "birth
family name" - so these optional PID claims are plausibly absent from
the issued credential regardless of format, while the revocation
portal's OpenID4VP request appears to ask for them unconditionally.
The mdoc-side field name is independently suspicious on its own
terms: the correct ISO 18013-5/ARF PID attribute is
`birth_family_name`, not `family_name_birth` - the reversed name
reads like a genuine typo in the revocation demo's own request
construction, separate from whether the claim value exists at all.

**Conclusion: classified the same way as TC-37** - a confirmed,
deterministic dead end on infrastructure outside this app's control
(the issuer's own revocation portal), not an app bug, blocking the
flow before either TC-35 or TC-36's actual behavior-under-test can be
reached at all. This lines up with the maintainers' own "TODO finish
revocation pages" caveat on that portal. No flow file exists for
either test and none should be added until the issuer side is fixed
(or a PID reissued with a fuller claim set is confirmed to get past
this - untested whether FormEU's mock form even exposes an address
field).

**Minor tooling note, not app-related:** the revocation portal's
credential-selection checkboxes don't toggle via Maestro's normal
`tapOn` (by text or `leftOf`) - a screen-hierarchy dump showed the tap
only focuses the `<input>` (`focused: true`, `checked: false`); only a
raw point-coordinate tap on the checkbox's own bounds actually toggled
it. Plausibly a synthetic-XCUITest-event mismatch specific to this
custom-styled web checkbox, not confirmed against a real finger tap.
Worth encoding as a point-tap in any future flow attempt against this
portal.

**Unverified - never observed live, flagged from code reading only
(needs a working revocation trigger to actually check):**
`DashboardViewModel.handleRevocationNotification(for:)` only shows the
revoked-document modal when `!viewState.isPaused`
(`DashboardViewModel.swift:97`), and the `.active`-phase handler,
`onResume()`, only clears `isPaused` - it does not re-check
`fetchRevokedDocuments()` or otherwise re-surface anything already
stored as revoked (`DashboardViewModel.swift:134-136`). If
`RevocationWorkManager`'s poll lands while the app is backgrounded,
the `RevocationDashboard` notification would fire while `isPaused` is
`true`, get dropped by this guard, and never re-fire on foreground -
a plausible gap where a real revocation could go unsurfaced to the
user. This is inferred purely from reading the two methods together;
it was never exercised against an actual revocation, since none of
this session's attempts got far enough to produce one. See
`CLAUDE.md`'s Security observations for the same note.

## TC-21 — weak/stalled (not fully absent) connection during PID issuance: confirmed clean timeout with a clear error and retry, no hang

Unlike TC-46 (a clean, total network loss during OpenID4VP presentation),
TC-21 asks what happens when a connection is degraded but not fully
severed - specifically, a connection that stalls mid-response rather
than dropping outright. Confirmed live, on the simulator: **PID issuance
times out cleanly with an explicit error screen and a retry button when
a real HTTPS connection to the issuer goes silent for roughly 65-90s
mid-response** - it does not hang indefinitely and does not silently
proceed with partial/stale data.

**No Network Link Conditioner install and no passwordless sudo exist on
this Mac**, so `pfctl`/`dnctl`-based throttling (root-required) is a
dead end here, the same class of blocker CLAUDE.md already documents for
`sudo xcode-select`. The workable, root-free alternative: since the iOS
Simulator shares the host Mac's own network stack rather than a
virtualized radio, `networksetup -setwebproxy`/`-setsecurewebproxy`
(no sudo required for the current user) can point the Mac's own Wi-Fi
service at a local proxy, and Simulator traffic follows it - the same
mechanism tools like Charles Proxy rely on for Simulator MITM. This
proxy modification is **system-wide**, affecting every process on the
Mac, not just the simulator - it was applied only with explicit
one-off user approval each time, always wrapped in a `trap ... EXIT`-
guarded restore script, and verified via `networksetup -getwebproxy`/
`-getsecurewebproxy Wi-Fi` against the exact pre-change values
immediately afterward on every run.

**A small root-free Python `asyncio` relay (kept in scratch, never
committed) did the actual throttling**: an HTTP `CONNECT`-tunneling
proxy that relays raw bytes for HTTPS (no TLS termination or cert
install needed) with deliberately injected delay.

**Methodology lesson, reusable for any future weak-connection testing
in this project: don't stall the first byte of a connection.** The
first version injected a single long stall on literally the first
server-to-client byte of each relayed connection. That byte is the TLS
`ServerHello`, not application data - stalling it broke the handshake
itself rather than simulating a slow-but-working connection, confirmed
via a plain `curl -x` sanity check returning an SSL connect error after
the full stall duration instead of a delayed `200`. Root cause: the
real remote server's own idle/keep-alive timeout (commonly ~60s) closed
the underlying TCP connection while the client was left waiting on a
handshake message that never arrived in time, so by the time the
proxy finally forwarded it, the connection was already dead. **Fix:**
let a clearance amount of bytes (8192 was used here) flow through
untouched first - enough to clear the handshake and get into the real
HTTP response - before injecting the stall on the first read past that
threshold. Re-verified with the same `curl -x` sanity check: a clean
`200` after the full ~83s delay. Any future attempt at this kind of
test should budget for this fix from the start rather than
rediscovering it.

**A separate, shorter-stall profile tried first (many short ~2-5s
stalls with small 512-byte chunking, meant to approximate a generic
"3G/Edge/very bad network" throughput profile) never actually exercised
any timeout path at all** - the issuer document-catalog fetch sat on a
loading skeleton for 150s+ without erroring, while the proxy log
confirmed real bytes kept trickling in the whole time (connection alive
and progressing, just extremely slow). This app has no custom
`timeoutIntervalForRequest`/`URLSessionConfiguration` anywhere in its
own Swift sources (confirmed by a full-repo grep), so it relies on
URLSession's default 60s timeout, which is an *inactivity* timeout that
resets on every byte received - a profile whose longest single gap
(5s) never approaches that threshold cannot distinguish "hangs forever"
from "correctly waiting on legitimate slow progress." This is why the
single-long-stall profile (crossing the 60s inactivity gap in one
deliberate freeze) was the version that actually answered the
question, not the many-short-stalls one.

**The confirmed result itself**: with the handshake-safe single-stall
profile (65-90s, injected after 8192B of real response data had
already flowed for that connection), the "Choose from list" screen's
issuer-catalog fetch produced a clean, native-looking error screen -
*"Houston we have a problem! Failed to resolve issuer metadata: The
operation couldn't be completed. (OpenID4VCI.CredentialIssuerMetadataError
error 0.)"* - with a "Try again" button, appearing roughly 90-110s
after the request began. No stuck spinner, no crash, no silent
proceed-with-partial-data.

**Not automated as a committed Maestro flow, deliberately**: the
throttling mechanism requires a system-wide `networksetup` proxy change
on the host Mac (affecting every process, not just the simulator, and
needing explicit interactive approval each time it was applied this
session) - not something safe or appropriate to wire into routine local
or CI runs the way TC-46's flow files are. This is reported as a
confirmed, positive finding via manual/scripted investigation rather
than a reusable regression flow.

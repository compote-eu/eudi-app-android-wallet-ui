> ⚠️ **PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,**
> navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
> attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.

# QES / remote-qualified-signing flows (physical device only)

## TC-37/38/39 — confirmed automatable on physical device via Appium

`.maestro/security/README.md` originally concluded TC-37 (enroll for
remote qualified signing + sign a document via CSC v2 QTSP) was **not
automatable at all** - the file-picker seeding blocker on simulator. That
conclusion still stands **for simulator/Maestro**. On a physical device
with Appium (the same infrastructure already proven in `proximity/` for
TC-02's BLE work - see that directory's README for why Appium rather than
Maestro drives physical iOS here), the picker blocker does not exist and
the full flow is automatable end-to-end, up to a live external-backend
limitation described below. `tc-37-qes-signing.py` is that flow.

### Fixture seeding (one-time setup, not a script step)

There is no bundled/sample document to sign, and the app declares neither
`UIFileSharingEnabled` nor `LSSupportsOpeningDocumentsInPlace`, so Xcode's
Devices-window file-sharing panel is not an option either. `devicectl
device copy to` was also checked and ruled out - its `--domain-type` only
supports `temporary, appDataContainer, appGroupDataContainer,
systemCrashLogs`, none of which reach the system Files app / `.fileImporter`
picker.

What does work, confirmed live: drop a PDF into the Mac's own iCloud Drive
folder (same Apple ID as the test device) -
`~/Library/Mobile Documents/com~apple~CloudDocs/`. It syncs to the
device's Files app / `.fileImporter` picker automatically, no device-side
interaction at all, confirmed appearing and selectable within ~2 minutes.
This is a permanent, reusable fixture once seeded - not a per-run step.

**The PDF must be well-formed - a hand-crafted/malformed one will crash
the app instead of failing gracefully.** See the dedicated crash finding
below. A quick way to produce a genuinely well-formed one-page PDF
without needing any authoring tool:

```
printf "some test content\n" > fixture.txt
cupsfilter fixture.txt > fixture.pdf   # macOS's built-in CUPS text-to-PDF filter
cp fixture.pdf ~/Library/Mobile\ Documents/com~apple~CloudDocs/
```

The file picker's "Recents" tab pulls from the **device's real iCloud
Drive**, not a sandboxed test area - on a personal Apple ID that means
real personal documents are visible there too. `tc-37-qes-signing.py`
avoids ever browsing Recents; it goes through the picker's Search field
and matches only the fixture's own filename, so it never touches or lists
anything else. Worth keeping in mind if extending this script.

### The "OAuth" steps are not real web content - Appium never needs to drive Safari

Confirmed live, twice independently: every leg that looks like it's
leaving the app is actually a same-device round trip. Tapping "Proceed"
(or, later, the second "Proceed" after certificate selection) opens what
Safari treats as a request to launch a URL - and Safari immediately
intercepts it with a system "Open in 'EUDI Wallet'?" dialog, before any
page ever renders. Tapping "Open" lands back in this app's own native UI,
showing a "Data sharing request" screen (an OpenID4VP presentation
request from "Verifier Signer dev") asking to share a PID. This happens
**twice** per full signing attempt - once for Service authorization, once
for Credential authorization, matching the two separate OAuth legs CSC v2
defines - each one a `Data sharing request -> Share -> Confirm-request PIN
-> Done` native sequence, then another "Open in 'EUDI Wallet'?" bounce
back to wherever the flow left off. Four such system-alert bounces total
across one full attempt.

This directly confirms the hypothesis floated in `security/README.md`'s
original TC-37 writeup: the DEV QTSP (`walletcentric.signer.dev.eudiw.dev`)
is a wallet-centric relying party that authenticates via an OpenID4VP
share back into this same wallet, not a real third-party identity
provider page. Practically, this means Appium's XCUITest driver alone is
sufficient for the entire flow - there is no Safari-hosted web content
requiring different tooling anywhere in it, despite what the system
dialogs make it look like at first glance.

### The eDoklady TST scheme collision (now a non-issue on a clean device)

On a device that also had "eDoklady TST" (`sk.minv.edoklady.kmp.tst`, a
separate Slovak digital-documents app, presumably built on the same EUDI
reference-wallet architecture) installed, the very first "Open in ...?"
bounce above showed "Open in 'eDoklady TST'?" instead - a genuine
app-scheme collision, most likely both apps registering the same custom
scheme (e.g. `openid4vp://`). Confirmed resolved by uninstalling eDoklady
TST from the test device; not something this app's own code can fix, and
not expected to affect any device that doesn't happen to have a
same-scheme app installed alongside it.

### How far the flow gets: everything this app owns, then a live backend limitation

`tc-37-qes-signing.py` runs the entire flow - document selection, signing
service selection, both OpenID4VP round trips, certificate selection - and
asserts each of those steps. It deliberately does **not** assert the
final signing call succeeds. That call goes to the live dev QTSP backend
and has been observed, confirmed independently three separate times, to
fail with a graceful (non-crashing) native error:

> Oups! Something went wrong
> It seems the RQES Signing service is unavailable. Please try again later.

**Confirmed not a one-off blip, though not exhaustively ruled out as
eventually-transient either**: reproduced at 11:29 and 11:30 (immediate
"TRY AGAIN" retry, same app session) and again at 11:52 - a fully
independent run, fresh Appium session, fresh app relaunch, fresh
PIN/re-authentication from scratch - about 22 minutes later. Same exact
error text all three times. This is treated the same way `ci.yml`/
`maestro-simulator.yml` already treat the dev issuer's occasional 504s for
TC-01/TC-50: a known category of external-dependency flakiness, not
something to build retry logic around here, and not something to assert
against as a pass/fail condition. If this backend comes back up, the
script logs that plainly as an unexpected-but-good outcome rather than
failing the run.

### Running it

```
EUDI_WALLET_PIN=<pin> python3 tc-37-qes-signing.py <udid> <apple-team-id> <bundle-id> <fixture-basename>
```

`fixture-basename` is the seeded PDF's filename without its extension
(e.g. `tc37-qes-test2` for `tc37-qes-test2.pdf`). Requires the fixture to
already be synced to the device via the iCloud Drive method above - this
script does not create or seed it. Not wired into CI: it needs a
physical device and a signed-in personal iCloud account, neither of which
exist in the GitHub Actions self-hosted-runner environment today (same
class of gap flagged as a "forward reference" for Fáza 4 real-device work
in the top-level `.maestro/README.md`).

## TC-38 — QTSP-side cancellation: investigated, not a UI-distinguishable scenario

Proposed as: verify what happens if the remote signing call itself is
genuinely interrupted/cancelled mid-flight (network loss, force-kill,
or a QTSP-side rejection while `signHash` is in flight) - as distinct
from TC-18-style cancellation of the *local* OAuth/document-selection
steps, which happens before any remote signing call is ever made.

Investigated at the code level (the same vendored `eudi-lib-ios-rqes-ui`/
`-kit`/`-csc-swift` packages already read for TC-37), not live - the
answer falls directly out of the error-handling structure and doesn't
need a live backend to confirm. The actual remote signing call chain is:

```
SignedDocumentViewModel.initiate()
 -> interactor.signDocument()
  -> RQESControllerImpl.signDocuments(authorizationCode)
   -> authorizeCredential(authorizationCode)   // network: exchange code for access token
   -> authorized.signDocuments()               // RQESServiceCredentialAuthorized
    -> rqes.signHash(request:, accessToken:)   // network: the actual QTSP signing call
    -> rqes.createSignedDocuments(signatures:)  // local: embeds signature into the PDF
```

**Finding: both network calls - the credential-authorization token
exchange AND the actual `signHash` remote-signing call - are wrapped in
one single shared `catch` at the `SignedDocumentViewModel` level, with
zero inspection of the error's type or origin**:

```swift
do {
  let signedDocument = try await interactor.signDocument()
  ...
} catch {
  setErrorState(.genericServiceErrorMessage) {
    Task { await self.initiate() }
  }
}
```

A real QTSP-returned rejection, a network timeout, a dropped connection
mid-request, and the already-documented "backend genuinely unavailable"
case from TC-37 are all indistinguishable from each other by the time
they reach the UI - every one of them produces the exact same generic
"Oups! Something went wrong / It seems the RQES Signing service is
unavailable" screen already covered above. There is no error-specific
messaging, no distinct "cancelled" state, and no code path that would
let a UI-level test tell "the QTSP explicitly rejected/cancelled this
signature" apart from "the network dropped" apart from "the backend
was down" (TC-37's case).

The SDK does have a genuine, distinct "Cancel" affordance
(`LocalizableKey.cancelSigningProcessSubtitle`: "Cancel will redirect
you back to the document list without signing your document.") - but
it covers the local OAuth/document-selection steps, before any
`signHash` call is made, which is exactly the *different* scenario this
test case was proposed to exclude, not the in-flight remote call itself.

Force-killing the app process (rather than just losing network) during
this window is a genuinely different action, but doesn't give TC-38 a
meaningful scenario either: it wouldn't exercise anything QTSP-cancellation-
specific, only how the app recovers from being killed mid-operation -
already TC-47's (`tc-47-force-kill-recovery.yaml`) territory, not a new
signing-specific behavior.

**Conclusion**: classified the same way as TC-23/TC-53 - investigated and
concluded not to be a distinguishable, testable scenario at all, not a
tooling or environment limitation. No flow file exists for it and none
should be added; the error-collapsing behavior above is exactly what
`tc-37-qes-signing.py` already tolerates as its known backend limitation.

## TC-39 — signature/timestamp verification: confirmed feature gap, not exercised as a flow

Proposed as: after a successful signing, inspect the actual signed
document to confirm its embedded timestamp is cryptographically valid
and matches the real signing time.

The dev QTSP backend did succeed once during this investigation (see
TC-37's backend-flakiness note above), reaching a real native "Data
shared" / "You have successfully signed your document." screen showing
a green-checkmark `tc37-qes-test2.pdf >` row. Reproducing that success
again to interact further with it did not succeed on subsequent attempts
- but the question of *how* the app would expose that document for
verification, and whether any verification UI exists at all, is fully
answered by the code, independent of backend availability:

- The signed PDF is written to **`NSTemporaryDirectory()`**
  (`RQESService.getTempFileURL()` in `eudi-lib-ios-rqes-kit`) - an
  ephemeral, sandboxed, app-private temp file. It is never copied to
  the app's Documents directory, never exposed to the Files app, and
  never offered via AirDrop/share/save-to-Files anywhere in this flow.
- Tapping that document row opens `DocumentViewer` (`DocumentView.swift`),
  a plain `PDFKit` `PDFView` that only renders the PDF's pages. When
  `isSigned` is true it shows exactly one toolbar icon - a static
  `Image(.verifiedUser)` badge - with **no action closure wired to it at
  all**, confirmed by reading the full view: it's decoration, not a
  button, and does not open any certificate/timestamp/validity detail
  screen.
- `DocumentView.swift` contains no `UIActivityViewController`, no share
  sheet, and no Files-save affordance anywhere.

**Finding: the app currently provides no user-facing way to verify a QES
signature's timestamp, certificate chain, or overall validity.** This
isn't a testing inconvenience or a gap in this investigation - it's a
real, code-confirmed absence of a feature. A user who successfully signs
a document through this flow has no way, from within the app, to inspect
or export what was actually produced.

**Not pursued further**: extracting the raw signed PDF directly from the
device's app-sandbox temp directory (e.g. via `xcrun devicectl device
copy from --domain-type temporary`, the same tool already used to pull
crash logs for the PoDoFo finding above) was considered as a way to
independently verify the signature/timestamp outside the app entirely.
Not attempted: that domain's identifier is documented by `devicectl` only
as "a unique client-provided string" for its own scratch space, with no
confirmed mapping to a specific app's `NSTemporaryDirectory()` sandbox
folder, and the file is only guaranteed to exist for the lifetime of the
signing session that created it. Worth revisiting if independently
verifying a real signed document's cryptographic timestamp ever becomes
a priority - it would likely need either that `devicectl` path confirmed
working, or app-side instrumentation to copy the signed file somewhere
externally reachable before it disappears.

**Conclusion**: classified the same way as TC-23/TC-53 - investigated
and answered directly, not left as an open blocker. No flow file exists
for it (there is nothing to assert against inside the app itself), but
unlike TC-38 this is a genuine product gap worth surfacing on its own
merits, not just a "not testable" note.

## Crash: uncaught C++ exception in PoDoFo's `beginSigning()` on a malformed input PDF (SIGABRT)

Found incidentally while developing the flow above, using a hand-crafted
PDF fixture (raw PDF syntax written directly via a shell heredoc, not
produced by any real PDF library) as the test document. Tapping "Proceed"
after selecting the signing certificate - the point where the app must
hash the document for the credential-authorization request - killed the
entire app process outright, not just the current screen/step.

Confirmed via the device's own crash log
(`EudiWallet-<timestamp>.ips`, pulled with `xcrun devicectl device copy
from --domain-type systemCrashLogs`):

```
Exception Type:  EXC_CRASH
Signal:          SIGABRT
Termination:     namespace SIGNAL, code 6, "Abort trap: 6"
ASI:             libsystem_c.dylib: "abort() called"

Faulting thread backtrace (top-relevant frames):
  abort() -> __abort_message -> demangling_terminate_handler() ->
  _objc_terminate() -> ... -> std::__terminate(void (*)()) ->
  __cxa_rethrow ->
  PoDoFo::PdfRemoteSignDocumentSession::beginSigning()   <- throws here
  -[PodofoWrapper calculateHash]
  PodofoManager.calculateDocumentHashes(request:tsaUrl:)
  RQES.calculateDocumentHashes(request:)
  static RQESService.calculateHashes(...)
  RQESServiceAuthorized.getCredentialAuthorizationUrl(...)
  RQESControllerImpl.getCredentialAuthorizationUrl(...)
  RQESInteractorImpl.openCredentialAuthrorizationURL()
  closure #1 in CredentialSelectionViewModel.openAuthorization()
```

**What triggers it**: `PdfRemoteSignDocumentSession::beginSigning()` (a
C++ method in the vendored PoDoFo library, reached via
`PodofoManager.calculateDocumentHashes` -> `RQES.calculateDocumentHashes`
-> `RQESService.calculateHashes`, called from
`CredentialSelectionViewModel.openAuthorization()` right after certificate
selection) throws a C++ exception while parsing/hashing the selected PDF.
Nothing between that throw site and the top of the call stack catches it,
so it propagates all the way to `std::terminate` -> `abort()`, taking the
whole process down with SIGABRT rather than surfacing as an in-app error.

**Confirmed fixture-specific, not a general defect**: re-running the
identical flow (same certificate, same everything else) with a
genuinely well-formed PDF - produced via macOS's own `cupsfilter`
text-to-PDF filter rather than hand-written PDF syntax - sailed straight
through this exact point with no crash, both in a careful manual retry
and in the fully-scripted `tc-37-qes-signing.py` run. So this specific
malformed fixture is what triggered it, not every document going through
`beginSigning()`.

**Severity/classification**: tracked as its own finding, same treatment
as `issuance/README.md`'s `XCTAutomationSupport` driver-crash entry -
real and reproducible, even though only reached here via a deliberately
malformed test fixture rather than a document a real user would plausibly
pick. The underlying gap is genuine and worth fixing on its own merits:
an uncaught C++ exception from a third-party parsing library should be
caught and surfaced as a normal in-app error (the same graceful "Oups!
Something went wrong" pattern already used elsewhere in this exact flow
for the backend-unavailable case above), not left to crash the entire
process. Whether a real user could plausibly produce a PDF malformed
enough to hit the same throw site is unconfirmed - this was only checked
against one intentionally-malformed fixture and one well-formed one, not
a range of edge cases (encrypted PDFs, corrupted-but-mostly-valid PDFs,
PDFs from unusual generators, etc.).

**Not exercised as a runnable flow here**: reproducing it requires
deliberately swapping in a malformed PDF fixture, which is the opposite
of what `tc-37-qes-signing.py` should be doing on every normal run - so
this is documented as a finding, not wired into the flow file above or
into CI.

## TC-52 — W3C Digital Credentials API via Safari: implementation confirmed present, blocked by a reference-verifier/Safari protocol mismatch

Not a QES flow - filed here because it's the same physical-device/Appium
investigation style as TC-37 above, and there was nowhere more specific
to put it yet. This is the third, browser/origin-based presentation
mechanism (`EudiReferenceWalletIDProvider`, the app's Identity Document
Provider extension backed by the pinned `av-lib-ios-w3c-dc-api` /
`DcApi18013AnnexC`), distinct from both BLE proximity (`proximity/`) and
OpenID4VP deep-link presentation (`presentation/`). Zero test files
existed for it before this investigation; genuinely first exploration.

**Confirmed via code first, then live on the physical device**: the
extension is fully implemented (`DocumentProviderExtension.swift`,
`RequestAuthorizationInteractor.swift`, real `IdentityDocumentServicesUI`/
`ExtensionKit` usage, registered under extension point
`com.apple.identity-document-services.document-provider-ui`). It's
reached when a web page calls `navigator.credentials.get({ digital: {
requests: [...] } })` with a `protocol` the OS recognizes, and iOS routes
the request to whichever installed app registers as a provider for it -
no custom scheme, no deep link.

**A trigger for this already exists live - no custom test page was
needed.** The EUDI reference verifier's own web frontend
(`dev.verifier.eudiw.dev`, source at `eu-digital-identity-wallet/
eudi-web-verifier`) has a first-class "Submit via DC API" tab right next
to "Submit with Redirects" in its normal presentation-request-preparation
flow (step 3 of "Define your presentation request"). Driving Safari via
Appium to that page, selecting PID / `mso_mdoc` / all attributes, and
tapping through to that tab and Submit worked cleanly - the friction
that follows is not an Appium/automation limitation, it's a real,
external mismatch between two other parties.

**The native system document picker never appeared - confirmed why, not
just observed that it didn't.** Tapping Submit produced an immediate
in-page error: "Protocol openid4vp-v1-signed is not supported by the
user agent." Reading the frontend's actual source
(`dc-api.component.ts`) confirms it hardcodes
`const protocol = 'openid4vp-v1-signed'` and gates the entire flow on
`window.DigitalCredential.userAgentAllowsProtocol('openid4vp-v1-signed')`
- which Safari on this iOS 26.6 device returns `false` for. Public
reporting on Safari 26's DC API support says it currently only allows
the `'org-iso-mdoc'` protocol - which is exactly what this app's own
extension implements (`DcApi18013AnnexC` is specifically the ISO
18013-7 **Annex C** / `org-iso-mdoc` binding, not an OpenID4VP-over-DC-API
one). So the reference verifier's current default and what Safari (and
this app) actually speak are two different protocol strings. The failure
happens entirely client-side, in Safari's own JS, before any system UI
renders, before any network round trip beyond the initial transaction
setup, before this app is ever invoked.

**Server-side confirms exactly that, not just the client-side error
text**: the verifier's own transaction log (its "View transaction logs" /
"Show Logs" panel) shows exactly one entry - "DC Api Transaction
initialized" - and nothing further. `POST /ui/presentations/dc-api`
(request shape: `dcql_query`, `nonce`, a required `origin`, optional
`intended_use_id` - confirmed directly from the verifier-endpoint's own
`InitDcApiTransactionTO` source) does succeed and returns a real
`request`/`transaction_id` pair; the actual credential exchange never
happens because the browser refuses to place the call at all.

**Not pursued further, deliberately**: confirming whether this app is
reachable via DC API *at all* (independent of the reference verifier's
protocol choice) would need a minimal custom HTML/JS test page that
calls `navigator.credentials.get()` requesting `'org-iso-mdoc'` directly
- bypassing the verifier frontend's hardcoded protocol - built on the
`POST /ui/presentations/dc-api` request/response shape already confirmed
above. This is a genuinely larger undertaking than anything else explored
in this investigation (standing up real HTTPS-reachable infrastructure,
since the Digital Credentials API requires a real secure-context origin
- not `file://`, and `javascript:` URL injection into an existing page
doesn't work either, see below), and the root cause is a mismatch
between two third parties, not something fixable or reasonably
workable-around on this app's side right now. Flagged as a known,
concrete future possibility - not a dead end, just not pursued given the
cost - if this ever becomes worth resolving conclusively.

**Conclusion**: distinct from both TC-38 (not a real scenario at all) and
TC-39 (a confirmed product gap) - this is a confirmed-working
implementation blocked by an external, third-party protocol mismatch
outside this app's control. Not classified as blocked-on-our-side, and
not something to keep retrying like TC-37's backend flakiness - it needs
either the reference verifier to default to `org-iso-mdoc` (or offer a
protocol choice), or Safari to add `openid4vp-v1-signed` support, or a
custom test page built specifically to route around both.

### Safari-driven Appium automation: two reusable friction notes

Found while investigating TC-52 above; kept here as reusable knowledge
for any future work driving Safari (rather than this app directly) via
Appium/XCUITest, since neither has come up in TC-02/TC-22/TC-37's own
device work:

- **Appium's WebDriver `/url` endpoint refuses non-`http(s)` schemes.**
  A `javascript:...` URL (the usual no-infrastructure way to run
  arbitrary JS in a page's existing context, e.g. to feature-detect a
  browser API without building a whole test page) gets rejected outright
  with "Url or Uri must start with `<scheme>://`" - confirmed live, not
  assumed. Safari's own address bar has also disabled executing
  `javascript:` URLs typed directly into it for years as an anti-phishing
  measure, so this likely wouldn't work even routed around Appium's own
  restriction. There is no console/`execute script` escape hatch for
  Safari web content over this driver either - `/session/:id/execute`
  returned a plain 404 (`unknown command`) when tried against a Safari
  session in this same investigation.
- **Element `rect` values and touch-action coordinates for Safari web
  content are in the device's logical point space, not screenshot pixel
  space.** This device's screenshots are 1320x2868px but its logical
  point space (what `/rect` and W3C `actions` coordinates both use) is
  440x956 - confirmed live after a raw pixel-space swipe (e.g. `x: 460`)
  landed off the right edge of a 440-point-wide screen and did nothing.
  Convert screenshot-pixel coordinates down to point space (roughly
  divide by 3 for this device) before issuing any raw coordinate-based
  tap/swipe against web content - element-reference-based clicks avoid
  this entirely and were used everywhere else in this investigation once
  the issue was caught.

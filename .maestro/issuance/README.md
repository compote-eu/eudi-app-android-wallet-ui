> ⚠️ **PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,**
> navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
> attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.

# Issuance flows

## TC-57 — issuance via a scanned/deep-linked OpenID4VCI Credential Offer: confirmed automatable, reveals granular per-format issuance TC-01 never exercises

Investigated the "Scan QR" icon on the Add Document screen - every other
issuance flow in this suite (TC-01/TC-50/TC-20/TC-51/TC-18) goes through
the app's own pre-configured "Choose from list" issuer path and never
touches this entry point at all.

**Confirmed via code, not assumed.** The icon (`qrCodeViewfinder`,
locator `AddDocumentLocators.scanQrCode`) is wired in
`AddDocumentViewModel.onScanClick()`
(`Modules/feature-issuance/Sources/UI/Document/Add/AddDocumentViewModel.swift`),
which pushes a generic scanner screen (`ScannerViewModel`,
`Modules/feature-common`) configured for `flow: .issuing(...)`. The
scanner does only generic URL-shape validation on whatever string it
decodes, then routes to `AppRoute.credentialOfferRequest(config: [uri:
scanResult])`. Separately, `DeepLinkController.swift` classifies any
incoming URL matching the `openid-credential-offer` or `haip-vci`
schemes (both registered in `Wallet.plist`'s `CFBundleURLSchemes`) as
action `.credential_offer`, routing to the exact same destination. So
the camera is purely a way to obtain a URL string - a real scan and an
OS-level/`openLink`-delivered URL of the same scheme both land on
`DocumentOfferViewModel`. This flow exercises that shared code path
directly, bypassing the camera-scanning UI screen itself (which would
need real-camera/Appium work to test - out of scope here, same
real-device-only classification as TC-37/TC-41).

**The DEV issuer (`ec.dev.issuer.eudiw.dev`) does expose a working
credential-offer flow**, documented in its own published
`credential_offer_guide.html`: select credential(s) → grant type →
Submit → the page returns a QR code and/or a same-device redirect link,
both encoding the same underlying offer URI.

**Reusable setup technique: replicate the issuer's own request sequence
directly via HTTP, not a browser.** Same "call the API instead of
driving a browser" approach `tc-22-setup-presentation.js` already
established for the verifier. The issuer's `credential_offer_choice`
page turned out to be plain server-rendered HTML (not the JS SPA it
first appeared to be from a static fetch) - three requests replicate
the whole thing:
1. `GET /credential_offer` - redirects to `credential_offer_choice`,
   which sets a session cookie and returns a hidden `payload` field
   listing every credential type/format the issuer can offer.
2. `POST` that same `payload`, unmodified, to
   `/display_credential_offer` - returns the real "Please select
   credentials" form (checkboxes per type + a grant-type radio + a
   `credential_offer_URI` scheme field, defaulting to `haip-vci://`,
   which the app also accepts).
3. `POST` the chosen selection to `/credential_offer` - returns an
   auto-redirecting page whose embedded JSON contains `url_data`: the
   real, ready-to-open credential-offer URI (also a base64 QR PNG of
   the same value, and a `wallet_dev` same-device link).

Maestro's `http` binding (used in `tc-57-fetch-credential-offer.js`)
auto-follows the redirect in step 1 and exposes `response.headers`,
including `set-cookie` - not documented anywhere in Maestro's own docs
at the time of writing, confirmed live via a throwaway probe script.
The session cookie has to be threaded through all three requests by
hand; there's no automatic cookie jar the way a browser or `curl -b/-c`
has one.

**Three confirmed differences from TC-01's "Choose from list" path -
verified live, not assumed identical just because both converge on the
same FormEU form:**

1. **An explicit "Issuance request" confirmation screen appears first**
   (`document_offer_screen_issue_button`/`document_offer_screen_cancel_button`,
   `DocumentOfferViewModel`) - TC-01's path goes straight from tapping a
   document row into the `ASWebAuthenticationSession` consent, with no
   equivalent intermediate confirmation screen.
2. **Granular, issuer-dictated credential selection, bypassing the
   app's own "Combined" bundling.** Requesting only
   `credential_configuration_ids: ["eu.europa.ec.eudi.pid_mdoc"]` in
   the offer issues *only* "PID (MSO Mdoc)" - no "PID (SD-JWT VC)" -
   unlike TC-01's "PID Combined" menu item, which always bundles both
   formats into one issuance. A credential offer lets whoever
   constructs it choose exactly which configuration(s) to request,
   entirely outside the app's own "Combined" grouping.
3. **"Done" returns to whichever tab was active when the deep link
   fired, not always Documents** - already documented in
   `tc-26-multi-document.yaml`'s own comments for a different entry
   point, now confirmed via this one too. `tc-57-credential-offer-qr.yaml`
   explicitly navigates to the Documents tab before its own final
   assertion rather than assuming it's already there, same fix TC-26
   already applies.

Everything from the country-selection screen onward (FormEU form,
Review & Send, Authorize, success screen) is byte-for-byte the same as
TC-01 - the divergence is entirely front-loaded into the offer-
acceptance step. That shared section was extracted out of
`tc-01-pid-issuance.yaml` into `formeu-identity-proofing-flow.yaml` (a
sub-flow both `tc-01-pid-issuance.yaml` and
`tc-57-credential-offer-qr.yaml` now reuse via `runFlow`) rather than
duplicated a second time - re-verified live that `tc-01-pid-issuance
.yaml` still passes end-to-end after this extraction.

**One non-deterministic finding worth flagging, not glossing over**:
the OS-level "Open in 'EUDI Wallet'?" confirmation dialog appeared on
one run of `tc-57-credential-offer-qr.yaml` but not on an otherwise
identical manual probe, nor on either of the two committed-flow runs
that followed. Handled as `optional`, the same way the "Allow"
identity-verification dialog already is elsewhere in this suite -
timing/state-dependent, not something to assume present or absent
either way.

**Files**: `tc-57-fetch-credential-offer.js` (setup - the three-request
sequence above), `tc-57-credential-offer-qr.yaml` (the flow itself),
`formeu-identity-proofing-flow.yaml` (the shared identity-proofing
sub-flow, also used by TC-01). Not wired into `maestro-simulator.yml`'s
main chain yet - confirmed passing twice locally
(`maestro test tc-57-credential-offer-qr.yaml`, run back-to-back against
the same simulator session after a prior `tc-01-pid-issuance.yaml` run,
same precondition convention as TC-51) but not yet exercised on the CI
runner itself.

## Driver crash: `EXC_BAD_ACCESS` in Apple's `XCTAutomationSupport`, triggered by the Choose-from-list shimmer bug

Found while developing TC-18 (`tc-18-cancel-issuance.yaml`). Two separate
live runs didn't just fail an assertion - the entire Maestro/XCTest
driver session crashed outright, taking the app process down with it.
Confirmed via the Simulator's own crash reporter
(`~/Library/Logs/DiagnosticReports/EudiWallet-*.ips`), identical
signature both times:

```
Exception Type:  EXC_BAD_ACCESS (SIGSEGV)
Exception Subtype: KERN_INVALID_ADDRESS at 0x0000000000000020
Crashed thread backtrace (top frame):
  XCTAutomationSupport
  -[XCTAutomationSession initWithAccessibilityFramework:dataSource:]_block_invoke
  -> libdispatch.dylib (_dispatch_call_block_and_release / _dispatch_client_callout / ...)
```

This is a crash inside Apple's own automation framework (a
null-pointer-style dereference at a small, fixed offset) - not a crash
in this app's own Swift code. Confirmed by reading both full crash
reports' backtraces: same exception type, same subtype, same crashing
symbol, both times.

**What triggers it**: tapping on, or asserting against, the "Choose from
list" document-type picker screen while it is still showing its
per-item shimmer/placeholder animation. That shimmer is driven by
`AddDocumentUIModel.isLoading` (`AddDocumentViewModel.swift`'s
`transformCellLoadingState`), a flag that does not reliably clear once
real content has already loaded underneath it - a separate, minor app
bug found incidentally while chasing this crash (real, accessible
document-type text was confirmed present via `maestro hierarchy` at the
exact moment the screen was still visibly shimmering). The
continuously-animating shimmer appears to race closely enough with
XCTest's accessibility-tree snapshot mechanism (used internally by every
`tapOn`/`assertVisible` call) to crash it outright.

**Reproduction history**: hit twice during manual exploration, both
times while the list was actively mid-shimmer; zero times once an
explicit `extendedWaitUntil` on real list text was added before
interacting with the screen (the guard now baked into
`tc-18-cancel-issuance.yaml`).

**Severity/classification**: tracked as its own finding rather than a
footnote - it's a real, reproducible crash, not a cosmetic quirk, even
though it only manifests under XCTest instrumentation and not for a real
end user tapping the same row (a genuine touch never races an
accessibility snapshot the same way this driver-level query does; this
is Maestro/XCUITest-specific). The underlying cause - the `isLoading`
flag not clearing - is still a genuine app bug worth fixing on its own
merits, independent of whether it ever crashes a test run.

**Flag for future work**: if `tc-18-cancel-issuance.yaml`, or any future
flow touching the Choose-from-list screen, becomes flaky or crashes the
CI job outright, this is the first thing to check - not the flow's own
selectors. The `extendedWaitUntil` guards already in that flow reduce
the odds of catching the screen mid-shimmer but don't address the root
cause, so flakiness here should prompt fixing the `isLoading` bug, not
just adding more waits.

## TC-54 — orphaned `.pending` document record if issuance is interrupted mid-flight: real gap, not automatable here

TC-54 was investigated as a follow-up to TC-18 (`tc-18-cancel-issuance.yaml`):
TC-18 only covers cancelling at the very first system-level checkpoint,
the `ASWebAuthenticationSession` consent alert, which fires *before* any
request has been sent to the issuer. This entry covers a later,
genuinely different and more severe scenario - the app being interrupted
*after* the user has authenticated with the issuer but *before* the
credential has actually been obtained and stored.

**What happens in that window**: EudiWalletKit's `OpenId4VciService`
writes a `.pending` document record (via `StorageManager.pendingDocuments`)
to support `resumeDynamicIssuance()` - this is a deliberate feature, not a
bug in itself, meant to let a user resume issuance that was interrupted
by something like an app restart mid-authorization. The question this
investigation answers is: what cleans that record up if the user never
resumes it?

**Finding: nothing does, automatically.**

- `StorageManager` (EudiWalletKit) has no TTL, expiry, or background
  cleanup job anywhere for `pendingDocuments` - confirmed by reading the
  full storage implementation. A `.pending` row survives indefinitely
  once written.
- `resumeDynamicIssuance()` is called from exactly one place in this
  app - `AddDocumentViewModel.initialize()` - meaning it only runs if the
  user happens to navigate back into the Add Document screen again later.
  It is not triggered automatically by anything else.
- `StartupInteractor` (the app's own launch/bootstrap sequence) never
  inspects or resumes pending documents on launch - a fresh app start
  does not attempt to clean this up either.
- The Documents tab does surface `.pending` records visibly (a
  warning-colored clock-icon row, `DocumentTabUIModel`), so the user isn't
  left with an entirely invisible artifact - but the ONLY action wired to
  tapping that row (`DocumentTabView`'s handling of `.pending`/`.failed`
  states, routing to `onDeleteDeferredDocument`) is a delete-confirmation
  modal. There is no "resume" affordance exposed anywhere in the
  Documents UI itself.

Net effect: if a user is interrupted in this window and never happens to
revisit "Add Document -> choose the same document type again" (which
silently triggers `resumeDynamicIssuance()` as an unadvertised side
effect of just being on that screen, not something the user is told to
do), the only way to clear the stuck `.pending` row is to notice it and
explicitly delete it. Left alone, it persists across app restarts
indefinitely - there is no path, automatic or user-driven-and-obvious,
that resolves it on its own.

This is classified as a real, moderate-severity gap - the same class of
finding as TC-20's silent-duplicate-issuance result - rather than a
documentation-only limitation like TC-23/TC-53. The underlying mechanism
(no cleanup path for an interrupted dynamic issuance) is a genuine app
behavior, confirmed by reading the actual storage and view-model code,
not an assumption from the manual test notes.

**Why this can't be exercised as a runnable Maestro flow here**: reproducing
it requires abandoning the app (force-quit or background-kill) at the
precise moment between "user has completed authentication with the
issuer" and "WalletKit has fully obtained and stored the credential" -
i.e. *after* the point TC-18 exercises (the consent alert, before any
request is sent), during the token-exchange/credential-retrieval network
round trip that follows the user tapping "Continue". That window's
duration depends on live network timing to the dev issuer and isn't
something Maestro can hook or pause on directly - there's no event to
assert on that fires "now the `.pending` record exists but the
credential doesn't yet." A `stopApp` fired some fixed delay after tapping
"Continue" would be racy: too early and it lands before the record is
even written (same as TC-18's outcome), too late and the credential may
already have been obtained normally. Deterministically hitting the
window would need either an artificial network delay/fault injection
between the app and the issuer (out of scope for this black-box
simulator suite) or app-side test instrumentation to pause mid-issuance
(also out of scope). Flagged here as a known, code-confirmed gap rather
than exercised as a pass/fail flow.

## TC-19 — issuer rejects the request: no clean UI-level trigger exists, same treatment as TC-38

TC-19 was proposed as: verify the app handles an issuer-side rejection of
a credential/token request gracefully during document issuance. Investigated
whether the mock identity-proofing form (FormEU, used by TC-01/TC-50) or
the live dev issuer offers any legitimate way to trigger this through
normal UI interaction, rather than assuming one exists.

**The mock issuer performs no field validation that could produce a
rejection.** Cloned and read the actual backend behind `ec.dev.issuer
.eudiw.dev` (`eu-digital-identity-wallet/eudi-srv-web-issuing-eudiw-py`).
`form_formatter()` (`app/route_dynamic.py`) parses whatever is typed into
Family Name/Given Name/Country Code/Place of Birth as free-form strings
with no format checking - confirmed directly in source, not assumed.
(The one strict-parsing path, `effective_from_date` via `datetime
.strptime(..., "%Y-%m-%d")`, isn't a field FormEU's PID/mDL forms
expose.) This was already observed indirectly during earlier live
testing: a garbled field value from an unrelated focus bug was accepted
and issued without complaint.

**The genuine `"invalid_credential_request"` rejection branches in that
backend are protocol-level, not reachable through the form.** They fire
only when the JSON credential request is missing `credential_identifier`/
`credential_configuration_id`, or names a `connection_type` the backend
doesn't recognize (`credentialCreation()`, same file) - conditions a
correctly-functioning wallet never produces on its own. Reaching them for
real would need a deliberately malformed OpenID4VCI request (e.g. a
MITM proxy), not a different form submission.

**The app itself has no dedicated handling for an issuer rejection
either way.** `WalletCoreError`'s full case list (`Modules/logic-core/
Sources/Error/WalletCoreError.swift`) has no rejection/denial-specific
case. `AddDocumentInteractor.issueDocument()`'s catch block
(`Modules/feature-issuance/Sources/Interactor/AddDocumentInteractor
.swift`) treats any non-trust error identically: `error.isTrustBlocked ?
.issuerNotTrusted : .failure(error)`. `AddDocumentViewModel` then shows
`.failure(error)` as a generic inline banner on the Add Document screen
itself (`error.errorMessage` → `localizedDescription`, `Modules/
logic-resources/Sources/Extension/Error+Extensions.swift`) - not a
dedicated rejection UI, and not TC-18's full-screen "Houston we have a
problem!" pattern (that's a different code path, specific to
`ASWebAuthenticationSession` cancellation). Whatever text would actually
appear is whatever the underlying `EudiWalletKit`/OpenID4VCI library's
thrown error's `localizedDescription` happens to produce for that
specific failure - not designed for this case, so its clarity is
unpredictable.

**A live network check (see the separate, internal-only finding below)
confirms the real dev issuer's OAuth server doesn't even return a clean
`invalid_grant` for an invalid authorization code** - it returns an
unhandled server error instead. That closes off the one other
plausible legitimate trigger (an expired or replayed authorization
code) as a *reliable* one: the actual failure mode live-tested there
is a raw HTTP 500, not the graceful rejection this test case was
meant to probe for, and reproducing it through the app's own UI would
need either a network proxy to capture a real code mid-flow or waiting
out its TTL - both bigger than this scenario's priority warrants within
the time spent.

**Conclusion**: classified the same way as TC-38 - a real, once-genuinely-
investigated scenario with no legitimate, reliable way to construct it
using the available mock issuer and current infrastructure. No flow file
exists for it and none should be added under simulator automation.

## Third-party infrastructure finding (internal-only, not an app issue, not reported externally)

While investigating TC-19's authorization-code angle, a live network
check against the actual dev issuer's OAuth token endpoint (discovered
via that issuer's own `.well-known/oauth-authorization-server` metadata)
returned an **unhandled server error with a full debug traceback** for
an authorization code the server didn't recognize - not the graceful
`invalid_grant`/`invalid_request` OAuth error TC-19 was probing for.

**Root cause, visible directly in the disclosed traceback**: the
token-endpoint handler in `eu-digital-identity-wallet/
eudi-srv-issuer-oidc-py`'s `views.py` (`token()`, `grant_type ==
"authorization_code"` branch) looks up the incoming code via
`request_manager.get_request_by_code(code)` and immediately dereferences
the result's `session_id` attribute with no `None` check.
`get_request_by_code()` (`request_manager.py`) returns `None` for any
code it doesn't recognize - which covers both "never issued" and
"expired and already removed," since expired entries are deleted from
the exact same lookup dict on access. An unrecognized code therefore
crashes with an `AttributeError` before the underlying OAuth library
(which the handler only delegates to afterward) ever gets a chance to
return a spec-compliant error.

**What this exposes, at a severity-relevant level of detail (deliberately
not a reproduction recipe)**: the resulting HTTP 500 response is a
Werkzeug/Flask debug-mode error page - full Python source file paths and
line numbers for the framework and application code, plus an embedded,
PIN-locked interactive debugger console that identifies itself as
present and available if unlocked. This is exposed on a public dev/test
endpoint, not gated behind any test-only header or IP allowlist that was
apparent from the outside.

**Why this is filed here as internal documentation only, not escalated
externally**: this is third-party EU reference infrastructure
(`eudi-srv-issuer-oidc-py`), not this app's code, and not this
repository's responsibility to fix or publicly disclose. Kept here
purely so a future investigation into this dev issuer's behavior isn't
surprised by it, and so nobody re-derives "authorization codes here
don't produce clean OAuth errors" from scratch. Whether/how to report
this upstream is a decision for whoever owns that infrastructure
relationship, not something resolved unilaterally in this file.
No further probing (fuzzing, attempting the debugger's PIN, etc.) was
attempted beyond the single malformed-code request that surfaced this.

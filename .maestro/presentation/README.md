> ⚠️ **PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,**
> navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
> attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.

# Presentation flows

## TC-46 — connection loss during remote/OpenID4VP presentation: fails closed, matching the verifier's actual non-receipt

The remote-presentation counterpart to `proximity/SECURITY-FINDING-TC-13.md`'s
BLE false-positive: does this app ever show "successfully shared" when
the verifier actually received nothing, on the HTTPS/OpenID4VP transport
instead of BLE? Answer, confirmed live via `tc-46-network-loss-during-
share.sh` (automated, runs cleanly, see below): **no** - the app fails
closed with a genuine error, and the verifier's own event log
independently confirms it received nothing. This is a fully-tested
negative result, not a "blocked, couldn't test" one like TC-41/TC-52 -
worth stating explicitly given how easily this class of check produces
a real bug when it's actually run (TC-13 is the proof it isn't just
theater).

**Why the remote transport is architecturally different, not just
luckier this one time.** TC-13's root cause was that
`ProximitySessionCoordinator` moved to its success state purely from a
*local* SDK status signal (`session.$status`'s `.responseSent` case) -
"the local send queue drained and a disconnect eventually happened,"
with zero confirmation the peer received anything. The remote/OpenID4VP
path is structurally different at the exact place that matters:
`OpenId4VpService.sendResponse()` (vendored `eudi-lib-ios-wallet-kit`,
`Sources/EudiWalletKit/Services/OpenId4VpService.swift`) calls
`openId4Vp.dispatch(response:)`, which performs the actual HTTPS POST to
the verifier's `direct_post` endpoint and awaits its response, then only
invokes its `onSuccess` callback (which is what ultimately sets
`PresentationState.responseSent` and drives the success-screen
navigation in `PresentationLoadingViewModel`) when that call returns
`.accepted(url)` - a real, parsed HTTP response from the verifier. Any
failure along that HTTP round trip (including "never even connected")
throws instead, propagating all the way up through
`RemoteSessionCoordinatorImpl.sendResponse()` ->
`PresentationInteractor.onSendResponse()`'s catch block -> `.failure` ->
`PresentationLoadingViewModel.doWork()`'s `case .failure(let error):
self.onError(with: error)` - a real, visible error screen. BLE's flaw
was trusting a "sent" status untethered from receipt; OpenID4VP's
success is coupled to an actual request/response round trip, so a
severed connection can't produce a false "shared" claim the way it did
on BLE.

**Confirmed live**: with the host machine made genuinely offline right
before the final PIN digit (which triggers the send), the app shows
"Houston we have a problem! The operation couldn't be completed.
(OpenID4VP.PostError error 0.)" - never the success screen - and the
verifier's own `GET /ui/presentations/{id}/events` log shows only
`Transaction initialized` / `Request object retrieved` / `Verifier
failed to get wallet response`, with no `Wallet response posted` entry
anywhere (the same positive-proof-of-non-receipt check
`tc-24-verify-no-receipt.js` already uses for TC-24's rejection case).

**A test-setup pitfall worth knowing about, found live while building
this - not a finding about the app, a finding about testing it
correctly**: disabling only Wi-Fi (`networksetup -setairportpower en0
off`) does **not** reliably take a Mac offline. On the machine this was
developed on, a physical iPhone was connected via USB with Personal
Hotspot enabled (`networksetup` service "iPhone USB") - the instant
Wi-Fi went down, macOS silently failed the default route over to it,
and a `curl` call to the verifier during that "interruption" got a
completely normal response. The app's resulting "successfully shared"
outcome in that state was *correct* - the network was never actually
down - and would have been wrongly read as a test bug (or worse, an app
bug) had it not been caught by independently checking `route get
default` and a real `curl` call rather than trusting the Wi-Fi toggle
alone. `tc-46-network-loss-during-share.sh` disables **every**
currently-enabled `networksetup` service for exactly this reason, and
refuses to proceed - failing loudly rather than silently passing a
meaningless test - unless a `curl` call independently confirms the host
is genuinely unreachable first (`curl` returns literal `000` for a
connection that never got made, vs. a real HTTP status code otherwise).

**Automated as a permanent regression guard**, same shell-script-
orchestrates-Maestro pattern as `storage/tc-32-reinstall-wipe.sh` (this
needs host-level network control and independent server-side
verification via `curl`, neither of which a pure Maestro flow can do -
`runScript`'s JS engine has no shell/process access at all):
`tc-46-network-loss-during-share.sh` runs `tc-46-setup-and-share.yaml`
(trigger + consent + Share + 5 of 6 PIN digits, network still up), takes
the host offline and verifies it, runs `tc-46-finish-pin-offline.yaml`
(submits the final digit and asserts the error screen, not the success
one), restores the network and verifies *that*, then curls the
verifier's own transaction/events endpoints to confirm zero receipt.
Ran twice in a row locally, clean pass both times.

**Two small Maestro-CLI behaviors worth knowing, found live while
building this (Maestro CLI 2.8.0), in the same spirit as TC-22/TC-52's
own reusable notes:**

- **`openLink` does not interpolate a `-e`-supplied `${VAR}` directly.**
  Confirmed live: `openLink: "${PRESENTATION_URL}"` with `-e
  PRESENTATION_URL=...` passes the literal, unsubstituted string
  through and nothing opens - no error, just silent no-op ("COMPLETED"
  in the log regardless). Routing the same value through `output.*`
  first (`output.presentationUrl = PRESENTATION_URL` in a one-line
  `runScript`, then `openLink: "${output.presentationUrl}"`) works
  correctly - confirmed via a real page load, not just log text. Also
  confirmed while isolating this: a `-e KEY=value` variable is exposed
  inside `runScript` as a bare global identifier (`KEY`), not nested
  under an `env` object as might be assumed - `env.KEY` throws "Cannot
  read property of undefined" there. See
  `tc-46-bridge-presentation-url.js`.
- **`request_screen_description` (what TC-22's own flow asserts on)
  does not currently exist anywhere in the consent screen's
  accessibility hierarchy** - confirmed by dumping and grepping the
  actual JSON hierarchy snapshot from a failed run, not guessed. This
  looks like UI drift since TC-22 was written; re-running TC-22 fresh
  today would likely hit the same failure. Out of scope to fix here,
  but `tc-46-setup-and-share.yaml` uses `request_screen_requested_
  document_0` instead (confirmed present) rather than inheriting the
  stale selector.

**NOT wired into `maestro-simulator.yml`'s main chain, deliberately** -
`tc-46-network-loss-during-share.sh` disables every network service on
the host Mac to simulate connection loss, which would sever the
self-hosted CI runner's own connection back to GitHub Actions mid-job
(same system-wide host-network risk category as TC-21). Kept as a
standalone, manually-run verified flow, same treatment as
TC-21/TC-49/TC-51.

## TC-22 — remote presentation via deep link (implemented)

See `tc-22-remote-presentation.yaml` and its own header comments for the
full flow, including why a custom-scheme deep link (`haip-vp://...`) is
the only way to trigger this on a simulator (no camera, no QR scanning),
and the one-time "Open in EUDI Wallet?" system dialog it has to handle.

## TC-23 — Universal Link variant of remote presentation: N/A

TC-23 was proposed as "reach the same OpenID4VP presentation result as
TC-22, but via a Universal Link (`https://...`, routed via Associated
Domains / apple-app-site-association) instead of a custom URL scheme" —
a genuinely different app-side routing path (NSUserActivity/scene
continuation vs. `application(_:open:options:)`).

Investigated and confirmed this path does not exist anywhere in this
stack, on either side:

- **App side**: `EudiWallet.entitlements` declares no
  `com.apple.developer.associated-domains` entitlement. A repo-wide
  search for `apple-app-site-association`, `NSUserActivity`,
  `continueUserActivity`, and `webpageURL` found zero matches. There is
  no Universal Link handling anywhere in this app - all deep-link
  routing (both issuance credential-offers and OpenID4VP presentation
  requests) goes through the custom-URL-scheme path only
  (`DeepLinkController`/`UrlSchemaController`, see TC-22's own comments).

- **Verifier side**: called the live `POST /ui/presentations/v2` endpoint
  directly with both `jar_mode: "by_reference"` and `jar_mode:
  "by_value"` - both returned `authorization_request_uri` as a custom
  scheme (`haip-vp://...`), never `https://`. Confirmed authoritatively
  against the verifier frontend's own TypeScript source
  (`eu-digital-identity-wallet/eudi-web-verifier`,
  `TransactionInitializationRequest.ts`):
  ```ts
  export type Profile = 'haip' | 'openid4vp';
  export const profileOptions: Record<Profile, ProfileOptions> = {
    haip: { endpoint: 'haip-vp://' },
    openid4vp: { endpoint: 'openid4vp://' },
  };
  ```
  The `profile` field has exactly two possible values, both custom
  schemes. There is no `https`/Universal Link option in the verifier's
  own type system to request in the first place.

**Conclusion**: not a workaround-avoidance gap - Universal Link delivery
for this presentation flow does not exist structurally on either end
(no Associated Domains entitlement app-side, no https request-URI option
verifier-side). TC-23 as originally conceived has no real alternate
mechanism to exercise here. Marked N/A; no flow file exists for it and
none should be added under this premise.

(A genuinely different third delivery mechanism - the W3C Digital
Credentials API, browser/origin-based rather than scheme- or link-based
- does exist in this app; see the separate Digital Credentials API
investigation for whether/how that could inform a future test case
instead of a Universal-Link-based TC-23.)

## TC-53 — remote presentation via in-app QR scan: not automatable on simulator

TC-53 was proposed as "reach the same OpenID4VP presentation result as
TC-22, but via the wallet's own in-app QR scanner instead of an external
deep link" - initially assumed to be redundant with TC-22 (which already
uses a deep link, not QR, to trigger this on a simulator - see TC-22's
own entry above). Investigated further before concluding that, and it
is NOT redundant at the code level, even though neither can be
automated here.

**What TC-22 actually covers**: an externally-delivered URL (Safari,
Messages, `xcrun simctl openurl`, or Maestro's `openLink:`) enters via
`Wallet/Application.swift` -> `DeepLinkController.hasDeepLink`/
`handleDeepLinkAction` (`Modules/logic-ui/Sources/Controller/
DeepLinkController.swift`), which parses the URL scheme via
`UrlSchemaController.retrieveSchemas` (reading `CFBundleURLTypes` from
`Wallet/Wallet.plist`) and applies login-state/screen-foreground gating
logic specific to that path, then calls
`WalletKitController.startSameDevicePresentation(deepLink:)`.

**What's genuinely untested**: the in-app QR scanner
(`Modules/feature-common/Sources/UI/Scanner/ScannerView.swift`/
`ScannerViewModel.swift`) is a real AVFoundation camera view (via the
third-party `CodeScannerView`/`CodeScanner` package), not a shim. On a
successful scan, `ScannerViewModel.onResult`/`onScanResultValidated`
validates the decoded string with only a generic `Rule.ValidateUrl`
(no scheme-specific parsing) and calls
`ScannerInteractor.startCrossDevicePresentation(scanResult:)` directly
-> `WalletKitController.startCrossDevicePresentation(urlString:)`. This
bypasses `DeepLinkController`/`UrlSchemaController` entirely - no
`hasDeepLink`, no scheme lookup, none of the login-state/foreground
gating TC-22's path applies.

The two paths only converge one layer below that, at
`WalletKitController.startRemotePresentation` (both
`startSameDevicePresentation` and `startCrossDevicePresentation` funnel
into it after their own distinct validation/entry logic). So TC-22's
coverage does NOT imply the scanner's own validation/gating logic is
exercised - this is a real, separate code path with its own real gap,
not a duplicate of TC-22 under a different name.

**Why it can't be automated here**: reaching that code path requires the
in-app camera view to successfully decode an actual QR code image -
there is no camera hardware on a simulator (same class of limitation as
the BLE/proximity tests planned for Phase 4 - see the manual testing
guide). No `simctl`/Maestro mechanism exists to inject a pre-decoded
string into `ScannerViewModel` from outside the running app; doing so
would require either a real device with a physical or displayed QR code,
or modifying the app itself to accept a test-only injection point
(out of scope for a black-box E2E test).

**Conclusion**: not a workaround-avoidance gap in the same sense as
TC-23 (where the alternate mechanism doesn't exist at all) - here the
mechanism exists and is code-confirmed to differ from TC-22's path, but
is untestable via Maestro/simulator specifically because it needs a
camera. Marked as requiring manual/physical-device QA; flagged for
Phase 4 alongside the BLE proximity tests. No flow file exists for it
and none should be added under simulator automation.

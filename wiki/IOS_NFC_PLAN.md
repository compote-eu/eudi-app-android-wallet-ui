# iOS NFC data-retrieval (ISO 18013-5 Annex 8 / HCE) — implementation plan

Planning note on adding a full NFC data-retrieval transport to the iOS side of this app, as a
sibling to the existing BLE proximity transport. "Full NFC data retrieval" means the entire mdoc
session — engagement, request, response — carried over NFC via Host Card Emulation (HCE), per
ISO 18013-5 Annex 8, as distinct from NFC *engagement* (a tap that only hands over connection
parameters before the actual data transfer happens over BLE).

> **Status:** implemented through phase 5 plus the Findings A/B/D/E fix (all in the `WIP checkpoint:
> iOS NFC data-retrieval` commit on `feature/kmp-nfc-proximity`), plus two post-checkpoint device-testing
> fixes on top of it — see §3.3 (`MdocTransportFactory` gap) and §3.4 (NDEF AID removal / latent
> Multipaz crash) — and the verifier-ecosystem finding in §8. Phase 6 (real end-to-end hardware
> verification against a reference verifier) is not complete — see §8 for why that's currently blocked
> on tooling, not on this repo's own code.
> **Date:** 2026-09-10, revised 2026-09-16 · this repo's Multipaz pin: `0.99.0`
> (`gradle/libs.versions.toml`) · upstream Multipaz (`github.com/openwallet-foundation/multipaz`)
> verified independently for this plan, current at time of writing · iOS deployment target: `17.4`
> (`iosApp/project.yml`, bumped from `17.0` per phase 1).

---

## 1. Current state summary

iOS proximity today is not a native-Swift feature: it is Kotlin, living in
`shared-logic/src/iosMain/kotlin/eu/europa/ec/shared/wallet/multipaz/IosProximityPresenter.kt`,
built directly on the OpenWallet Foundation's **Multipaz** KMP SDK (`org.multipaz`, the same engine
underneath `eudi-lib-android-wallet-core` on Android — see `wiki/KMP_FEASIBILITY.md`), using
Multipaz's `MdocTransport`/`MdocConnectionMethodBle`/`EngagementGenerator`/`Iso18013Presentment`
APIs in peripheral-server-mode-only BLE. `iosApp/`'s Swift code contains no proximity, BLE, or
mdoc-transport logic at all — it is limited to app bootstrap, the Digital-Credentials-API document
provider extension, and document-signing/registration glue. The proximity screens and ViewModels
themselves (`ProximityQRViewModel` and friends) are fully shared KMP Compose code in
`shared-ui/src/commonMain`; only the *interactor implementation* differs per platform, selected by
Koin DI rather than `expect`/`actual` — Android's implementations drive
`core-logic`'s `WalletCorePresentationController` (wrapping `eudi-lib-android-wallet-core`'s
transport-agnostic `TransferEvent.Listener`), while iOS's implementations
(`shared-ui/src/iosMain/.../di/IosProximityInteractors.kt`) all delegate to one
`IosProximityCoordinator` wrapping the single `IosProximityPresenter`, which owns transport,
engagement, presentment and consent-waiting together, with no separate transport/session split.

The shared `ProximityQRInteractor` interface already declares `toggleNfcEngagement`. Android's
implementation is real — it calls `eudiWallet.enableNFCEngagement(componentActivity)`
(`eu.europa.ec.eudi.iso18013.transfer.engagement.NfcEngagementService`) — but this is **NFC
engagement/handover only**: an NFC tap hands over BLE connection parameters, and the mdoc data
itself still travels over BLE afterward. It is not the full NFC data-retrieval transport this plan
is about. iOS's implementation is a hard-coded no-op, with a comment stating "iOS gives no app NFC
card emulation, so a phone cannot be the mdoc side of an NFC engagement." **Neither platform has
full NFC-as-transport today** — this plan is genuinely new ground on iOS, and Android's existing
NFC feature is a smaller, different thing that happens to share the same toggle-shaped interface
seam.

`iosApp/project.yml` currently sets `deploymentTarget.iOS` to `17.0`, has no `.entitlements` file
committed (XcodeGen generates them from `project.yml`'s `entitlements:` blocks per target, and the
repo's `.gitignore` explicitly excludes the generated file), and declares no NFC entitlement of any
kind. There is no CocoaPods; the only Swift Package Manager dependencies are `EudiRQESUi` (remote
signing, unrelated), `SwiftCopyableMacro` (a version-pin workaround, transitively required by
RQES), and the locally-vendored `PKIXBridge` (X.509/PKIX path validation, unrelated to mdoc
transport). All mdoc CBOR and cryptography already flows through Multipaz on the Kotlin side —
there is no Swift CBOR library anywhere in the project, and none is needed for this work.

## 2. A correction to this repo's own `wiki/KMP_FEASIBILITY.md`

That document states (§3–4): *"iOS holder NFC-tap engagement is impossible on any iOS SDK (Apple
limit), not a Multipaz gap"* and *"Apple grants no NFC HCE to third-party apps."* This is stale and,
as a blanket claim, incorrect. Three independent points of verification for this plan:

- **Apple's own entitlement documentation** for `com.apple.developer.nfc.hce` describes it as
  **approval-gated**, not unavailable: third parties can request it, subject to Apple's review of
  the use case.
- **`pagopa/iso18013-ios`** (confirmed MIT-licensed via the GitHub API) ships exactly this in
  production, inside Italy's official "IO" public-services app, using `CoreNFC`'s `CardSession` API
  (`@available(iOS 17.4, *)`) plus the `com.apple.developer.nfc.hce` and
  `com.apple.developer.nfc.hce.iso7816.select-identifier-prefixes` entitlements, with AID values
  `A0000002480400` (mdoc) and `D2760000850101` (NFC Forum NDEF Type 4 Tag).
- **Multipaz's own upstream source already assumes this is pluggable.** Verified directly against
  `github.com/openwallet-foundation/multipaz` (not this repo's pinned snapshot, the live upstream
  tree): the mdoc-over-NFC *protocol* logic is entirely platform-neutral, in `commonMain` —
  `multipaz/src/commonMain/kotlin/org/multipaz/mdoc/transport/NfcTransportMdoc.kt`,
  `MdocConnectionMethodNfc.kt` / `MdocConnectionMethodNfcV2.kt`,
  `mdoc/nfc/MdocNfcEngagementHelper.kt`, and the AID constants in `nfc/Nfc.kt`
  (`ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID = A0000002480400`,
  `NDEF_APPLICATION_ID = D2760000850101` — the identical standard AIDs pagopa uses, confirming
  these are ISO/NFC-Forum values, not project-specific ones). `NfcTransportMdoc` exposes a public
  companion function, `processCommandApdu(commandApdu, sendResponse)`, documented in its own source
  as "must be called by platform when receiving APDUs" — i.e. Multipaz's design already expects a
  thin, swappable platform binding to feed it raw APDU bytes. **Android has that binding**
  (`multipaz-compose/src/androidMain/kotlin/org/multipaz/compose/mdoc/{CombinedNfcService,
  NfcApduService,MdocNfcDataTransferService,MdocNfcV2Service}.kt`, wrapping Android's
  `HostApduService`). **No iOS binding exists anywhere in Multipaz's tree today** — confirmed via a
  full repository file listing; the only iOS NFC files present
  (`multipaz/src/iosMain/kotlin/org/multipaz/nfc/{NfcTagReader.ios.kt,NfcIsoTagIos.kt}`) are
  reader-role only (iOS acting as a verifier scanning a tag), not holder/card-emulation role.

The real state, then: the gap is genuine — nobody has written the iOS platform glue yet — but it is
an *unbuilt binding*, not a platform impossibility. Multipaz's architecture already has the
extension point Android uses; iOS needs the equivalent one. This repo's `wiki/KMP_FEASIBILITY.md`
should get a follow-up correction reflecting this; that edit is out of scope for this plan.

## 3. Target architecture

NFC becomes a **second `MdocConnectionMethod`** — `MdocConnectionMethodNfc`
— advertised from the same engagement point in `IosProximityPresenter.kt` that already advertises
`MdocConnectionMethodBle`. It is not a parallel screen, ViewModel, or feature: the shared
`shared-ui` proximity screens and `ProximityQRViewModel`/etc. need no new architectural layer,
only whatever state already distinguishes "which connection method is active/available" (extended
for a second method) plus turning the existing `toggleNfcEngagement` seam in `ProximityQRInteractor`
from a no-op into a real one on iOS.

### 3.1 Correction: `CardSession` cannot be cinterop'd from Kotlin/Native — verified empirically

The original version of this plan assumed the new NFC code could be pure Kotlin/Native, cinterop'ing
`CoreNFC`'s `CardSession` directly the same way Multipaz's own `BlePeripheralManagerIos.kt` cinterops
`CoreBluetooth`. **That assumption does not hold, and this was verified directly against this
project's own toolchain and SDK, not assumed from documentation:**

- A throwaway probe file (`import platform.CoreNFC.CardSession`) failed to compile against
  `:shared-logic:compileKotlinIosSimulatorArm64` with `Unresolved reference 'CardSession'`, while a
  known-good older CoreNFC symbol (`NFCReaderSession.readingAvailable`) compiled cleanly in the same
  probe — ruling out a module-resolution problem.
- Checked at the source: Kotlin/Native's `CoreNFC.def` parses `CoreNFC.framework/Headers/CoreNFC.h`
  (`language = Objective-C`). On this machine's actual SDK (`iPhoneSimulator26.5.sdk`, the one this
  project's build uses), `CardSession` is declared **only** in `CoreNFC.swiftmodule/*.swiftinterface`
  — Swift's own module-interface format — and does not appear in the Objective-C header at all.

This is not a toolchain-version gap: `CardSession` is a Swift-concurrency-native API (`async`/`await`,
`AsyncSequence`) that Apple has never exposed to Objective-C, on any iOS SDK, by design. Cinterop can
only bind to a framework's Objective-C/Clang-visible surface, so **no Kotlin/Native code, in this
repo or upstream in Multipaz, can call `CardSession` directly.** The only working direction is the
other way: **Swift code owns the `CardSession` object and its event loop, and calls into Kotlin** for
the actual APDU processing.

This repo already has a working, documented convention for exactly that shape —
`IosDocumentProviderBridge.kt` (`shared-logic/src/iosMain/.../multipaz/IosDocumentProviderBridge.kt`),
whose own comment states the rule generally: "Kotlin/Native exports a suspend *function type* as
`KotlinSuspendFunction1`, which a Swift closure cannot satisfy... A suspend method *on an interface*
exports as a completion-handler method that Swift implements with `async`." The revised shape:

- **Kotlin** (`shared-logic/src/iosMain`, sibling to `IosProximityPresenter.kt`): a small class/interface
  wrapping Multipaz's public `NfcTransportMdoc.processCommandApdu(bytes, sendResponse)` behind a
  Swift-callable, completion-handler-shaped seam — the same convention `IosDocumentProviderBridge.kt`
  already uses. No CBOR, crypto, or mdoc-session logic here; this is a thin adapter.
- **Swift** (a new file, either directly in `iosApp/` or a small local package mirroring the existing
  vendored `PKIXBridge` pattern): owns the actual `CardSession` instance, starts emulation, iterates
  `cardSession.eventStream` (`.readerDetected`, `.received(cardAPDU)`, `.sessionInvalidated`, ...),
  and calls into the Kotlin adapter above for each APDU — structurally close to pagopa's
  `NFCCardEmulator.swift`, but calling this app's own Kotlin/Multipaz stack instead of a separate
  Swift mdoc implementation.

This still keeps `shared-logic` Compose-UI-free and introduces no new CBOR/crypto/session logic —
Multipaz still owns all of that — but it is **not** the "one self-contained Kotlin file, nothing else
touched" shape the plan originally described: a Swift file (and likely a small `project.yml` change to
declare it, unless it lives directly in the existing `iosApp` target's `sources:`) is now a necessary
part of this work, not an optional extra.

The platform seam otherwise stays exactly where BLE already put it: a Koin-selected interactor
implementation on iOS, no new `expect`/`actual` pair.

### 3.2 Consequence for the upstream Multipaz contribution

The "file the same binding upstream against Multipaz" idea (§3, §4) is affected by this too: Android's
existing Multipaz binding (`multipaz-compose/src/androidMain/.../{CombinedNfcService,NfcApduService}.kt`)
is plain Kotlin because Android's HCE API (`HostApduService`) is a native Kotlin/Java-accessible
Android SDK class — there is no ObjC/Swift-style boundary on that platform. An iOS counterpart cannot
mirror that shape 1:1: **any official Multipaz iOS HCE binding will need to ship some Swift, for the
same reason this repo's own binding does.** Worth saying explicitly in whatever is proposed upstream,
so the contribution isn't reviewed against the wrong (pure-Kotlin) expectation.

In parallel — this is still the confirmed "hybrid" approach for this plan, not a full fork — the same
Kotlin-adapter-plus-Swift-owner binding should be proposed upstream against
`openwallet-foundation/multipaz` as an official iOS platform module. This is filed as a parallel
community contribution to converge toward, not a blocking dependency for this repo's own work.

A second, much smaller item belongs alongside it, found via §3.3: `MdocTransportFactory.ios.kt`'s
`defaultMdocTransportFactoryCreateTransport` throws `NotImplementedError` for
`MdocConnectionMethodNfc` + `MdocRole.MDOC` instead of constructing `NfcTransportMdoc`, the same way
its `MdocRole.MDOC_READER` branch already constructs `NfcTransportMdocReader`. Precisely scoped — a
few lines mirroring an existing branch in the same `when`, no new class or protocol logic — and this
repo's own `IosProximityPresenter.kt` fix (§5, Phase 3) is a working, device-verified reference for
exactly what those lines should do.

A third item, found via §3.4, is the most important of the three — a real crash bug, not a missing
feature: `NfcTransportMdoc.failTransport` asserts `check(mutex.isLocked)`, but two of its four call
sites (both inside the private `processApdu(command): ResponseApdu?` — the "unsupported instruction"
branch and the catch-all for any `Exception` from `SELECT`/`ENVELOPE`/`GET_RESPONSE`) call it without
holding that lock, guaranteed to throw `IllegalStateException` instead of failing the transport
gracefully. That exception surfaces inside a coroutine `processCommandApdu`'s companion function
launches internally (no parent job, no handler), crashing the whole host process — reachable by any
rejected/malformed APDU from any reader, not just the wrong-AID case that found it. See §3.4 for the
full trace and exact call sites; this should be filed with that repro attached.

### 3.3 Correction: `MdocTransportFactory.ios.kt` never implements the wallet-role NFC transport — found via live device testing

Phase 3 (§5) wired `IosProximityPresenter.startQrEngagement()` to advertise `MdocConnectionMethodNfc`
alongside `MdocConnectionMethodBle`, both going through Multipaz's own `List<MdocConnectionMethod>
.advertise(role, transportFactory = MdocTransportFactory.Default, options)` extension. That phase's
own scoped verification (`:shared-logic:iosSimulatorArm64Test`, a real `xcodebuild` for compile/link)
passed, because neither exercises a real connection — NFC has no simulator path (§6), and a compile/
link check can't catch a `RuntimeException` thrown by code that type-checks fine. **The gap only
surfaced under real-hardware testing, once NFC was actually toggled on and engagement attempted**:

```
INFO: BlePeripheralManagerIos: peripheralManager didPublishL2CAPChannel
INFO: BlePeripheralManagerIos: Listening on PSM 192
WARNING: IosProximityPresenter: proximity presentation failed: Not yet implemented
XPC connection invalid
```

Root cause, verified directly against the pinned `multipaz-iosarm64:0.99.0` sources jar (not assumed
from behavior): `MdocTransportFactory.ios.kt`'s `defaultMdocTransportFactoryCreateTransport` —
the `internal actual` function `MdocTransportFactory.Default.createTransport()` delegates to —
implements the reader role but not the wallet role for NFC:

```kotlin
is MdocConnectionMethodNfc -> {
    return when (role) {
        MdocRole.MDOC -> throw NotImplementedError("Not yet implemented")
        MdocRole.MDOC_READER -> NfcTransportMdocReader(role, options, connectionMethod)
    }
}
```

`MdocRole.MDOC` is this app's role (the wallet), so every call reaches the `throw`. This is **Phase
3's own deferred TODO, never actually completed** — `IosNfcHceTransport.kt`'s doc comment already said
so, plainly, before this was ever traced: "this class... does not construct an `NfcTransportMdoc`
instance, advertise `MdocConnectionMethodNfc`, or touch `IosProximityPresenter`. Opening a transport
instance needs the device-engagement key the presentment flow generates, which is §5 phase 3's job."
Phase 3 advertised the connection method in the engagement CBOR and started the CardSession/APDU
bridge (phases 2 and 5), but never actually constructed the Multipaz-side transport object those two
halves need to meet at — `MdocTransportFactory.Default` was assumed to do that, silently, and doesn't.

Because `List<MdocConnectionMethod>.advertise()` (Multipaz's `connectionHelper.kt`) is a single `for`
loop with no per-method isolation, and BLE was ordered first in the list, the practical effect on
device was worse than "NFC doesn't work": BLE's transport was already constructed and already
advertising (CoreBluetooth peripheral manager live, L2CAP channel published, listening on its PSM) by
the time the loop reached NFC and threw — aborting the whole `startQrEngagement()` call, before the QR
was ever shown, with the just-started BLE peripheral session abandoned rather than closed (the likely
source of the `XPC connection invalid` message immediately following). Toggling NFC on didn't just
fail to add NFC — it took BLE down with it.

**The fix does not touch Multipaz.** `NfcTransportMdoc` — the exact class `IosNfcHceTransport`'s
`ApduDelegate` already calls into via `NfcTransportMdoc.processCommandApdu`/`.onDeactivated` — is
itself a public class directly implementing `MdocTransport` for the wallet role (`class
NfcTransportMdoc(...) : MdocTransport()`, no visibility modifier), unlike `BleTransportPeripheralMdoc`
(`internal class`, reachable only via the factory). `defaultMdocTransportFactoryCreateTransport`
itself is `internal expect`/`internal actual`, so it cannot be patched or overridden from this module
— but nothing requires going through it. `IosProximityPresenter` now constructs `NfcTransportMdoc`
directly and, separately, keeps going through `MdocTransportFactory.Default`/`advertise()` for BLE
only — the two are combined into one `List<MdocTransport>` only after each has independently
succeeded or failed, so a failure on one side can never take the other down. See §5's revised Phase 3
entry below for the actual shape of the fix.

### 3.4 Correction: the NDEF AID was dead registration — and it crashed the app, via a still-latent Multipaz bug

`iosApp/project.yml`'s `com.apple.developer.nfc.hce.iso7816.select-identifier-prefixes` originally
listed both AIDs pagopa's entitlements use (§2): `A0000002480400` (the ISO 18013-5 mdoc AID) and
`D2760000850101` (the NFC Forum NDEF Type 4 Tag AID). **The NDEF AID has been removed — only the mdoc
AID remains.** Found on real hardware, not by inspection first: a verifier's SELECT AID probe for the
NDEF AID (standard NFC discovery behavior on its part, unrelated to anything this app needs) crashed
the whole process.

**Why the NDEF AID was never actually needed.** It is meaningful only alongside Multipaz's
`MdocNfcEngagementHelper` (`org.multipaz.mdoc.nfc`, commonMain) — "Helper used for NFC engagement on
the mdoc side," the class that answers an NDEF-AID `SELECT` as part of NFC *tap-to-engage* handover.
This app does not do that: engagement is QR-only throughout this plan (§1, §3), NFC is only ever the
data-retrieval transport *after* QR engagement, and — confirmed by search, not assumed —
`MdocNfcEngagementHelper` is constructed nowhere in this codebase, on either platform. The only NFC
class this app actually builds, `NfcTransportMdoc` (§3.3), never references the NDEF AID at all; its
`processSelectApplication` recognizes exactly one AID, the mdoc one. Registering the NDEF AID at the
entitlement level bought nothing functional — it only meant iOS would route a class of `SELECT`
command to our HCE service that our own Kotlin code was structurally guaranteed to reject. It was
carried over by mirroring pagopa's entitlement pair (§2's citation) without also carrying over the
NFC-engagement code that makes that second AID meaningful for pagopa; nothing in this app's own
architecture ever called for it.

**The bug the wrong AID exposed is Multipaz's own, and it's broader than one AID.** Traced directly in
the pinned `multipaz:0.99.0` sources: `NfcTransportMdoc.failTransport` asserts
`check(mutex.isLocked) { "failTransport called without holding lock" }`. Of its four call sites, two
(inside `sendMessage()` and `onDeactivated()`) call it from within `mutex.withLock { }` and are fine.
The other two are both inside the private `processApdu(command): ResponseApdu?` — called with **no
lock held** — reached by (a) an unrecognized `command.ins`, or `INS_SELECT` with an unexpected `p1`,
and (b) the catch-all for **any** `Exception` thrown while handling `SELECT`/`ENVELOPE`/`GET_RESPONSE`:
a wrong AID (`NfcError`, what we hit), a repeated `SELECT`, an out-of-order `ENVELOPE`/`GET_RESPONSE`,
a bad CLA — anything. Both broken call sites throw `IllegalStateException` before any of
`failTransport`'s intended graceful-degradation logic (`State.FAILED`, a diagnostic status for
subsequent APDUs) ever runs, and that exception surfaces inside a coroutine
`NfcTransportMdoc.processCommandApdu`'s companion function launches internally
(`CoroutineScope(Dispatchers.Default).launch { }`, no parent job, no handler) — a context this app's
own `IosNfcHceTransport.ApduDelegate` try/catch cannot reach, since that only wraps the synchronous
entry into `processCommandApdu`, not the coroutine it kicks off. The result is an uncaught exception
that crashes the whole process, not just the one transport.

**This AID removal narrows the crash's trigger surface — it does not close it.** Dropping the NDEF AID
means iOS never routes *that* AID to this app again, so the exact repro above can't recur. But nothing
about `NfcTransportMdoc`'s own bug is fixed: any other protocol violation within the *mdoc AID*
conversation itself — a reader retrying `SELECT`, sending `ENVELOPE` out of order, or any other
malformed/unexpected APDU — reaches the identical unlocked `failTransport()` call and crashes the
process the same way. This remains a real, standing crash risk from untrusted NFC reader input,
independent of this change, until Multipaz fixes the missing lock. It is the most important of the
three items on this plan's upstream contribution list (§3.2) — a crash bug, not a missing feature —
and should be filed as such, with the exact two call sites above as the repro.

**On Apple's approval, since the AID list is now narrower.** Apple's HCE-with-`iso7816.select
-identifier-prefixes` approval is granted per **App ID/Team ID for the capability itself**, not
per individual AID value in the list — the same general pattern as other restricted, list-valued
entitlements (e.g. Associated Domains): once granted, automatic signing regenerates the provisioning
profile to match whatever the entitlements file currently declares. Both AIDs here are public,
standards-defined values (the ISO 18013-5 mdoc AID and the NFC Forum's own Type 4 Tag AID) rather than
an organization-specific RID needing separate per-value ownership verification, so there is no reason
to expect Apple's backend to treat a *subset* of an already-approved list as needing new review. Checked
against real signing resolution, not left as inference alone: `xcodebuild -allowProvisioningUpdates`
against the regenerated, single-AID entitlements — same command, same device, same App ID that
previously failed signing before Apple's original two-AID approval landed (§3) — resolved automatic
signing cleanly (`BUILD SUCCEEDED`, no `error:` of any kind in the full log), confirming a subset of an
already-approved AID list needs no new approval.

**Revised again — the wrong-AID trigger is now also closed in code, not just at the entitlement level.**
`IosNfcHceTransport.ApduDelegate.processCommandApdu` (`shared-logic/src/iosMain/.../IosNfcHceTransport.kt`)
now decodes the incoming APDU with Multipaz's own `CommandApdu.decode` and checks it for a `SELECT`
by AID (`ins == Nfc.INS_SELECT && p1 == Nfc.INS_SELECT_P1_APPLICATION`) before ever calling
`NfcTransportMdoc.processCommandApdu`. If the AID isn't `Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID`
— the exact same comparison `NfcTransportMdoc.processSelectApplication` itself makes — it answers
`Nfc.RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND` (SW `6A82`) directly and returns, without
Multipaz ever seeing the APDU. Anything that fails to decode at all still falls through to the
existing Finding-C catch (`RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS`), unchanged.

This is in Kotlin, not `NfcHceBridge.swift`, on purpose: it needs `CommandApdu.decode` and `Nfc`'s
AID/status-word constants, both Multipaz Kotlin APIs with no Swift-visible equivalent, and doing this
in Swift would mean a second, hand-rolled ISO 7816-4 parser next to Multipaz's own — exactly the
"no CBOR, crypto, or mdoc-session logic" line §3.1 draws for the Swift side of this seam. This only
reads a header Multipaz's own decoder already parses; it doesn't reimplement anything Multipaz owns.

This is genuine defense in depth, not redundant with the entitlement narrowing above: that entitlement
key is named `select-identifier-**prefixes**` — iOS matches by prefix, not exact equality, so a reader
presenting a longer AID that merely starts with the mdoc AID's seven bytes would still be routed to
this app by iOS, then fail Multipaz's own exact-equality check and hit the same unlocked
`failTransport()` path — a case the entitlement alone does not close, because this check mirrors
Multipaz's exact-equality comparison rather than iOS's prefix-based one.

**Scope, stated plainly so it isn't overclaimed: this only gates the *initial* `SELECT`.** Once the
mdoc AID is genuinely selected, a duplicate `SELECT`, an out-of-order `ENVELOPE`/`GET_RESPONSE`, a bad
CLA, or any other protocol violation *within that same legitimate session* still reaches
`NfcTransportMdoc.processCommandApdu` unfiltered and still hits the identical unlocked
`failTransport()` bug traced above — this class has no visibility into Multipaz's own private session
state (`applicationSelected`, `_state`) to gate on without duplicating it badly. **The crash risk from
§3.2's third upstream item is narrowed to "an already-selected, in-progress mdoc session going wrong,"
not eliminated** — that residual exposure is closed only when Multipaz fixes the missing lock.

Verified with a unit test (`IosNfcHceTransportTest.kt`) using a substitutable seam
(`ApduDelegate(forwardToMultipaz = ...)`, real `NfcTransportMdoc.processCommandApdu` by default) rather
than a mocking framework, matching this file's existing no-framework test style: one test proves
`NfcTransportMdoc.processCommandApdu` is never invoked for a `SELECT` targeting the NDEF AID, and a
paired test proves a `SELECT` for the real mdoc AID still reaches it — so the fix is checked against
both directions, not just "the crash doesn't happen."

## 4. Dependency decision

Three approaches were weighed:

| Approach | Verdict |
|---|---|
| Build a fully separate native Swift `CardSession` stack (pagopa-style, standalone) | **Rejected as the primary approach.** Would duplicate mdoc session/CBOR/crypto logic this app already gets from Multipaz on both platforms — a maintenance burden with no offsetting benefit here. Adopted *partially*: as a source of attributed operational know-how (see §6), not as a vendored code stack. |
| Wait for upstream Multipaz to ship an iOS HCE binding first | **Rejected as the sole approach.** Multipaz is pre-1.0 with no committed roadmap item for this; waiting blocks shipping indefinitely on a timeline outside this project's control. Not discarded entirely — pursued in parallel via an upstream filing so this repo's binding can converge with (or be replaced by) the official one later. |
| **In-repo binding now — a thin Kotlin adapter over Multipaz's existing public `commonMain` NFC protocol surface, plus the small first-party Swift shim `CardSession` requires (§3.1) — with an upstream contribution filed in parallel** | **Recommended.** Ships without waiting on an external, uncommitted timeline; reuses 100% of the existing mdoc/session/CBOR/crypto logic already vetted for Android; the Swift shim is first-party and minimal (owns only the `CardSession` event loop), not a third-party dependency. |

License note: `pagopa/iso18013-ios` is MIT-licensed (confirmed via the GitHub API), which permits
adapting specific implementation details with attribution. This repo is EUPL 1.2; an attributed,
partial adaptation of operational details (not a wholesale code import) is compatible with both.

## 5. Phased implementation steps

Each phase's "done" criterion uses this repo's existing scoped-verification approach from
`CLAUDE.md` — the smallest affected scope first, widened only as needed — rather than a new
verification scheme.

1. **Entitlement + capability declaration.** Add `com.apple.developer.nfc.hce` and
   `com.apple.developer.nfc.hce.iso7816.select-identifier-prefixes` (AID values
   `A0000002480400` / `D2760000850101`) to `iosApp/project.yml`'s `EudiWallet` target
   `entitlements:` block; bump `deploymentTarget.iOS` from `17.0` to `17.4`.
   *Done when:* `./gradlew generateIosProject` regenerates cleanly and
   `xcodebuild -project iosApp.xcodeproj -scheme EudiWallet -configuration Debug build` succeeds
   with the new entitlement present. (Apple's actual approval of the entitlement request is a
   separate, non-code-verifiable prerequisite — see Risks.)
   **Revised, §3.4:** the AID list above is what this phase originally shipped, mirroring pagopa's
   pair. It's since been narrowed to `A0000002480400` only — the NDEF AID was dead registration that
   crashed the app on real hardware; see §3.4 for the full trace. Apple's approval, granted for the
   original two-AID request, was confirmed (by real signing resolution, not assumption) to still cover
   the narrower one-AID list with no new review needed.

2. **Transport-level APDU plumbing.** Per the corrected architecture in §3.1: a new `iosMain` Kotlin
   adapter (sibling to `IosProximityPresenter.kt`) wrapping `NfcTransportMdoc.processCommandApdu`
   behind a Swift-callable, completion-handler-shaped interface — the same convention
   `IosDocumentProviderBridge.kt` already uses — plus a new Swift file (in `iosApp/`, or a small local
   package mirroring `PKIXBridge`) that owns the `CardSession` instance and its event loop, calling
   into that Kotlin adapter per APDU. `CardSession` itself cannot be reached from Kotlin/Native (§3.1),
   so this phase is no longer Kotlin-only.
   *Done when:* `./gradlew :shared-logic:iosSimulatorArm64Test` still passes (necessarily
   unit/mock-level on the Kotlin adapter — the simulator has no NFC hardware, see Risks),
   `:shared-logic:testAndroidHostTest` is unaffected, and the new Swift file builds via
   `xcodebuild ... -configuration Debug build` (it cannot be exercised end-to-end without hardware,
   but it must compile and link against the Kotlin adapter it calls).

3. **Wire into the engagement/session flow.** Extend `IosProximityPresenter.kt` to advertise
   `MdocConnectionMethodNfc` alongside `MdocConnectionMethodBle`; make
   `IosProximityCoordinator`/`IosProximityInteractors.kt`'s `toggleNfcEngagement` real.
   *Done when:* `:shared-logic:iosSimulatorArm64Test`, `:shared-ui:testAndroidHostTest`, and
   `:shared-ui:iosSimulatorArm64Test` pass; `detekt ktlintCheck` remain at zero findings.

4. **UI states for NFC vs. BLE.** Any shared Compose screen changes needed to reflect which
   transport is active or available (e.g. un-hiding the NFC toggle on iOS now that it is real).
   *Done when:* the same `shared-ui` scoped tests pass and `:androidApp:assembleDevDebug` still
   builds — shared UI code must not regress Android.

5. **Late-engagement / cool-down handling.** Port pagopa's `isNfcLateEngagement` /
   `lateNfcInitialization()` pattern and its 15-second-active / 15-second-cool-down retry behavior,
   with an attributed comment crediting `pagopa/iso18013-ios`.
   *Done when:* the ported retry/timing logic has scoped tests passing. This is the phase where the
   attributed-adaptation decision from §4/§6 is actually applied.

6. **Hardware testing.** Real-device verification against an EUDI-compatible verifier — NFC has no
   simulator path, the same limitation this repo's `CLAUDE.md` already notes for BLE.
   *Done when:* a real iOS 17.4+ device completes a full NFC-transport presentation against a
   reference verifier, and the full verification suite in `CLAUDE.md` passes, including an
   `xcodebuild` run against a real-device destination (not just the simulator).
   **Not yet met — see §8.** The locally-available reference verifier checkout has its own gaps
   (unfinished NFC-engagement wiring, a transport-selection mismatch with this app's BLE-first
   engagement) that block a clean end-to-end run today; this is a tooling gap outside this repo,
   not evidence against phases 1–5's own implementation, which is otherwise complete.

### Phase 2 implementation notes

Two things worth recording from actually building phase 2, neither obvious in advance:

- **`@objc(NfcHceBridge)` / `@objc(NfcHceBridgeDelegate)` explicit naming is required** on any
  hand-authored cinterop `.def` that targets a Swift class, the way `NfcHceBridge.def` does. Without
  it, Swift exports the class under its own name-mangled symbol
  (`_OBJC_CLASS_$__TtC12NfcHceBridge12NfcHceBridge`), not the plain one
  (`_OBJC_CLASS_$_NfcHceBridge`) a plain-Objective-C-header-based cinterop binding expects — caught
  with `nm` on the built archive before it ever reached Gradle, not as a late link failure.
- **Every Xcode target that embeds the shared Kotlin/Native framework needs the dependency**, not
  just the one that uses it. `EudiWalletDocumentProvider` has nothing to do with NFC, but it embeds
  the same `SharedKit` framework `EudiWallet` does, and the framework does not strip a public class
  just because one particular consumer's code never calls it — so `NfcHceBridge` needed declaring on
  both targets in `project.yml`, the same way `PKIXBridge` already is. This isn't NFC-specific; see
  `CLAUDE.md`'s "The KMP layer" section for the general version.

### Phase 3 implementation notes

`IosProximityPresenter`'s `nfcEngagementEnabled` flag — which gates whether `startQrEngagement()`
advertises `MdocConnectionMethodNfc` and starts the NFC bridge — **defaults to `false`, deliberately**.
Nothing on iOS calls `setNfcEngagementEnabled` yet, so a `true` default would mean CoreNFC's
`CardSession` starts automatically and unconditionally on real hardware the moment the QR screen
opens, with no way to disable it before phase 4 wires a real user-facing toggle. **Phase 4 must
actually call `setNfcEngagementEnabled(true)` from a real user control — this does not happen on its
own**, and phase 4's own scoping should account for that call, not just for UI state to render.

**Revised — what this phase's original wiring got wrong, and the actual fix (post-device-testing).**
The original Phase 3 landed with `startQrEngagement()` building one `List<MdocConnectionMethod>` (BLE
then, conditionally, NFC) and passing it to a single `.advertise(role, MdocTransportFactory.Default,
options)` call. This compiled, passed `iosSimulatorArm64Test`, and built via `xcodebuild` — none of
which can exercise a real connection — but broke on real hardware: see §3.3 for the root cause
(`MdocTransportFactory.ios.kt` throws for the wallet role on NFC) and its consequence (BLE's
already-started CoreBluetooth peripheral got taken down by NFC's exception, in the same `try`).

The actual fix, entirely within `IosProximityPresenter.kt`:

- `startQrEngagement()` now calls `advertise()` with **only** `listOf(bleConnectionMethod())`. BLE's
  success or failure is fully resolved (and, on success, its `MdocTransport` captured) before NFC is
  touched at all.
- A new private `createNfcTransport()` constructs `NfcTransportMdoc(MdocRole.MDOC, options,
  nfcConnectionMethod())` directly — bypassing `MdocTransportFactory.Default` — and calls its (no-op)
  `.advertise()`. Wrapped in its own `try`/`catch`: any failure logs and reports through the existing
  `nfcNotice`/`NfcStartResult`-shaped channel (a new `NFC_TRANSPORT_UNAVAILABLE` message alongside the
  Finding A/B messages) and returns `null` rather than throwing, so it reads as the same class of
  "NFC-specific, BLE keeps working" event a CardSession start failure already does — never a
  presentation failure.
- The two results are combined into one `List<MdocTransport>` only after each is independently
  resolved. The engagement CBOR's connection methods are now derived from that same combined list
  (`transports.map { it.connectionMethod }`) rather than built separately up front — so a
  `createNfcTransport()` failure drops NFC from the engagement CBOR and the transport list together;
  a reader is never told NFC is available when nothing here can actually answer it.
- `runPresentment()`'s CardSession-start call is now gated on `transports.any { it is
  NfcTransportMdoc }`, not on `nfcEngagementEnabled` directly — starting CardSession with no
  `NfcTransportMdoc` registered to receive its APDUs would reproduce this exact bug's symptom
  (`NfcTransportMdoc`'s static `instances` list empty at tap time) from a different cause.
- Ordering check, since `nfcTransport.start()` (CardSession activation, real Swift/CoreNFC round trip,
  resolves asynchronously via its own completion callback) and `transports.waitForConnection(...)`
  (which calls `MdocTransport.open()` on every transport, including NFC's — a synchronous,
  mutex-guarded `instances.add(this)` with no real I/O) both still run in `runPresentment()`, in that
  order: `open()`'s registration is scheduled the instant control reaches `waitForConnection()`, right
  after `nfcTransport.start()` returns (it doesn't suspend for CardSession to finish activating), so
  registration is guaranteed to finish well before CardSession can plausibly become ready for a
  physical tap. This is what makes `NfcTransportMdoc.processCommandApdu` finding a live, open instance
  possible on this platform for the first time — no change to this ordering was needed.

Scoped Kotlin tests (`:shared-logic:iosSimulatorArm64Test`, including `IosProximityPresentmentTest`)
pass against this change, but — per this same section's opening point — cannot reach the actual bug:
it only manifests against real Bluetooth/NFC hardware. Real-device confirmation is recorded in this
plan's git history / PR description for this fix, not duplicated here.

### Phase 4 implementation notes

The new switch is wired through `ProximityQRInteractor.toggleNfcDataRetrieval` /
`isNfcDataRetrievalAvailable` — **new methods, deliberately kept separate from the existing
`toggleNfcEngagement` seam, not a rename of it or an overload on it.** Two independent reasons, not
just a naming preference:

- **They represent genuinely different features**, not the same feature under two names.
  `toggleNfcEngagement` is Android's narrower NFC engagement/handover (a tap that only hands over BLE
  connection parameters; see §1). `toggleNfcDataRetrieval` is the full NFC data-retrieval transport
  this plan builds. Routing the new switch through the old method would have silently conflated the
  two under one control.
- **`toggleNfcEngagement`'s `componentActivity: PlatformActivity` parameter couldn't represent the new
  control even if the two features were the same one.** `PlatformActivity` has no iOS instance
  (`private constructor()`), so the shared `ProximityQRScreen` only ever constructs the old method's
  call inside a `platformActivity?.let { }` guard that is always null on iOS — meaning that seam is
  permanently unreachable there, not merely unused. `IosProximityQRInteractor.toggleNfcEngagement` was
  reverted to a no-op accordingly; `toggleNfcDataRetrieval` is the real, reachable path.

### Phase 5 implementation notes

Two behaviors adapted from `pagopa/iso18013-ios` (MIT-licensed), with a code comment at each specific
point of adaptation, not just once at the top of the file — see `IosProximityPresenter.kt` and
`iosApp/NfcHceBridge/Sources/NfcHceBridge/NfcHceBridge.swift` for the comments themselves:

- **Late NFC initialization.** Verified against pagopa's actual source before porting anything: the
  real symbols are `isNfcLateEngagement` / `lateNfcInitialization()` in
  `IOWalletProximity/ISO18013.swift` (not `NFCCardEmulator.swift`, where the plan's phase 5 entry
  implied they'd be). Their shape — declare NFC as an available retrieval method immediately, defer
  actually starting the radio to a later, separate call — is what's adapted: `nfcTransport.start()`
  moved out of `startQrEngagement()` (where `nfcConnectionMethod()` still gets embedded in the
  engagement CBOR immediately, unchanged) and into `runPresentment()`, so the radio only starts once
  BLE is genuinely advertising and the QR is on screen. **Pagopa's own trigger point for
  `lateNfcInitialization()` is caller-decided and isn't part of their public API's documented
  behavior** — so which later moment counts as "late" here is this app's own choice, not a ported
  value, unlike the two timing constants below.
- **Cool-down retry and stop delay.** `IosNfcHceTransport.kt` itself was not touched (per this phase's
  constraint) — both behaviors live in `NfcHceBridge.swift`, where the actual `CardSession` calls are.
  Verified directly against this SDK's `CoreNFC.swiftmodule` swiftinterface before porting: the real
  case is `CardSession.Error.transmissionError` (confirmed present, same name pagopa uses — not
  assumed). `respond(to:with:attempt:)` retries exactly that case up to 10 times (`Timing
  .maxRespondRetries`), matching pagopa's own `counter > 10` bound exactly (11 total attempts, not
  10); `stop()` waits 3 seconds (`Timing.stopDelay`) before `stopEmulation`/`invalidate()`, matching
  pagopa's `Task.sleep(for: .seconds(3))` exactly. Both values are cited to pagopa in their own doc
  comments, not asserted as this project's independent reasoning.
- **A real bug this phase's own review caught, worth recording**: the first version of
  `respond(to:with:attempt:)` compiled cleanly via the `swiftc`-archive path
  `:shared-logic:iosSimulatorArm64Test` uses (which always builds at the app's 17.4 floor — see
  `registerNfcHceBridgeBuild` in `shared-logic/build.gradle.kts`), but failed a real `xcodebuild` with
  `'CardSession' is only available in iOS 17.4 or newer` — because `NfcHceBridge`'s own `Package.swift`
  declares a lower floor (`.iOS(.v17)`), and only a real Xcode/SPM build compiles against a package's
  *own* declared floor. **The Kotlin/Native test-linking path cannot catch an iOS-availability bug in
  this Swift bridge**, because it always compiles at 17.4 regardless of what `Package.swift` declares
  — a full `xcodebuild` (already part of this repo's own full verification suite, not something this
  phase added) is what actually exercises the real floor. Fixed by adding the same
  `@available(iOS 17.4, *)` the file's other `CardSession`-touching methods already carry.

### Findings A/B/D/E (post-phase-5 current-state audit)

After phase 5 landed, a read-only audit of the whole NFC implementation as it actually stood (not
as planned) found six gaps, labeled A–F. **Finding C** — `IosNfcHceTransport.ApduDelegate
.processCommandApdu` calling multipaz's `NfcTransportMdoc.processCommandApdu` with no exception
handling around untrusted, pre-authentication bytes — was fixed separately and first; see
`IosNfcHceTransport.kt`'s own doc comment on `ApduDelegate` for that fix. This entry covers the
other four, fixed together because the audit found them to be one gap wearing four faces:
`isNfcDataRetrievalAvailable()` answered a hard-coded `true` regardless of the device, and any
later failure to actually start NFC was swallowed into a log line with nothing reaching a screen.

- **Finding D — a real device/OS support check.** `IosProximityQRInteractor
  .isNfcDataRetrievalAvailable()` now asks `IosProximityPresenter.isNfcDataRetrievalSupported()`,
  which asks a new `NfcHceBridge.isSupported()` class method (Swift) — `NFCReaderSession
  .readingAvailable && CardSession.isSupported`, gated by `#available(iOS 17.4, *)`. Deliberately
  **not** Apple's own `CardSession.isEligible` — that check is `async` (an EEA-region/hardware
  eligibility gate Apple documents as evaluated per-request), and this call site needs to answer
  instantly, at view-model-init time; the audit's own wording allowed skipping a check that isn't
  "synchronous/cheap/without side effects," and an async network-adjacent call is exactly that. A
  device this reports `true` for can still fail to actually start NFC later — see Finding B.
- **Finding A/B — a distinguishable failure reason, reaching the screen.** `NfcHceBridge.swift`'s
  `start(completion:)` used to answer a bare `Bool`; it now answers one of five plain `NSInteger`
  codes (`StartResult` on the Swift side, `NfcStartResult` in `IosNfcHceTransport.kt`) — started,
  not supported, not eligible (`CardSession.isEligible` false), access not accepted (the
  entitlement itself not yet granted — `CardSession.Error.accessNotAccepted`), or a transient
  failure (anything else). This is the one **sensitive-boundary change** in this fix, per the
  convention this plan established in phase 2/5: both `NfcHceBridge.def`'s `startWithCompletion:`
  block and its new `+ (BOOL)isSupported` class method changed shape, documented at the point of
  change in the `.def` itself with the exact code-to-meaning mapping both sides must agree on. A
  plain integer rather than an Objective-C-visible enum type crosses that seam on purpose: an enum
  would need the same explicit `@objc(Name)` treatment `NfcHceBridge`/`NfcHceBridgeDelegate`
  already need (see phase 2's notes above) to avoid Swift's name-mangling trap, while a bare
  `NSInteger` has no linker symbol to get wrong. `IosProximityPresenter.runPresentment()`'s NFC-start
  callback now turns any non-`Started` result into a human message (still naming that BLE keeps
  working — this is a degraded-NFC notice, not a presentation failure) and publishes it on a new
  `IosProximityPresenter.nfcNotice: SharedFlow<String>`, merged into `IosProximityCoordinator
  .qrEvents()` as a new `ProximityQRPartialState.NfcNotice`, which `ProximityQRViewModel` turns
  into a new one-shot `Effect.ShowSnackbar`. `ProximityQRScreen.kt` wires a `SnackbarHostState` into
  `ContentScreen`'s existing `snackbarHost` slot for this — the same slot/pattern
  `SettingsScreen.kt` already uses for its own one-shot snackbar effect, not a new convention.
- **Finding E — the switch now reads back reality.** `IosProximityPresenter` gained
  `isNfcEngagementEnabled(): Boolean`, a getter alongside the pre-existing `setNfcEngagementEnabled`
  setter; threaded through `IosProximityCoordinator.isNfcDataRetrievalEnabled()`,
  `IosProximityQRInteractor.isNfcDataRetrievalEnabled()`, and a new
  `ProximityQRInteractor.isNfcDataRetrievalEnabled(): Boolean` on the shared interface (Android
  answers `false`, same as `isNfcDataRetrievalAvailable`). `ProximityQRViewModel.initializeConfig()`
  now reads this back into `State.nfcDataRetrievalEnabled` instead of leaving it at the state
  class's own default — the flag lives on `IosProximityPresenter`, a Koin `@Single` that outlives
  any one screen visit, so a fresh `ProximityQRViewModel.State` was previously always wrong about a
  switch left on from an earlier visit.
- **Part 4 — a toggle mid-screen is no longer inert.** Flipping the switch while this screen's own
  engagement was already advertising used to change nothing until the *next* time the screen
  opened. `ProximityQRViewModel` now restarts engagement on toggle
  (`restartEngagementForNfcToggle()`: cancels the running collector first, then calls
  `generateQrCode()` again — without the cancel, the old collector would read the restart's
  momentary disconnect as `ProximityQRPartialState.Disconnected` and navigate back on its own). The
  switch is also disabled (`SwitchDataUi.enabled = !state.isLoading`) for the moment the restart is
  in flight, so a second tap can't race the first one's restart.

Verification for this fix followed the same scoped commands as every prior phase
(`:shared-logic:testAndroidHostTest :shared-logic:iosSimulatorArm64Test :shared-ui:testAndroidHostTest
:shared-ui:iosSimulatorArm64Test detekt ktlintCheck`) — including the Swift/cinterop seam change,
since `iosSimulatorArm64Test` genuinely recompiles `NfcHceBridge.swift` and regenerates the cinterop
binding against the edited `.def` (this is a signature-shape check, not an iOS-availability-floor
check, so it isn't the specific gap phase 5's own note above warns a full `xcodebuild` is needed
for).

## 6. Risks and open questions

- **Deployment target bump, `17.0 → 17.4`.** This narrows the device pool for the *whole app*, not
  just NFC users, unless NFC is instead runtime-gated as optional (with a graceful "unsupported"
  path below 17.4) while the app-wide minimum stays at `17.0`. This choice needs a decision before
  phase 1.
- **Apple entitlement approval is an external dependency with unknown lead time and no guaranteed
  outcome.** This is the single biggest schedule risk in this plan. Submit the request as early as
  possible, in parallel with phases 2–3, rather than treating it as a phase-1-blocks-everything
  gate.
- **No simulator path for NFC**, identical to this repo's existing BLE limitation already called
  out in `CLAUDE.md`'s verification guidance. Automated coverage for phases 2–5 is necessarily
  partial (unit/mock-level); full confidence comes only from phase 6.
- **No CBOR/crypto dependency conflicts found.** This uses the Multipaz version already declared in
  `shared-logic/build.gradle.kts` and adds no new *third-party* Swift dependency — only the small
  first-party Swift shim `CardSession` requires (§3.1), which is source we own and maintain, not an
  external package.
- **Android-side parity gap.** Android has NFC *engagement* only, not full NFC *data-retrieval*
  transport (§1). If true cross-platform parity on the fuller feature is wanted later, that is
  separate follow-on work, outside this plan's iOS-only scope.
- **This plan corrects a stale claim in `wiki/KMP_FEASIBILITY.md`** (§2 above); a follow-up edit to
  that document is recommended but is out of scope for this plan's own file.
- **Upstream Multipaz filing has no committed response-time expectation** from OWF maintainers —
  treat it as best-effort community engagement, not a scheduling dependency for this repo's own
  phases.
- **Only `MdocConnectionMethodNfc` (V1) is used, not V2.** `MdocConnectionMethodNfcV2` exists on
  Multipaz's upstream `main` (seen during planning) but not at the `0.99.0` tag this repo pins —
  verified directly by listing that tag's `connectionmethod/` directory. It may become available on
  a future Multipaz upgrade; adopting it is out of scope here.
- **No reference verifier currently completes phase 6 end-to-end — see §8.** The one locally-available
  checkout has real gaps of its own, and a real, unclosed question about which build was actually
  running during on-device testing. Treat phase 6 as blocked on external tooling until that's resolved,
  not as evidence of a defect in this repo's own implementation.
- **`NfcTransportMdoc.failTransport`'s missing-lock crash (§3.4) is a real, standing risk, narrowed
  but not closed.** `IosNfcHceTransport.ApduDelegate`'s AID filter (§3.4) keeps a wrong-AID `SELECT`
  from ever reaching Multipaz, but once the mdoc AID is genuinely selected, any other protocol
  violation within that same session — a duplicate `SELECT`, an out-of-order `ENVELOPE`/
  `GET_RESPONSE`, a bad CLA — still reaches `NfcTransportMdoc.processCommandApdu` unfiltered and still
  crashes the process. Not yet filed upstream (§3.2); until it is (or Multipaz fixes it), this
  narrower, in-session exposure is a known-accepted risk, not a closed one.

## 7. Definition of done for the full feature

Consistent with this repo's `CLAUDE.md` "Definition of done": the change stays within existing
module/architecture boundaries without reformatting unrelated code; targeted tests for the changed
behavior pass per the scoped verification list in §5; `detekt`/`ktlintCheck` remain at zero
findings; the affected iOS target builds, with any Xcode project changes coming only from
`generateIosProject`; no trust/certificate validation is weakened and no secrets or sensitive
credential/PII data are introduced into source, logs, or test fixtures.

Feature-specific closure criteria, additionally: the Apple HCE entitlement has been granted and is
present in the shipped build (done, see §3.4); a real device running iOS 17.4+ has completed a full
NFC-transport presentation end-to-end against a reference verifier (**not done — see §8: blocked on
reference-verifier tooling, not on anything in this repo**); and the upstream Multipaz issue or PR
proposing the equivalent official binding has been filed and is linked from the merged code (not done
— three items now identified for that filing, see §3.2).

## 8. Reference-verifier ecosystem gap — found during phase 6 attempts

Phase 6 (§5) assumes "a real iOS 17.4+ device completes a full NFC-transport presentation against a
reference verifier" is achievable once phases 1–5 are done. Attempting it against a local checkout of
`eudi-app-multiplatform-verifier-ui` (a separate repo, `_projects/eudi-app-multiplatform-verifier-ui`)
surfaced a real gap in that verifier project — but the exact relationship between what's in that
checkout and what was physically tested is **not fully resolved**; both parts are recorded here rather
than only the confirmed half, so this isn't mistaken for more settled than it is.

**Confirmed, from that checkout's own source (not assumed):**

- `verifierApp/src/iosMain/.../IosTransferController.kt` is a complete no-op stub — every
  `TransferController` method (`startEngagement`, `initializeTransferManager`, `sendRequest`, ...) has
  an empty body. The iOS verifier app implements no proximity transport at all, NFC or BLE.
- The Android verifier (`androidVerifierApp`, via `eudi-lib-android-verifier-core:0.1.0`, which pins
  Multipaz `0.93.0` — a different version than this repo's `0.99.0`) wraps
  `com.android.identity.android.mdoc.deviceretrieval.VerificationHelper`
  (`multipaz-android-legacy`). Its own `TransferManagerImpl.kt` has real, working `startQRDeviceEngagement`,
  but `enableNFCDeviceEngagement`, `disableNFCDeviceEngagement`, `onMoveIntoNfcField`, and
  `onReaderEngagementReady` are **all literally `"Not implemented yet"`** — the app layer never
  registers an `NfcAdapter.ReaderCallback`. Traced `VerificationHelper`'s constructor and its `Builder`
  fully: no automatic reader-mode registration happens anywhere in that class either — its own doc
  comment says registering `NfcAdapter` and forwarding tags to `nfcProcessOnTagDiscovered` is the
  *application's* responsibility. Nothing in this checkout, at any layer, does that.
- Independent of the stub above: `VerificationHelper.nfcProcessOnTagDiscovered(tag)` only calls
  `startNfcDataTransport()` (the direct mdoc-AID path, no NDEF) when `dataTransport is DataTransportNfc`
  — i.e. only if the reader had *already selected* NFC as its one connection method for this session.
  That selection (`TransferManagerImpl.onDeviceEngagementReceived`) is
  `MdocConnectionMethod.disambiguate(connectionMethods, MdocRole.MDOC_READER).first()` — and
  `disambiguate()` (Multipaz commonMain) is a plain order-preserving `for` loop, no type preference.
  This repo's own `IosProximityPresenter.startQrEngagement()` always builds `transports` as
  `bleTransports + listOfNotNull(nfcTransport)` — BLE first, unconditionally. So whenever this app
  advertises both, this reader's `.first()` selection is always BLE, `dataTransport is DataTransportNfc`
  is always false, and the mdoc-AID-direct path is structurally unreachable — the reader instead falls
  into its generic NDEF-handover tag-discovery path (`startNfcHandover()`, selecting
  `D2760000850101` then, from something outside this reader's own source — likely Android OS-level tag
  probing, not this app's code — `D2760000850100`), which is what this repo's wallet-side capture
  showed.

**Not resolved — flagged rather than assumed:** whether the physical device used for the on-device
repro was actually running code from this exact checkout, an older build of it, or a different
verifier entirely. The trace above shows this checkout's *current* code has no path that would ever
put the device in NFC reader mode at all — so if this is precisely what was running, it could not have
produced the captured AID probes. An `adb` check against the physical device used for that test was
inconclusive (connection could not run a single stable shell command). **This is an open question, not
a closed finding — resolve it before treating the transport-selection analysis above as the final word
on why NFC data-retrieval doesn't connect against that verifier.**

**Not investigated at all, despite being asked about at one point:** whether `eudi-lib-android-wallet-core`
(the *holder*-side SDK, i.e. what this app's own Android flavor is built on, not a verifier) has any
bearing on NFC data-retrieval reference testing. That term doesn't name a reader/verifier and no claim
about it should be inferred from this section — it was never looked at in this investigation.

**Effect on phase 6 and closure criteria:** even once the open question above is resolved, this
investigation shows the concrete, locally-available reference verifier checkout cannot presently
serve as an NFC-data-retrieval end-to-end test target as-is — either because its own NFC-engagement
wiring is unfinished, or because of the BLE-always-wins transport-selection mismatch, or both. Phase
6's "done when" and §7's closure criteria should be read with that caveat until a verifier capable of
actually selecting NFC as the transport (or one that races/falls back between advertised transports
the way this app's own `waitForConnection` does) is available to test against.

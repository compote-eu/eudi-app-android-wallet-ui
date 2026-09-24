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

## 9. Annex C standalone ("cold") NFC-tap engagement — in progress

§8 traced why the reference verifier can't reach this app's NFC-as-*transport* path at all today: its
own tap-to-engage flow (`VerificationHelper.startNfcHandover()`) does full NDEF handover, never the
mdoc-AID-direct path this app's QR-then-NFC-transport model expects. This section covers a second,
independent feature that closes that gap from the wallet's side: implementing real NFC *engagement*
(ISO 18013-5 Annex C — a tap that starts a session with no QR shown at all), so a verifier's ordinary
tap-to-engage flow has something to engage with, instead of relying on the verifier ever adopting the
wallet's engagement-first model.

**Investigated first, before any implementation** (see that investigation's own findings for the full
trace): Multipaz's `MdocNfcEngagementHelper` (`org.multipaz.mdoc.nfc`, commonMain) already implements
Annex C engagement in a shape functionally equivalent to `pagopa/iso18013-ios`'s hand-rolled virtual
NDEF filesystem (`NFCNDEFCardFileSystem`/`NFCDataTransfer.swift`, credited here for the *pattern* —
one dispatcher, AID-based selected-application state — even though no pagopa code is used; Multipaz's
own API is what's actually called) — a single `processApdu(CommandApdu): ResponseApdu` entry point
that internally tracks a virtual NDEF file pointer and builds the Handover Select message itself from
a supplied `eDeviceKey` and connection methods. Multipaz's own Android binding
(`multipaz-compose/.../MdocNdefService.kt`) proves the intended integration burden is exactly one
method call per APDU, nothing more — confirming option (a), using it as designed, over hand-rolling a
pagopa-style filesystem directly. Decision: **use `MdocNfcEngagementHelper` as designed.**

**Assumption confirmed before implementing** (stated explicitly, per this repo's own working
convention): the wallet listens for QR-based and cold-NFC-tap engagement simultaneously, no explicit
mode switch, mirroring the "scan QR OR tap NFC" model verifiers commonly offer. Two corollaries this
implies, carried into the stages below rather than decided quietly: (1) QR and cold-tap engagement are
independently-keyed — each generates its own `eDeviceKey`/`DeviceEngagement` at its own time (QR
eagerly on screen-open, cold-tap lazily at first NDEF-AID `SELECT`, matching how Multipaz's own
`MdocNdefService.startEngagement()` generates a fresh key per tap) — they are not one engagement shared
two ways; (2) CardSession must be armed before a QR even exists, not "late" the way Phase 6's
NFC-as-transport deliberately starts it (§5's Phase 5 notes) — cold-tap has no QR to wait for.

**Pre-Stage-2 clarification: two live engagement attempts is fine; two live `NfcTransportMdoc`
instances is not.** QR-keyed and cold-tap-keyed engagement listening for their own AID never conflict —
`MdocNfcEngagementHelper` (NDEF AID) and BLE-advertise-for-QR are separate objects. The real constraint
is one layer down: `NfcTransportMdoc`'s own companion `processCommandApdu`/`onDeactivated`
(`multipaz/.../mdoc/transport/NfcTransportMdoc.kt:34-56`) logs `"expected just one"` if `instances.size
> 1` and then **broadcasts every APDU to all of them anyway**, with no disambiguation — confirmed
directly in that source, not assumed. Phase 6's `nfcEngagementEnabled` toggle pre-arms an
`NfcTransportMdoc` immediately alongside QR (well before any tap); if Annex C cold-tap engagement also
completed a handover on the same tap and needed its own `NfcTransportMdoc`, two instances would be
live at once — a real bug (double `sendResponse` into iOS's single-shot completion callback, both
instances independently decrypting the same bytes under different session keys), not a theoretical one.

**Decision: fold Phase 6's NFC-as-transport into Annex C, don't run both.** `nfcEngagementEnabled`/
`createNfcTransport()`'s specific wiring (QR advertising `MdocConnectionMethodNfc`, pre-arming a second
`NfcTransportMdoc`) is retired — Stage 2/3 will not build it alongside Annex C. Once Annex C exists, a
reader wanting NFC transport just taps, same as any cold tap; QR's own engagement CBOR no longer needs
to advertise `MdocConnectionMethodNfc` at all. This makes the "at most one live `NfcTransportMdoc`"
constraint structurally true rather than something to arbitrate at runtime.

**The existing "Share over NFC" switch survives this, repurposed, not removed.** Its old job — letting
a QR-engaged reader continue over NFC instead of BLE — is gone with the wiring above. But the switch was
never really a mode picker; its own doc comment says its job is gating whether CoreNFC's `CardSession`
starts *at all* ("with no way to disable it" otherwise) — a battery/privacy opt-in, not a transport
preference. That concern is at least as strong under Annex C, which needs `CardSession` armed before
any QR exists rather than started late — so the switch keeps its name, default-off, and "is NFC
engagement armed" meaning; only what enabling it constructs underneath changes (cold-tap listening
instead of a pre-armed transport). This doesn't conflict with "simultaneous, no explicit mode switch"
above — that assumption was about not making the user pick *between* QR and NFC once NFC is enabled, not
about removing a persistent enable/disable preference for whether NFC participates at all. Exact UI
copy/flow is Stage 4's job once Stage 3 lands, not decided here.

**Pre-Stage-3 clarification: cold-tap arming is screen-scoped, not QR-attempt-scoped.** When the switch
is on, cold-tap listening must be armed for the entire time the proximity screen is open — a tap can
happen before, during, or independent of any QR interaction — and torn down only on screen-exit or the
switch turning off, never as a side effect of a QR attempt starting or restarting. Today's code cannot
support this as written: `nfcTransport.start()` only runs inside `runPresentment()`, reached solely via
`startQrEngagement()`, which itself opens with `cancel()` (`IosProximityPresenter.kt:197`) — and
`cancel()` calls `nfcTransport.stop()` (line 378). Unchanged, that would tear down and re-arm cold-tap
listening on every `startQrEngagement()` call, not just once per screen visit. `cancel()` is doing two
jobs today (QR-attempt-scoped reset — BLE transport, presentment job, consent state — and full NFC
teardown) that Stage 3 needs to split: cold-tap arm/disarm needs its own entry points driven by
screen-level lifecycle (screen appears / screen closes), not `startQrEngagement()`/`cancel()` — and since
`IosProximityPresenter` is already a Koin `@Single` outliving any one screen visit (Finding E), that
lifecycle pair has no existing hook to piggyback on and needs to be added explicitly (e.g.
`onScreenEntered()`/`onScreenExited()`), with `cancel()` narrowed back to only the QR-attempt-scoped
cleanup it should have had all along.

### Stage 1 — done: the NDEF AID is back, for a real purpose

`iosApp/project.yml`'s `com.apple.developer.nfc.hce.iso7816.select-identifier-prefixes` now lists both
AIDs again (`A0000002480400`, `D2760000850101`). This is **not** a regression of §3.4's removal: that
removal worked around `ApduDelegate` forwarding any accepted AID straight into `NfcTransportMdoc`, which
crashes (via the still-latent `failTransport` locking bug) on anything but the mdoc AID. Until Stage 2
lands, `ApduDelegate`'s existing single-AID guard still rejects a NDEF-AID `SELECT` gracefully with SW
`6A82` before it can reach `NfcTransportMdoc` — re-enabling the entitlement alone changes nothing
observable yet, it only lets the AID reach this app's own code instead of being blocked by the
entitlement filter itself. `generateIosProject` was re-run and a full `xcodebuild` (scheme `EudiWallet`,
iOS Simulator, since the pinned `iPhone 17` simulator isn't installed on this machine, `iPhone 17 Pro`
was used instead) passed clean (`** BUILD SUCCEEDED **`, no `error:` in the log).

### Stage 2 — done: dual-AID routing in `ApduDelegate`

Simplified per the decision above: since Phase 6's parallel `NfcTransportMdoc` pre-arm is retired
rather than kept alongside Annex C, `ApduDelegate` only ever needs to route between the mdoc AID and
the NDEF AID, never arbitrate two live `NfcTransportMdoc` instances.

`ApduDelegate` (`IosNfcHceTransport.kt`) now tracks `SelectedApplication { NONE, MDOC, NDEF }`, set only
by a successful `SELECT APPLICATION` and read by every subsequent APDU (`READ_BINARY`, `UPDATE_BINARY`,
`ENVELOPE`, `GET_RESPONSE` — none of which carry an AID of their own). A `SELECT` for anything other
than the mdoc or NDEF AID is still rejected with SW `6A82` before either helper sees it, unchanged in
spirit from §3.4's fix, just widened from a single-AID match to a two-AID allow-list. The mdoc-AID
branch is byte-for-byte the same call into `forwardToMultipaz`/`NfcTransportMdoc.processCommandApdu` as
before — Finding C's crash-avoidance is untouched. The NDEF-AID branch is new: it routes to
`forwardToEngagementHelper`, whose default implementation bridges `MdocNfcEngagementHelper.processApdu`
(`suspend`, unlike `NfcTransportMdoc`'s callback shape) onto a `CoroutineScope` constructor parameter —
matching this codebase's existing convention for launching a suspend call from a synchronous,
Swift-facing entry point (`IosProximityPresenter`'s/`IosRemotePresenter`'s own
`scope: CoroutineScope = CoroutineScope(Dispatchers.Default)` parameter), not
`IosDocumentProviderBridge.kt`'s convention — that one exports a suspend method to Swift directly, a
different problem from bridging into an already-synchronous callback here.

`IosNfcHceTransport` gained one new piece of public surface: `var engagementHelper:
MdocNfcEngagementHelper? = null`. `null` means no cold-tap engagement is currently offered — a NDEF-AID
`SELECT` is answered exactly as if that AID weren't registered at all. Nothing in this stage constructs
a real `MdocNfcEngagementHelper` (no `eDeviceKey`, no `onHandoverComplete` wiring) — that lifecycle is
entirely Stage 3's job (`IosProximityPresenter`, armed for as long as the "Share over NFC" switch is on
and the screen is open).

Verified: `IosNfcHceTransportTest.kt`'s existing spy pattern was generalized with a second seam
(`forwardToEngagementHelper`), mirroring every existing AID-filter test with its NDEF-AID counterpart —
an unsupported AID reaches neither helper; the mdoc AID reaches multipaz and never the engagement
helper; the NDEF AID reaches the engagement helper and never multipaz; a non-`SELECT` APDU keeps
routing to whichever helper was already selected (checked with a `READ_BINARY` following a NDEF-AID
`SELECT`); and an APDU with nothing selected yet reaches neither. `./gradlew :shared-logic:iosSimulatorArm64Test
:shared-logic:testAndroidHostTest` and `./gradlew detekt ktlintCheck` both passed clean. No `project.yml`/Xcode
project changes this stage, so `generateIosProject`/`xcodebuild` weren't re-run — nothing Xcode-visible changed.

### Stage 3 — done: `IosProximityPresenter` gets a second, independent engagement entry point

Design reviewed and approved before implementation (see that design's own write-up for the full
`onScreenEntered`/`onScreenExited`/`armColdTapEngagement` rationale); this records what was actually
built and what changed relative to the approved design.

**`IosProximityPresenter`**: added `suspend fun onScreenEntered()` (reconciles cold-tap engagement with
the "Share over NFC" switch — arms or disarms — idempotently, safe on every engagement restart within a
visit) and `fun onScreenExited()` (unconditional teardown). `armColdTapEngagement()`/
`disarmColdTapEngagement()` are the private implementation, guarded by a new `engagementHelperArmed`
flag rather than `nfcTransport.engagementHelper != null` — the corrected version from the design review,
since a null-check alone would leave a later retry believing NFC was already armed after a failed
`CardSession` start. `onColdTapHandoverComplete()` builds the one `NfcTransportMdoc` a completed cold
tap uses and feeds it into the existing `runPresentment()`, which gained one new parameter
(`handover: DataItem = Simple.NULL`, default preserving QR's existing call site) rather than being
duplicated. Phase 6's `createNfcTransport()` and its call site in `startQrEngagement()` are deleted —
QR engagement is BLE-only now; `nfcConnectionMethod()` is reused, not removed, by cold-tap's static
handover. `cancel()` no longer calls `nfcTransport.stop()` — that responsibility moved to
`onScreenExited()`/`disarmColdTapEngagement()`, so a QR retry mid-visit no longer collaterally
disarms an already-listening cold tap.

**One addition beyond the literal approved design, found while implementing, not silently shipped:**
`onColdTapHandoverComplete()` guards against `presentmentJob?.isActive == true` before launching a new
presentment. QR and cold-tap engagement are simultaneously armed by design, so a physical tap can
complete while a QR-originated presentment is already running (a real race, not theoretical, given
"both available at once, whichever happens first wins" — see the assumption confirmed before Stage 1).
Without this guard, `presentmentJob` would be silently overwritten — leaking the first coroutine and
racing two `runPresentment` invocations over the same single-instance state (`transport`,
`pendingConsent`, `pendingData`, `sharedDocuments`). The guard is a minimal one: first engagement to
actually reach `onColdTapHandoverComplete`/`runPresentment` wins, the loser is dropped (logged, not
cancelled — nothing needs un-building on that side). It does not address the narrower, still-open
question of whether the *reverse* direction needs handling too (see the second flagged limitation
below).

**`IosProximityCoordinator`**: `qrEvents()` now calls `presenter.onScreenEntered()` before
`presenter.startQrEngagement()`, inside the same flow builder — reached on the initial screen visit,
`Event.Init` retries, and `restartEngagementForNfcToggle()`'s restart, confirmed directly against
`ProximityQRViewModel.kt`. `cancel()` now calls `presenter.onScreenExited()` alongside `presenter.cancel()`
— reached from the QR screen's `GoBack`/`cleanUp()` and both the request and success screens'
`stopPresentation()`. No new `ProximityQRInteractor` contract method, no Android no-op stub, no new
expect/actual — confirmed, not assumed, by tracing every call site before writing to this file.

**Two limitations flagged during design review, still open, not addressed by this stage:**

1. A second physical tap within the same screen visit, after one already completed a handover, reuses
   the same `MdocNfcEngagementHelper`/`eDeviceKey` for the rest of the visit — nothing re-arms with a
   fresh key mid-visit unless the switch is toggled off and back on. Harmless (a stale-but-valid helper
   answers), but diverges from Android's own per-tap key regeneration (`MdocNdefService.startEngagement()`
   generates fresh each time). Undecided; not blocking.
2. The switch flipped off exactly while a physical NDEF handover is mid-flight: `disarmColdTapEngagement()`
   would null out `engagementHelper` out from under an in-progress conversation, answering the reader's
   next APDU as if the AID weren't registered at all. A narrow race, not defended against.

**Verified**: `./gradlew :shared-logic:testAndroidHostTest :shared-logic:iosSimulatorArm64Test` (12/12
`IosProximityPresentmentTest` cases and 8/8 `IosNfcHceTransportTest` cases pass, confirmed from the
generated `TEST-*.xml` result files, not just `BUILD SUCCESSFUL`) and `./gradlew detekt ktlintCheck`
both passed clean. `generateIosProject` regenerated the Xcode project, and a full `xcodebuild` (scheme
`EudiWallet`, `iPhone 17 Pro` simulator — `iPhone 17` isn't installed on this machine) produced
`** BUILD SUCCEEDED **` with no `error:` in the log, per `CLAUDE.md`'s `NfcHceBridge` rule.

### Stage 4 — done: UI/interactor copy corrected for the repurposed switch

Per the decision recorded above, the "Share over NFC" switch keeps its name, its default-off state, and
its `ProximityQRInteractor` contract (`isNfcDataRetrievalAvailable`/`toggleNfcDataRetrieval`/
`isNfcDataRetrievalEnabled`, unchanged signatures) — only what enabling it constructs underneath moved,
and only its *copy* needed correcting to stop describing the retired meaning.

**Checked, not assumed, before touching anything**: whether the switch's own visible label
(`proximity_qr_enable_nfc_data_retrieval`, "Share over NFC") was itself stale. It isn't — "share over
NFC" is generic enough to be accurate under either model, old or new, so it needed no change. What
*was* stale, found by tracing every doc comment and code comment touching this feature end to end, not
just the label: the composable's own KDoc ("offered as a sibling transport to BLE"), the shared
`ProximityQRInteractor` contract's three doc comments (`isNfcDataRetrievalAvailable`'s "Annex 8... a
sibling transport to BLE", `toggleNfcDataRetrieval`'s "for the *next* `startQrEngagement`"), and smaller
comments in `IosProximityCoordinator.kt`, `IosProximityInteractors.kt`, `ProximityQRInteractorImpl.kt`
(Android's no-op), and its test. All corrected to describe cold-tap (Annex C) engagement as an
independent alternative to scanning the QR, not a transport choice within it.

**One real gap, not just stale prose**: nothing on screen told the user what the switch now actually
does differently — before, its effect was an invisible transport-selection detail; now, enabling it
means "you can share by tapping your phone instead of scanning the QR at all," a materially more
significant, user-visible capability. Added a new string,
`proximity_qr_nfc_data_retrieval_description` ("Tap your phone against the reader device to share
instead of scanning the QR code."), rendered as a supporting line under the switch in
`NfcDataRetrievalSection` — mirroring the exact label-plus-supporting-text pattern the sibling
`NFCSection` composable in the same file already uses for Android's own NFC copy, not a new UI pattern.

**Verified**: `./gradlew :shared-ui:testAndroidHostTest :shared-ui:iosSimulatorArm64Test
:proximity-feature:test` — `ProximityQRViewModelTest` 12/12 on both Android-host and iOS-simulator
targets, `TestProximityQRInteractor` 17/17 on both `dev`/`demo` flavors, confirmed from the generated
`TEST-*.xml` files. `./gradlew detekt ktlintCheck` passed clean. `./gradlew :androidApp:assembleDevDebug`
built and packaged successfully (not just `UP-TO-DATE`: `mergeLibDexDevDebug`/`packageDevDebug`/
`assembleDevDebug` all actually executed), confirming the new composable and string resource compile
and package correctly for Android. No `iosApp/project.yml`, `NfcHceBridge`, or other Xcode-project-level
change this stage — pure Kotlin/Compose-resource work reached entirely through the same `SharedKit`
build step `:shared-ui:iosSimulatorArm64Test` already exercises — so `generateIosProject`/`xcodebuild`
were not re-run; nothing Xcode-visible changed.

**Annex C cold-tap NFC engagement is feature-complete across Stages 1–4**, with two known, undecided
limitations carried from Stage 3 (§9, not resolved by this stage): a second same-visit tap reuses the
first tap's engagement key rather than regenerating one; and toggling the switch off while a physical
NDEF handover is mid-flight can pull the engagement helper out from under it. Both remain open questions
for a future pass, not blockers.

### Real-device crash found after Stage 3: `MdocNfcEngagementHelper` has no reentry protection

Not a design oversight Stage 3's review missed — the double-presentment guard it added
(`onColdTapHandoverComplete`'s `presentmentJob?.isActive` check) worked exactly as designed and is what
surfaced this. First real-device test of Annex C hit: cold-tap engagement completed via a physical tap
while a QR/BLE presentment was already active; the guard correctly logged and ignored the duplicate
handover; shortly after, the app crashed with an uncaught `kotlin.IllegalStateException: "Check failed."`
inside `MdocNfcEngagementHelper.raiseError`, called from `processApdu`, on a later APDU.

**Root cause, traced before touching anything.** `MdocNfcEngagementHelper`'s entire public surface is
its constructor plus `processApdu` — no `close()`, `dispose()`, or "handover already completed" flag
anywhere in its own state (`negotiatedHandoverState`, `selectedFileId`, `selectedFilePayload`,
`ndefApplicationSelected`, `inError`). Nothing stops `processSelectFile`'s static-handover branch for
file `0xe104` (the NDEF file) from re-running its whole construction — rebuild `DeviceEngagement`,
rebuild the Handover Select message, call `onHandoverComplete` again — if that file is selected a second
time, which real hardware evidently does. **This is not a bug in that class so much as an assumption
baked into its design, and the assumption is specifically about who does AID-level routing:** on
Android, `MdocNfcEngagementHelper` is only ever reached from `MdocNdefService`, a `HostApduService`
bound to the NDEF AID alone — the OS's own AID-based routing between separate `HostApduService`
instances is what guarantees a reader that moves on to the mdoc AID never reaches this instance again,
for free, without the class needing to defend itself. iOS has no OS-level router of that kind:
`ApduDelegate` does AID routing itself (§9 Stage 2), so the guarantee Android gets from its platform has
to be provided by this app instead — and until this fix, nothing did. Confirmed directly in code, not
assumed: `onColdTapHandoverComplete`'s guard returned before touching `nfcTransport.engagementHelper` or
`engagementHelperArmed` at all, so the same helper instance — done, from the app's perspective — kept
receiving every subsequent NDEF-AID APDU for the rest of the physical tap.

The literal `"Check failed."` text doesn't come from any of `MdocNfcEngagementHelper`'s own `check()`
calls (all of them carry custom messages) — it matches Kotlin's default message for a bare
`check(condition)` with no lambda, and several exist one level down in code `processApdu` reaches into
(`CommandApdu.kt`, `MdocConnectionMethodNfc.kt`, `MdocConnectionMethodBle.kt`, `NdefRecord.kt`). Which
exact one fired wasn't pinned down without a symbolicated device crash log — not needed to fix this: the
mechanism (a stale helper instance reachable at all) is confirmed independent of which internal
assumption it tripped.

**Fix**: `disarmColdTapEngagement()` used to conflate two responsibilities — stop routing to the helper,
*and* tear down `CardSession` — which can't both happen on a successful handover, since `CardSession`
must stay alive for the mdoc-AID data transfer that continues on the same physical tap. Split:

```kotlin
private fun clearEngagementHelper() {
    engagementHelperArmed = false
    nfcTransport.engagementHelper = null
}

private fun disarmColdTapEngagement() {
    clearEngagementHelper()
    nfcTransport.stop()
}

private fun onColdTapHandoverComplete(...) {
    clearEngagementHelper()   // unconditional, before the presentmentJob check — handover is done
                              // either way, acted on or ignored
    if (presentmentJob?.isActive == true) { ...; return }
    ...
}
```

Once `nfcTransport.engagementHelper` is `null`, `ApduDelegate`'s existing "no helper armed" fallback
(Stage 2, unchanged) answers any further NDEF-AID APDU with
`RESPONSE_STATUS_ERROR_FILE_OR_APPLICATION_NOT_FOUND` — the same clean answer a reader gets if cold-tap
were never armed at all. No `ApduDelegate` change was needed.

One new, narrower edge case this fix itself surfaces: clearing `engagementHelperArmed` unconditionally
means a later `onScreenEntered()` (e.g. a QR retry firing while a cold-tap-originated presentment is
still active) would otherwise try to re-arm — restarting `CardSession` mid-conversation. Closed with the
same guard `onColdTapHandoverComplete` already uses: `armColdTapEngagement()` now also skips arming
while `presentmentJob?.isActive == true`.

**Verified**: a new test in `IosNfcHceTransportTest.kt` uses a real `MdocNfcEngagementHelper` (not a
spy) and the default `forwardToEngagementHelper` wiring (not overridden) to drive the actual sequence —
select NDEF AID, select the capability container, select the NDEF file (handover completes) — then
confirms `engagementHelper` is `null` afterwards and that a second `SELECT FILE` on the NDEF file is
answered with SW `6A82` rather than reaching the stale helper again. `./gradlew
:shared-logic:testAndroidHostTest :shared-logic:iosSimulatorArm64Test` (9/9 `IosNfcHceTransportTest`
cases pass, confirmed from the generated `TEST-*.xml`) and `./gradlew detekt ktlintCheck` both passed
clean. `generateIosProject` + a full `xcodebuild` (`EudiWallet`, `iPhone 17 Pro` simulator) produced
`** BUILD SUCCEEDED **` with no `error:` in the log.

Ready for another real-device test.

### Second real-device finding: iOS showed its own Wallet/contactless picker instead of routing to this app

Next real-device test surfaced a different symptom, unrelated to the crash above: tapping the phone
against the reader immediately showed iOS's own system Wallet/contactless card picker — this app's
`CardSession` never got asked at all.

**Root cause, researched against Apple's own documentation before touching anything** (not the AID
content — no evidence anywhere in Apple's docs that the NDEF AID is reserved or special-cased; ruled
out). iOS decides which app's `CardSession` answers an NFC field-detect event through exactly two
documented mechanisms, and this app implemented neither:

- **Default contactless app** (background/passive case) — a user-chosen Settings preference, gated by
  the `com.apple.developer.nfc.hce.default-contactless-app` entitlement.
- **`NFCPresentmentIntentAssertion`** (foreground/active case — this app's actual scenario, since
  cold-tap only ever arms while the proximity screen is open): "Eligible apps running in the foreground
  can prevent the system default contactless app from launching... acquire a presentment intent
  assertion when the user expresses an active intent to perform an NFC transaction." Apple's documented
  required sequence: **acquire the assertion first, then construct `CardSession`.** `NfcHceBridge.swift`
  went straight to `CardSession()` with no prior assertion at all — confirmed by grep, zero references
  anywhere in this codebase before this fix. This matches the reported timing exactly: the routing
  decision happens before any app's `CardSession` is consulted, so its absence explains an *immediate*
  system picker precisely.

**Fix**, in `NfcHceBridge.swift`'s `startCardSession()`: acquire `NFCPresentmentIntentAssertion` before
constructing `CardSession`, per Apple's documented sequence:

```swift
let intentAssertion: NFCPresentmentIntentAssertion
do {
    intentAssertion = try await NFCPresentmentIntentAssertion.acquire()
} catch NFCPresentmentIntentAssertion.Error.systemEligibilityFailed {
    return .notEligible
} catch NFCPresentmentIntentAssertion.Error.systemNotAvailable {
    return .transientFailure
} catch {
    return .transientFailure
}
presentmentIntentAssertion = intentAssertion
// ... then CardSession() as before, clearing presentmentIntentAssertion on any failure path too
```

No new `StartResult` case needed — checked first, per usual practice here, rather than assumed:
`NFCPresentmentIntentAssertion.Error` has exactly two documented cases, both of which fit an existing
`StartResult` cleanly. `.systemEligibilityFailed` maps to `.notEligible` (a distinct eligibility gate
from `CardSession.isEligible`, but the same user-facing "NFC unavailable right now" outcome).
`.systemNotAvailable` (the mandatory cool-down between assertions) maps to `.transientFailure` (it's
inherently transient — will resolve after the cool-down). Reusing `.accessNotAccepted` was considered
and rejected: that case's existing message is specifically about the HCE entitlement's own approval,
a genuinely different permission system from the presentment intent's separate, first-use system
prompt — conflating the two would mislead future debugging. `NfcHceBridge.def`'s code-meaning table
(codes 2 and 4) was updated to document both sources for each, not just the original `CardSession` one.
`presentmentIntentAssertion` is released (set to `nil`) in `stop()` and on any `CardSession` construction
failure, rather than left to expire on its own.

**Lifecycle, researched precisely rather than assumed** (via Apple's own DocC JSON, since the rendered
page returns only a title to a plain fetch): an acquired assertion is hard-capped at **15 seconds**
(also expires early if the object deinitializes or the app backgrounds), followed by a **mandatory
15-second cool-down** before a new one can be acquired. **One acquisition does not cover the whole
armed period** — `IosProximityPresenter`'s cold-tap engagement can stay armed for a whole multi-minute
screen visit (`onScreenEntered`/`onScreenExited`), far longer than 15 seconds, and Apple's API gives no
way to hold continuous coverage across that: even reacquiring immediately on expiry still leaves an
unavoidable 15-second gap (the cool-down) where suppression lapses. This fix covers the window right
around arming and a reader's first encounter — the realistic case this bug report actually described —
not indefinite coverage for the rest of a long-idle screen visit. **Flagged as a third open, undecided
limitation, alongside Stage 3's other two** (stale key on a same-visit second tap; switch-off mid-
handover race): whether the 15-second gap matters in practice for later taps in a long visit is
unverified, and Apple's API doesn't offer a way to close it even if it does.

**Sanity-checked against pagopa/iso18013-ios, per this project's usual practice of citing what's
adapted from there — this piece is not.** Fetched that repo's actual `NFCCardEmulator.swift` directly:
it declares a `presentmentIntent: NFCPresentmentIntentAssertion?` stored property and a comment
referencing "failure to acquire NFC presentment intent assertion," but **never actually calls
`.acquire()` anywhere** — the property is only ever assigned `nil`. That file has the same gap this
app did; it is not a working reference for this piece. This fix is standard Apple platform API usage,
applied directly from Apple's own `NFCPresentmentIntentAssertion` documentation, not adapted from
pagopa — documented as such in both the file-level and method-level comments in `NfcHceBridge.swift`.

**`com.apple.developer.nfc.hce.default-contactless-app`, tried and reverted.** Added to `project.yml`
for the secondary, background/passive case per the investigation's recommendation, then verified —
empirically, via a real signed device build (`Martin's iPhone`, `-allowProvisioningUpdates`), not a
simulator build, since simulator builds don't validate entitlements against Apple's granted capabilities
the way device signing does — and it fails outright:

```
error: Provisioning profile "iOS Team Provisioning Profile: eu.compote.euidi.dev" doesn't include the
Default Host Card Emulation (HCE) App capability. ... needs to be assigned to your team and bundle
identifier by Apple in order to be included in a profile.
error: Entitlement com.apple.developer.nfc.hce.default-contactless-app requires approval from Apple to
include in a profile. ... To continue building for device during request processing, remove entitlement
and add upon approval.
```

This confirms what Apple's own entitlement documentation says directly: this key needs its own,
separate Apple approval — distinct from, and not covered by, the `com.apple.developer.nfc.hce` grant
this App ID already has — and Apple's docs frame it as "primarily designed for banking and payment apps
in specific regions [EEA]," not identity/mdoc use cases. **Reverted, per Apple's own suggested
workaround** ("remove entitlement and add upon approval") — leaving it in would have broken every
real-device build until that separate approval is requested and granted. **Action item, not done**:
file the request for this capability if the background/passive default-app case is still wanted; until
then, only `NFCPresentmentIntentAssertion` (the foreground fix above, which needed no new approval and
is what this app's actual cold-tap flow uses) is in place.

**Verified**: `./gradlew :shared-logic:testAndroidHostTest :shared-logic:iosSimulatorArm64Test` and
`detekt ktlintCheck` both passed clean (no Kotlin production/test source changed this pass — only
`NfcHceBridge.swift`, `NfcHceBridge.def`'s doc comment, and `project.yml`). `generateIosProject` +
`xcodebuild` succeeded on both `iPhone 17 Pro` (simulator) and `Martin's iPhone` (real device,
`-allowProvisioningUpdates`) for the final, reverted-entitlement state — the device build is what
actually exercises Swift's real `CoreNFC`/`NFCPresentmentIntentAssertion` compilation and signing
resolution, stronger evidence than the simulator-only check alone.

Ready for another real-device test.

### Diagnostic logging added ahead of the next real-device test

Logging-only, no protocol/transport logic changed. Covers the whole cold-tap path so its timing can be
correlated against the verifier side: `onScreenEntered`/`onScreenExited`, every `armColdTapEngagement`
guard (already armed / presentment active / unsupported), `MdocNfcEngagementHelper`'s
`onHandoverComplete`/`onError` callbacks, `onColdTapHandoverComplete`/`clearEngagementHelper`,
`ApduDelegate.processCommandApdu`'s AID routing state per incoming APDU (`none`/`mdoc`/`ndef`) — so a
missing log line there directly shows whether the wallet ever received any APDU at all, distinguishing
"system picker intercepted before us" from "we got the APDU and did something else" — and, on the Swift
side, `NfcHceBridge.swift`'s `NFCPresentmentIntentAssertion.acquire()`/`CardSession` construction/
`startEmulation()` and every `eventStream` case, none of which had any logging before this. Uses
multipaz's own `Logger.i`/`Logger.w` (Kotlin, auto-timestamped) and this repo's existing Swift
`print("TAG: message")` convention (`iOSApp.swift`/`DocumentRegistration.swift`) with a new `NFC-HCE:`
tag — `NfcHceBridge.swift` itself had no prior logging convention to match. The Swift side's timestamp
is explicit (ISO 8601 with fractional seconds) rather than left to the console's own capture time, so
it lines up by literal string against Kotlin's own `Logger` timestamps — the point being to read a
device console as one timeline across the Swift/Kotlin boundary when checking the
`NFCPresentmentIntentAssertion` 15-second-expiry hypothesis against exactly when a physical tap
happened.

Capture from the device with either: Xcode's own console (Window > Devices and Simulators > select the
device > Open Console, filtered to process `EudiWallet`, then a text filter for `NFC-HCE` or
`IosProximityPresenter`/`IosNfcHceTransport` to isolate the cold-tap path from everything else the app
logs) — or, for a saved transcript to diff against the verifier's own logs afterward,
`idevicesyslog -p EudiWallet | grep -E "NFC-HCE|IosProximityPresenter|IosNfcHceTransport"` (or
unfiltered `idevicesyslog` piped through the same `grep -E` if `-p` doesn't match reliably), redirected
to a timestamped file.

Verified: `:shared-logic:testAndroidHostTest :shared-logic:iosSimulatorArm64Test` (9/9
`IosNfcHceTransportTest`, 12/12 `IosProximityPresentmentTest`, unaffected) and `detekt`/`ktlintCheck`
clean. `generateIosProject` + `xcodebuild` (`EudiWallet`, `iPhone 17 Pro` simulator) succeeded — required
since `NfcHceBridge.swift` changed, per `CLAUDE.md`'s rule.

### Third real-device finding: the presentment-in-progress guard was too coarse, blocking every arm attempt

The new logging above did its job immediately: the very next real-device test log showed
`onScreenEntered: nfcEngagementEnabled=true` followed immediately by `armColdTapEngagement: a
presentment is already active, skipping` — the `presentmentJob?.isActive` guard added earlier today
(alongside the `clearEngagementHelper()` crash fix, as a "narrowing-only improvement") firing on the
first arm attempt, before any cold-tap had ever happened. `NFCPresentmentIntentAssertion.acquire()`/
`CardSession` were never even called — the system picker symptom from the previous finding had a second,
compounding cause: nothing was armed to intercept the tap in the first place.

**Root cause, confirmed by tracing the exact assignment, not assumed.** `presentmentJob` is set inside
`startQrEngagement()` the instant `scope.launch { runPresentment(...) }` runs — right as the QR becomes
visible — and `runPresentment()`'s first real step, `transports.waitForConnection(...)`, has no timeout
in multipaz's own implementation. So `presentmentJob.isActive` is `true` continuously from "QR just
appeared" through "a reader eventually connects," which is most of a typical screen visit — it reflects
coroutine liveness, not whether an actual document exchange is underway. The guard's *intended* scope,
per its own doc comment, was narrower: protect against racing two `runPresentment()` calls once a real
exchange (`transport`/`pendingConsent`/`pendingData`/`sharedDocuments` genuinely in play) is underway —
not "QR is merely displayed, waiting for any connection."

**Fix**: replaced the check in both `armColdTapEngagement()` and `onColdTapHandoverComplete()` with a
new `isPresentmentActuallyInProgress()`:

```kotlin
internal fun isPresentmentActuallyInProgress(): Boolean =
    mutableState.value is IosProximityState.Requesting || mutableState.value is IosProximityState.Sending
```

`Engaging` (QR shown, nothing connected) no longer counts; only `Requesting`/`Sending` — the states
where a reader has actually connected and the shared mutable state the original guard cared about is
genuinely in flight — do. This still catches the original race (if QR's own exchange has reached
`Requesting`/`Sending` when a cold tap completes, the guard still fires exactly as before) while no
longer blocking the common case the bug actually hit.

**A known, pre-existing, separate asymmetry, flagged here rather than fixed**: there is no equivalent
guard on the *other* direction. If a cold-tap exchange is genuinely `Requesting`/`Sending` and QR's own
`waitForConnection()` *then* succeeds, QR's side has nothing stopping it from proceeding into its own
`Iso18013Presentment(...)` call and racing the same shared state from the opposite direction. This gap
predates today's guard entirely (the guard was only ever on the cold-tap side) and is out of scope for
this fix — recorded here so it isn't lost, not proposed to be solved now.

`mutableState` was widened from `private` to `internal` (matching this file's existing "internal so a
test can..." convention, e.g. `deviceEngagement()`) specifically so a test can drive
`isPresentmentActuallyInProgress()` without a real connection — the Simulator has no NFC hardware and no
BLE radio, so driving `armColdTapEngagement()`/`onScreenEntered()` fully end to end can't distinguish
"skipped by this guard" from "proceeded, then stopped at the next guard down" from the outside; testing
the actual changed logic directly was the more honest option than a misleading pseudo-integration test.

**Verified**: `:shared-logic:testAndroidHostTest :shared-logic:iosSimulatorArm64Test` — 14/14
`IosProximityPresentmentTest` cases pass (12 previous + 2 new:
`isPresentmentActuallyInProgress_is_false_while_merely_engaging` and
`isPresentmentActuallyInProgress_is_true_while_requesting_or_sending`), confirmed via the generated
`TEST-*.xml`. `detekt`/`ktlintCheck` clean. `generateIosProject` + `xcodebuild` (`EudiWallet`,
`iPhone 17 Pro` simulator) succeeded, no errors.

Ready for another real-device test.

### Fourth real-device finding: engagement, handover, and CardSession all worked — this morning's own crash fix over-corrected

First real end-to-end progress: `NFCPresentmentIntentAssertion` + `CardSession` + handover all
succeeded. The very next thing failed instead, confirmed from both device logs together: `on
HandoverComplete` fired (wallet log), the wallet immediately cleared the engagement helper and started
mdoc-AID presentment, and 14ms later a trailing `READ_BINARY` arrived on the NDEF AID — a real reader
legitimately reading the NDEF file once more before it notices engagement is done and switches AIDs
itself. The wallet correctly rejected it with SW `6A82` (no crash, per this morning's fix) — but the
reader's own `NfcIsoTag.readBinary` treats *any* non-success status as fatal and aborted the whole tap,
never reaching the mdoc-AID `SELECT` that should have followed.

**Root cause: this morning's `clearEngagementHelper()` fix (the one that closed the repeat-`SELECT`
crash) was broader than the crash it was closing.** Traced directly against Multipaz's own reader-side
source (`NfcIsoTag.kt`, `mdocReaderNfcHandover.kt`, `scanMdocReader.kt`) and `MdocNfcEngagementHelper`'s
own `processReadBinary`:

- Multipaz's reader-side `mdocReaderNfcHandover()` does exactly two reads on the NDEF file (length,
  then content) and returns — no trailing read is part of its own documented sequence. The extra read
  is most plausibly Android's own `NfcAdapter.enableReaderMode()` performing its own OS-level NDEF check
  before invoking the app's callback (a well-documented mechanism, suppressed only by
  `FLAG_READER_SKIP_NDEF_CHECK`) — noted here as context for *why* it happens, not as something to
  chase down or "fix": the correct wallet-side behavior is to answer it correctly regardless of why it
  was sent.
- `NfcIsoTag.readBinary()` throws on any non-success status unconditionally — no tolerance for `6A82`
  as "no more data" anywhere. Not a library bug to petition upstream: relying on a reader tolerating an
  error response would be relying on non-obvious behavior, when serving valid content for a still-
  selected file is simply the correct Type 4 Tag behavior to have in the first place.
- `MdocNfcEngagementHelper.processReadBinary` is a pure, side-effect-free function of already-built
  state (`selectedFilePayload`) — it has no "handover already completed" check of its own and answers
  correctly no matter how many times it's called. The *only* reason the wallet answered `6A82` was its
  own `clearEngagementHelper()` call inside `onColdTapHandoverComplete`, which nulled the entire helper
  reference the instant handover completed — rejecting a request the helper itself would have handled
  fine. The actual crash risk this morning's fix was closing is narrower: re-entering
  `processSelectFile`'s handover-construction logic via a *repeat* `SELECT` (application or file), not
  a plain `READ_BINARY`.

**Fix**: added `IosNfcHceTransport.ndefHandoverCompleted: Boolean`, reset alongside `engagementHelper =
null` everywhere that already happens (`clearEngagementHelper()`, the failed-start branch in
`armColdTapEngagement()`). `ApduDelegate` gained a matching `ndefHandoverCompletedProvider`, checked
only inside the `SelectedApplication.NDEF` sticky-routing branch:

```kotlin
SelectedApplication.NDEF -> {
    if (ndefHandoverCompletedProvider() && command.ins == Nfc.INS_SELECT) {
        // reject with SW 6A82 — never reaches the engagement helper
    } else {
        forwardToEngagementHelper(command) { ... }   // READ_BINARY/UPDATE_BINARY, or any SELECT
                                                       // before completion — unchanged
    }
}
```

`onColdTapHandoverComplete()` now sets `nfcTransport.ndefHandoverCompleted = true` instead of calling
`clearEngagementHelper()` — the helper instance stays wired and keeps answering reads correctly. Nothing
needs to explicitly detach it once the reader *does* move on: a `SELECT` for the mdoc AID is checked
unconditionally by `ApduDelegate`, before `selectedApplication`'s current value is even read, so it
reaches `NfcTransportMdoc` and the NDEF helper simply stops being reachable — no new code needed there.
`disarmColdTapEngagement()` (screen exit / switch off) is unaffected and still does a full reset via
`clearEngagementHelper()`.

One clarification on scope, found while implementing rather than assumed from the design: the top-level
AID-select branch (which chooses `MDOC` vs `NDEF` vs "unsupported") was deliberately left untouched, so
a *repeat* `SELECT APPLICATION(NDEF)` still always reaches the helper regardless of
`ndefHandoverCompleted` — this is safe, not a gap, since `MdocNfcEngagementHelper.processSelectApplication`
is itself idempotent (just re-sets a flag, no reconstruction). Only a repeat `SELECT FILE` — the actual
crash trigger, reached via the sticky-routing branch since it isn't a `SELECT APPLICATION` — is what
the new gate blocks.

**Verified**: `IosNfcHceTransportTest.kt`'s real-`MdocNfcEngagementHelper` test (the one proving the
original crash fix) was rewritten to prove both halves of the corrected fix against the actual
crash-prone class: a trailing `READ_BINARY` after handover now succeeds, and a repeat `SELECT FILE`
is still rejected — plus a new test confirming the mdoc-AID path is completely unaffected by
`ndefHandoverCompleted`. `:shared-logic:testAndroidHostTest :shared-logic:iosSimulatorArm64Test` — 10/10
`IosNfcHceTransportTest` cases pass, confirmed via `TEST-*.xml`. `detekt`/`ktlintCheck` clean.
`generateIosProject` + `xcodebuild` (`EudiWallet`, `iPhone 17 Pro` simulator) succeeded, no errors.

By this point every piece of the cold-tap path has been individually confirmed working on real hardware:
`NFCPresentmentIntentAssertion` acquisition, `CardSession` construction/start, handover completion, and
now the trailing-read/repeat-select distinction immediately after it. Ready for another real-device
test — this should be the last fix before a fully successful end-to-end tap.

### Fifth finding: iOS's own "Hold Near Reader" system modal sits over this app's consent screen during cold-tap — a transparency stopgap was tried and reverted; the real fix is an upstream Multipaz version bump

**The problem, confirmed real via real-device testing, not a wallet bug.** During cold-tap, once a
`DeviceRequest` arrives and `awaitConsent` publishes `IosProximityState.Requesting`, CoreNFC's own
"Hold Near Reader" system modal (driven by the active `CardSession`, not this app) stays on top of this
app's own consent screen — the user cannot see or reach accept/decline underneath it. Confirmed against
Apple's own documentation (`CardSession` DocC page): "emulation triggers the system modal UI to display
over the app," with a customizable `alertMessage: String` property but no documented way to dismiss or
hide the modal short of ending the session (`stopEmulation(status:)`/`invalidate()`), which this app
obviously can't do mid-consent without abandoning the exchange.

**Two-tap-same-session feasibility, re-verified from the pinned `0.99.0` sources jar directly (not the
earlier extraction, and not carried forward from an earlier restated summary):**

```kotlin
class SessionEncryption(
    val role: MdocRole,
    private val eSelfKey: EcPrivateKey,
    private val remotePublicKey: EcPublicKey,
    private val encodedSessionTranscript: ByteArray,
)
```

`org.multipaz.mdoc.sessionencryption.SessionEncryption`, pinned `0.99.0` sources, lines 48-53. No
transport reference in the constructor — it's keyed purely on the ECDH pair and the session transcript.
This supports **transport-agnostic**, which is as far as this was ever actually verified — not
"cryptographically impossible," a characterization that surfaced in this conversation but was never
the finding and doesn't match what's in the source. Whether a second NFC tap can resume the *same*
session in practice depends on whether `Iso18013Presentment`/the engagement helpers keep that
`SessionEncryption` instance alive across a transport handoff — not traced here, and not a crypto-layer
blocker either way.

**GitHub search against `openwallet-foundation/multipaz`'s own issues/PRs — one real, on-topic result,
and an honest "nothing found" for the rest.** Issue #1875 ("NFCv2: Support double taps for NFC-only
readers", closed) describes exactly the two-tap pattern this section is about, tied explicitly to ISO
18013-5 Second Edition's NFCv2 recommendation; its implementing PR #1876 ("Nfcv2 multiple taps") merged
`2026-08-06T18:21:42Z`. **Confirmed via GitHub's compare API (commit ancestry, not date comparison) that
this is NOT in the pinned `0.99.0` artifact**: the `0.99.0` tag's target commit (`7486ddb...`) is an
ancestor of PR #1876's merge commit (`acd6bcb...`), 94 commits earlier — so NFCv2 double-tap support
would require bumping the Multipaz dependency, not just app-side changes. Beyond that one issue, a
broad search (`CardSession consent`, `CardSession UI`, `"Hold Near Reader"`, `iOS HCE consent`,
`alertMessage`, `iOS system modal`, `CardSession`, `HCE emulation dialog`, `consent screen iOS`,
`two-tap`, `double tap`) turned up nothing else on-topic: issue #1494 ("iOS NFC Double Engagement Bug")
is a different bug (a second NFC tag-detection race during an in-progress BLE transfer, not a consent-UI
problem); issue #368 ("Tag was lost... static Handover") is an Android reader-app bug about two holder
apps on one device. **No established Multipaz-community pattern exists for the iOS
`CardSession`-obscures-consent problem** — this is unresolved territory for the library, not a
known/solved issue elsewhere.

**A `CardSession.alertMessage` transparency stopgap was tried, then reverted — tracked here for the
record, not because it's in the tree.** `alertMessage` — the same read-only text property Apple
documents for `NFCTagReaderSession` and exposes identically on `CardSession` — was briefly wired end to
end (`NfcHceBridge.swift`/`NfcHceBridge.def`/`IosNfcHceTransport.kt`/`IosProximityPresenter.kt`) to show
a one-line summary of the requested claims (e.g. "Sharing: family_name, given_name") from `awaitConsent`
right before publishing `IosProximityState.Requesting`. Confirmed via a real-device log that the call
was genuinely reached with the correct string (`setAlertMessage: bridge=present, message=Sharing:
family_name, given_name`) — but whether the text actually rendered on the system sheet was never
determined; that check was still in progress when the decision was made to abandon this direction.
**The deciding factor either way**: `alertMessage` is read-only text with no button or callback of its
own — it cannot collect a decision, only display a line, and can never let the user decline through it.
Even a confirmed-working version would still leave the actual problem (the user can't reach
accept/decline underneath the modal) unsolved, so it was reverted rather than carried as a permanent
stopgap. `git diff` confirmed the revert restored `NfcHceBridge.swift`, `NfcHceBridge.def`, and
`IosNfcHceTransport.kt` to byte-identical `HEAD`, and removed only the `alertMessage`-specific additions
from `IosProximityPresenter.kt`, leaving this section's earlier diagnostic logging (`awaitConsent:
entered`, `PresentmentCannotSatisfyRequestException` cause-unwrapping, `logDocumentStoreState`) intact.

**The real fix under consideration: upgrade the pinned Multipaz dependency from `0.99.0` to `0.101.0`.**
Verified directly against GitHub's release for that tag (published `2026-09-10T19:15:49Z`) — this is a
real, verbatim release note, not a paraphrase:

> **Presentment lifecycle and multi-tap NFCv2**: Factored presentment into three discrete phases
> (consent, authentication/key unlocking, response generation) with `SecureArea.unlockKey()` and
> `PreloadedKeyUnlockDataProvider` to support pre-unlocking keys before tapping. Added wallet-side and
> reader-side support for NFCv2 multi-tap presentment and continuous scanning on NFC-only engagements.

This is exactly the upstream-supported shape this section has been looking for: consent as its own
phase, decoupled from the tap/key-unlock step, is what would let this app show and resolve its own
consent screen *before* `CardSession` ever needs to be active for that tap — closing the "Hold Near
Reader"-obscures-consent problem structurally instead of working around it. This also supersedes the
earlier NFCv2 double-tap finding above (PR #1876, confirmed absent from `0.99.0`): `0.101.0` is a later
release than that PR merged into, so it very likely (not yet independently re-confirmed for `0.101.0`
specifically) includes it.

**Decision: pursue the `0.99.0` → `0.101.0` bump as the real fix, in place of the reverted stopgap.**
`gradle/libs.versions.toml` is now pinned to `0.101.0` and staying there — this is a deliberate,
in-progress migration, not an accident to revert. Confirmed via a full `:shared-logic:compileAndroidMain`
run that this is a real, coherent API migration, not scattered breakage: every current compile failure
(12 files, all under `shared-logic/src/iosMain` — zero in `commonMain`, confirmed by filtering error
paths to their source-set prefix) traces to the same presentment-model rework described above —
`CredentialPresentmentData`/`CredentialPresentmentSelection` no longer resolving, `TrustMetadata` renamed
to `TrustedRequesterIdentity` (evidenced directly by an exact-match type-mismatch error in
`WalletPresentmentSource.kt`), plus a few smaller signature changes (`SecureArea`/cert-chain parameter
renames, a new `EventVerification` sealed subtype needing an exhaustive `when`). Android's own build
genuinely succeeds against `0.101.0` right now (`:androidApp:assembleDevDebug`, confirmed via real
compiler output on `:shared-logic:compileAndroidMain`/`:shared-ui:compileAndroidMain`, not just
`BUILD SUCCESSFUL`) — expected, not reassuring: Android's build never touches `iosMain` at all, so it was
never exposed to the changed API surface in the first place. Migrating the 12 broken files to 0.101.0's
API is now its own separate, tracked task.

**Verified for the alertMessage revert itself** (independent of the 0.101.0 migration above — the two
are tracked separately): `git diff` confirms `NfcHceBridge.swift`, `NfcHceBridge.def`, and
`IosNfcHceTransport.kt` are byte-identical to `HEAD` again, and a case-insensitive grep for
`alertmessage` across all four touched files (those three plus `IosProximityPresenter.kt`) returns zero
matches — only this section's earlier diagnostic logging remains in `IosProximityPresenter.kt`.
`IosProximityPresenter.kt` is among the 12 files currently broken by the 0.101.0 migration, but none of
its compile errors (lines 52-53, 198, 201, 330, 335-336, 664, 716-717, 722, 744 — all
`CredentialPresentmentData`/`CredentialPresentmentSelection`/type-inference errors from the pre-existing
presentment-model API) trace to anything alertMessage-related; that code no longer exists in the file.
Full `:shared-logic:testAndroidHostTest`/`iosSimulatorArm64Test`/`detekt`/`ktlintCheck`/`xcodebuild`
verification for `shared-logic` as a whole is deferred until the 0.101.0 migration itself completes —
that dependency, not the alertMessage revert, is what's currently blocking a green build.

### 0.101.0 migration — done

All 12 production files (the ones the compile-error scan found) plus 4 test files it didn't cover
(`WalletPresentmentSourceTest.kt`, `IosRemotePresentmentTest.kt`, `IosProximityPresentmentTest.kt`,
`IosDcApiPresenterTest.kt` — exercising the same production API, only surfaced once tests were
compiled) are adapted to 0.101.0's API shapes. Fixed in the planned order — mechanical trust/revocation
files first (`IosEtsiTrust.kt`, `MultipazRevocationChecker.kt`, `IosIssuerRegistrationChecker.kt`,
`harness/RevocationFixture.kt`), then the presentment-model files with `WalletPresentmentSource.kt` and
`IosProximityPresenter.kt` (the two NFC-critical ones) done first among those, then the rest
(`IosPresentmentModel.kt`, `IosRemotePresenter.kt`, `IosDcApiPresenter.kt`, `IosDocumentProviderBridge.kt`,
`IosDocumentProvisioningHandler.kt`, `IosTransactionLog.kt`) — compile-checked incrementally after each
file, per-error-count dropping to zero one file at a time rather than fixed all at once.

**Confirmed no genuine behavior change was required for the two NFC-critical files, only shape
adaptation**: `awaitConsent()`'s state machine (`Idle → Requesting → Sending → Sent/Failed`,
`CompletableDeferred`/`withTimeoutOrNull` suspend pattern) is untouched — only 3 renamed types
(`TrustMetadata?`→`TrustedRequesterIdentity?`, `CredentialPresentmentData`→`ConsentData` at the callback
boundary, unwrapped via `.credentialQueryResult` to reach the `CredentialQueryResult` the rest of the
translation layer already expected, `CredentialPresentmentSelection`→`CredentialSelection`) and one
extra field hop. `Iso18013Presentment`'s call site in `IosProximityPresenter.kt` needed **zero** changes
— 0.101.0 kept a backward-compatible overload matching this app's exact parameter names, confirmed by
reading the actual source rather than assumed. The new three-phase presentment lifecycle and
`engagementParams`/`NfcHybridTransportMdoc` (the NFCv2 multi-tap machinery) were deliberately **not**
adopted — out of scope for this migration, and still the separate future redesign this section already
flagged for the CoreNFC-system-sheet-blocks-consent problem.

**One real bug found during verification, not a pre-existing assumption confirmed** — worth recording
because it's non-obvious and could resurface if this code is touched again: `CompressedStatusList.fromJwt`'s
`trustedRootCert` parameter cannot substitute for the old `publicKey` parameter when a status-list JWT
carries no `x5c` of its own (this app's actual production/fixture shape — "[n]either EU dev issuer
populates" the credential-embeds-signer field, and `harness/RevocationFixture.kt`'s tokens are signed
anonymously, no `x5c`) — `validateJwt` only ever invokes `certificateChainValidator` when the JWT's own
header already has one; with none, it always fails "could not check signature, no public key found"
regardless of what's passed. A second, subtler difference surfaced fixing that first bug:  when a JWT
*does* carry its own `x5c` and `publicKey` is also supplied, 0.101.0's `validateJwt` now verifies
`publicKey` anchors the chain's *root* (last entry) and then signs against the chain's *leaf* (first
entry) instead of using `publicKey` directly — 0.99.0 just used `publicKey` outright, ignoring any
embedded chain. `MultipazRevocationChecker.kt`'s `checkStatusList` now peeks at whether the token's own
header carries an `x5c` (mirroring the exact check `validateJwt` itself does internally) and picks
`publicKey` or a `certificateChainValidator` comparing the chain's leaf key against the already-trusted
signer accordingly — both paths verify the token was actually signed by the exact key/cert already
established as trusted, neither weaker than 0.99.0's behavior. Found via `MultipazRevocationCheckerTest`
regressions (`Expected <Valid...>, actual <Unknown(...no public key found)>`, then `...Signature
verification failed` after the first fix), not by inspection — confirms why this migration's own scoped
test run mattered, not just a clean compile.

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test` 454/454
(both counted from the actual `TEST-*.xml` result files, not `BUILD SUCCESSFUL` alone — the tests
genuinely ran; the two revocation-checker test classes specifically went from 8 failures to 0 across the
two fix iterations above), `detekt`/`ktlintCheck` clean, `:androidApp:assembleDevDebug` succeeded
(confirmed via real, non-cached `:shared-logic:compileAndroidMain`/`:shared-ui:compileAndroidMain`
compiler output earlier in this same investigation — Android's build graph never touches `iosMain`, so
nothing in this migration could have invalidated that result), `generateIosProject` + full `xcodebuild`
(`EudiWallet` + `EudiWalletDocumentProvider`, real connected device) succeeded, "Compile Kotlin Framework
(SharedKit)" confirmed to run unconditionally every build so the migrated Kotlin was genuinely compiled
in, not stale.

**Still open, unchanged by this migration**: the CoreNFC "Hold Near Reader" system-sheet-blocks-consent
problem itself (this section's whole subject) remains unsolved.

### Sixth finding: Multipaz 0.101.0's three-phase presentment does NOT solve this — a dead end, documented so it isn't re-investigated

**Corrects the framing at the end of the previous finding above** (which read `Iso18013Presentment`'s new
`engagementParams`/`NfcHybridTransportMdoc`/`onDeviceRequest` as "concretely available for that
redesign"). A dedicated follow-up investigation traced the actual mechanism in full — `mdocPresentment.kt`
(all 506 lines: `mdocPresentmentObtainConsent`/`mdocPresentmentAuthenticateUser`/
`mdocPresentmentGenerateResponse`), `Iso18013Presentment.kt` (both overloads, the full request/response
loop), `NfcHybridTransportMdoc.kt` (full), `SecureArea.kt`/`KeyUnlockDataProvider.kt`/
`PreloadedKeyUnlockDataProvider.kt`, and iOS's real `SecureEnclaveSecureArea.kt` — and the honest
conclusion is **this solves a different, narrower problem than the one this section is about.**

**What the three-phase split actually is**: `mdocPresentmentAuthenticateUser(selection)` calls
`credential.secureArea.unlockKey(alias, unlockReason)`, which on iOS's real `SecureEnclaveSecureArea` is
a genuine `LAContext`-based Face ID/Touch ID/passcode prompt when the key requires it, producing a
`PreloadedKeyUnlockDataProvider` so a *later* `sign`/`keyAgreement` call doesn't need to re-prompt. The
entire feature is about **avoiding a biometric prompt interrupting an active NFC tap** — not about
letting the user review and decide on the actual requested claims before or during a tap.

**Directly irrelevant to this app as configured, on top of that**: `IosDocumentProvisioningHandler.kt`
states plainly — *"this wallet's own configuration sets `userAuthenticationRequired = false` on every
flavour."* `unlockKey()` is a no-op here today. Even the narrower problem this feature actually solves
doesn't currently apply to this app.

**Does true per-field informed consent become available earlier?** No — traced precisely, not assumed.
`mdocPresentmentObtainConsent(deviceRequest, source, ...)` requires an *already-fully-decrypted*
`DeviceRequest` — obtaining one still requires the exact same complete ISO 18013-5 mdoc-AID
ENVELOPE/GET RESPONSE exchange this app already performs today, no lighter-weight "preview" protocol
exists. And per Apple's own CardSession documentation, fetched and quoted directly (not paraphrased):
`startEmulation()` — "Start the card emulation **and present a modal user interface** to the person
using the app" — the system modal is tied to `startEmulation()` itself, not to actual reader
communication beginning. There is no documented state where `CardSession` is emulating/receiving APDUs
without the modal already up. (This is in tension with this app's own earlier real-device observation
that the modal appears specifically during/after the tap, not from screen-entry onward when cold-tap
arms — an unresolved discrepancy between Apple's doc wording and this app's prior hardware observation,
flagged here rather than guessed at; only a deliberately-instrumented real-device test could resolve
which is accurate.) Either way: **the full DeviceRequest can only ever be obtained via an exchange that
happens while the modal is already up** — `onDeviceRequest`'s new hook lets the app end `CardSession`
promptly *once the request has arrived*, so this app's own consent screen becomes interactive
*immediately after* the tap, never *during* or *before* it. That's a real UX improvement over today (no
more indefinitely-stuck-behind-the-modal consent screen) but it is not "review what's being asked before
tapping," and adopting it would still cost a second physical tap to actually deliver an accepted
response (traced via `Iso18013Presentment`'s reconnect-wait logic, gated behind
`transport is NfcHybridTransportMdoc && transport.isNfcOnly`) plus a matching reader-side NFCv2 tolerance
this repo cannot verify or guarantee (0.101.0's own release note: "wallet-side **and reader-side**
support"). No Multipaz sample/reference app was found demonstrating an intended UX for any of this — the
pinned sources jar has no sample module, and the local sibling checkout at `~/multipaz` is tagged
`0.95.0`–`0.98.0` (2025-11-14), predating this feature entirely.

**The CoreNFC-modal-blocks-consent problem remains genuinely unsolved by any upstream Multipaz mechanism
found so far.** The two remaining real options:

- **(a)** A generic, pre-configured "I consent to share my PID via NFC tap without per-request review"
  setting, with the physical tap itself standing in as the consent act — a real UX/security tradeoff
  requiring a product decision, not just engineering. Not evaluated here; flagged as an option, not a
  recommendation.
- **(b)** Continue tracking Multipaz's own development for a genuine solution to this specific problem
  in a future release.

Nothing has been implemented for either option. This closes out this investigation thread with an
honest, unresolved conclusion rather than a false lead.

### Seventh finding: Option 4 — NFC engagement, BLE data transfer — implemented as cold-tap's new default (2026-09-22)

A genuinely different path from either option (a)/(b) above, investigated and then implemented:
cold-tap still *engages* over NFC (a physical tap still starts everything), but hands data transfer off
to BLE instead of continuing over the mdoc AID — closing `CardSession` (and its system sheet) right
after handover, well before consent. Staged, same discipline as Stages 1-4 earlier: design confirmed
before any code, implementation reviewed, verifier-compatibility investigated separately (below).

**Design correction found before writing any code, not after**: the plan as first proposed — branch
only inside `onColdTapHandoverComplete`, construct a BLE transport there from the NFC engagement's own
`connectionMethods` — does not work. Traced directly against `MdocNfcEngagementHelper.processSelectFile`
(pinned `0.101.0` source): a reader only ever learns a connection method from the Handover Select NDEF
message, built and returned *synchronously* as part of the NFC `SELECT FILE` response, from whatever
`staticHandoverMethods` were supplied when engagement was armed. A BLE transport constructed *after* the
tap, with a UUID the reader was never told about in that Handover Select, is undiscoverable — the reader
has no way to find it. **The BLE transport has to already be advertising, with its real
`connectionMethod`, before the tap can happen at all** — `armColdTapEngagement()` had to change too, not
just `onColdTapHandoverComplete()`.

**Implementation** (`IosProximityPresenter.kt`):
- `armColdTapEngagement()`: advertises a BLE transport (the exact `advertise()`/`bleConnectionMethod()`
  call `startQrEngagement()` already used, reused verbatim) *before* constructing
  `MdocNfcEngagementHelper`, and offers that transport's own `connectionMethod` in
  `staticHandoverMethods` — not `nfcConnectionMethod()`. The live transport is held in a new
  `coldTapBleTransport` field until a tap claims it (transferred to `onColdTapHandoverComplete`, which
  nulls the field first) or arming is torn down without a tap ever happening (`clearEngagementHelper()`,
  which now also closes it if still unclaimed).
- `onColdTapHandoverComplete()`: publishes a new `IosProximityState.Connecting`, ends `CardSession` via
  the existing `disarmColdTapEngagement()` (confirmed correct over a bespoke `nfcTransport.stop()`-only
  call — `disarmColdTapEngagement()` also resets `engagementHelperArmed`, which a lighter call would
  leave stale-`true` after CardSession has actually stopped, silently breaking re-arming for the rest of
  the screen visit), then hands the already-claimed BLE transport into `runPresentment()` — which needed
  **zero changes**, confirmed: it already took `transports: List<MdocTransport>` and already relied on
  `waitForConnection`'s existing multi-transport race-and-close-the-losers behavior, exactly as the QR
  path already exercised for its own (single) BLE transport.
- **Retained, not deleted**: the prior NFC-continuation shape (`onColdTapHandoverComplete` constructing
  `NfcTransportMdoc` directly, no CardSession teardown) is kept as a dated comment block on
  `onColdTapHandoverComplete`'s own doc comment, not live code — an unused private
  function/branch would itself be a real detekt finding (`UnusedPrivateMember`/`UnusedParameter`), not a
  harmless no-op, so a comment is the correct way to "keep but not run" here.
- `isPresentmentActuallyInProgress()` **now also includes `Connecting`** — found while writing tests,
  not by inspection: between `onColdTapHandoverComplete` publishing `Connecting` and `runPresentment`
  reaching `Requesting`/`Sending`, a presentment attempt already owns the same shared state
  (`presentmentJob`/`transport`/`pendingConsent`/`pendingData`/`sharedDocuments`) this guard exists to
  protect — omitting `Connecting` would have reopened a version of the exact race this guard was written
  to close, just during a new window Option 4 introduces.

**UX continuity** (your own addition to the plan, not in the original research): `IosProximityState.Connecting`
is new, published between CardSession teardown and the BLE connection actually completing, so the screen
has something concrete to show instead of an unexplained pause. Wired through the full stack — a new
`ProximityQRPartialState.Connecting` (shared `commonMain`, Android never emits it, a no-op there exactly
like `NfcNotice` already is) — and `ProximityQRViewModel` sets `isLoading = true` for it, **reusing the
existing loading spinner** the screen already shows before `QrReady` arrives; no new screen or Compose
work needed. `IosProximityCoordinator`'s three `when` blocks over `IosProximityState`, and a fourth
exhaustive `when` found in `WalletEngineProbe.kt` (`iosProbeMain`, not caught by the initial grep — only
surfaced by the compiler), all updated.

**Known, documented limitation, not solved here**: `NfcHceBridge.swift`'s existing `stop()` has a
deliberate ~3-second delay before actually ending the CoreNFC session (added earlier tonight so an
in-flight NFC response has time to reach the reader) — reused as-is for this teardown too, since
`onColdTapHandoverComplete` runs synchronously, nested inside the NFC `SELECT FILE` response
construction itself, and that same grace period incidentally protects the pending handover-ack response
here as well. The system sheet does **not** disappear the instant `Connecting` is published — it
disappears roughly 3 seconds later. Documented on `IosProximityState.Connecting`'s own doc comment. A
genuinely immediate teardown would need new Swift-side plumbing (an immediate-vs-graceful `stop()`
distinction) — not attempted in this stage.

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test` 455/455
(+1: `isPresentmentActuallyInProgress_is_true_while_connecting`), `:shared-ui:testAndroidHostTest` 482/482
(+1), `:shared-ui:iosSimulatorArm64Test` 530/530 (+1: `a_connecting_notice_shows_the_spinner_again_after_the_qr_was_ready`,
the same `commonTest` case running on both targets), all counted from actual `TEST-*.xml` files, not
`BUILD SUCCESSFUL` alone. `detekt`/`ktlintCheck` clean (two real findings surfaced and fixed along the
way: an unused `NfcTransportMdoc` import once the live construction site was removed, and an unused
`connectionMethods` parameter on `onColdTapHandoverComplete` once it stopped needing to build a transport
from it directly). `:androidApp:assembleDevDebug` succeeded (validated indirectly — the same
`commonMain` compile it depends on had already run fresh, non-cached, moments earlier as part of the
test run above). `generateIosProject` + full `xcodebuild` (`EudiWallet` + `EudiWalletDocumentProvider`,
real connected device) succeeded.

**Not yet done**: a genuine end-to-end hardware test of this flow (real cold-tap, confirm the reader
actually connects over BLE and not NFC, confirm `Connecting` visibly appears and the system sheet
disappears roughly when expected). Stage 3 (verifier-side compatibility) is investigated separately,
below.

### First real-device finding on Option 4: `onColdTapHandoverComplete`'s teardown was too broad, again

The first real-device test surfaced a genuine bug, not a variant of the already-documented 3-second-
delay question. Verifier log: `mdocReaderNfcHandover()` starting, then ~198ms later
`NfcCommandFailedException: Error READ BINARY, status 6a82`. Wallet-side log, pulled and traced precisely
(exact timestamps):

```
08.352857  SELECT FILE (0xe104 — the NDEF file itself)
08.362630  onHandoverComplete fires (synchronously, nested inside that SELECT's own processing)
08.372876  clearEngagementHelper: engagementHelper nulled
08.375145  disarmColdTapEngagement: stopping CoreNFC
08.401926  READ_BINARY — the reader's actual attempt to fetch the Handover Select content
08.406095  WARNING: NDEF AID selected but no engagement helper is armed   ← the 6A82
```

**Confirmed precisely which of two possible causes this was, not assumed**: was CoreNFC's own session
(the radio) already unresponsive by `08.401926`, or was this purely a Kotlin-side routing decision made
too early? The APDU-received log line at `08.401926` is itself the proof — it could only exist if the
byte sequence made it all the way from the reader, through CoreNFC's radio, through
`session.eventStream`, to Kotlin. Cross-checked against `NfcHceBridge.swift`'s actual `stop()` source:
`cardSession = nil` runs synchronously and only clears that class's own stored reference; the calls that
actually end the session's ability to answer APDUs (`stopEmulation()`/`invalidate()`) are deferred behind
the existing ~3-second delay, and the event-stream loop that receives APDUs holds its *own* local
reference to the session, entirely unaffected by that property being nilled. **CardSession was still
genuinely open and would have answered correctly — the Kotlin-side `clearEngagementHelper()` call, not
CoreNFC, is what rejected the read, ~29ms before the APDU even arrived and roughly 3 seconds before
CoreNFC's own session would have stopped responding anyway.**

This is the exact scenario `ndefHandoverCompleted` (§9's fourth real-device finding, above) was already
built to solve — a reader legitimately sending one or more trailing `READ_BINARY`s on the NDEF file after
handover completes, before it switches AIDs itself. Option 4's `onColdTapHandoverComplete` called
`disarmColdTapEngagement()` (the full clear) immediately after publishing `Connecting`, bypassing that
existing protection entirely — a real regression, not a new problem.

**Fix**: `onColdTapHandoverComplete` now calls `nfcTransport.stop()` directly instead of
`disarmColdTapEngagement()`/`clearEngagementHelper()`. `ndefHandoverCompleted = true` (already set) is
what keeps this narrowly safe — `ApduDelegate`'s existing gate is completely unchanged: a genuine repeat
`SELECT` is still rejected, only `READ_BINARY`/`UPDATE_BINARY` keep being served, exactly the guard the
retained NFC-continuation path already relies on. `engagementHelperArmed` is deliberately left `true`
after a successful tap too — confirmed consistent with, not a new gap next to, the retained path's own
existing behavior: neither one ever reset it on success; both already relied on `onScreenExited()`/the
switch toggling off for eventual cleanup rather than auto-re-arming within the same screen visit, so
this isn't a new staleness risk Option 4 introduces.

**Test added** (not just asserted safe): `onColdTapHandoverComplete_keeps_the_engagement_helper_reachable_for_a_trailing_read`
drives a real `MdocNfcEngagementHelper` through the exact Type-4-Tag APDU sequence a real tap produces
(`SELECT NDEF AID` → `SELECT`+`READ` the capability container → `SELECT` the NDEF file, where handover
completes) against `IosProximityPresenter`'s own real `onColdTapHandoverComplete` — not a spy standing in
for it — then sends the same trailing `READ_BINARY` the real reader sent and asserts it succeeds.
Required widening three members from `private` to `internal` (`nfcTransport`, `coldTapBleTransport`,
`onColdTapHandoverComplete` itself) — same justification already established for `mutableState`/
`isPresentmentActuallyInProgress`: the actual piece of logic a fix changed needs to be reachable directly
by a test, without real hardware. **Verified the test itself catches the regression**, not just that it
passes: temporarily reverted the fix, confirmed the test fails with the exact expected assertion message,
then re-applied the fix and confirmed it passes again — a genuine positive control, not merely "the test
happens to be green."

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test`
456/456 (+1 for the new regression test), `:shared-ui:testAndroidHostTest` 482/482,
`:shared-ui:iosSimulatorArm64Test` 530/530 — all from actual `TEST-*.xml` files. `detekt`/`ktlintCheck`
clean. `generateIosProject` + full `xcodebuild` (`EudiWallet` + `EudiWalletDocumentProvider`, real
connected device) succeeded. **Not yet re-tested on real hardware** — ready for another real-device tap
to confirm the fix holds under the actual conditions that surfaced the bug.

### Second real-device finding on Option 4: an unexpected `CardSession` end left the "Share over NFC" switch silently stale

Reported by the user after the first successful end-to-end Option 4 run: after the system NFC sheet is
cancelled (or times out), the sheet does **not** reappear on a later tap unless the switch is manually
toggled off and back on — the app looks ready for NFC (switch still on) but is not actually listening at
all.

**Traced precisely, confirming which of two possible causes this was rather than assuming**: is
`disarmColdTapEngagement()` ever called from an unintended path (e.g. `onScreenExited()` firing without
the user actually leaving the screen), or does `CardSession` simply have no way at all to tell Kotlin it
ended on its own? Re-grepped from scratch rather than trusting an earlier restated summary: exactly two
call sites exist, both gated by a genuine, deliberate user action —
`onScreenEntered()`'s `else` branch (only reached when the switch is off at call time, itself only
invoked at screen entry or an explicit switch toggle) and `onScreenExited()` (only reached via explicit
navigation/cancel). **No accidental or reactive path exists at all** — the real gap is the opposite one:
`NfcHceBridgeDelegate` had no session-lifecycle callback whatsoever, and the Swift `.sessionInvalidated`
event case discarded its own associated `CardSession.Error` and told Kotlin nothing. So when the user
cancels the system sheet (or it times out), `engagementHelperArmed`/`nfcEngagementEnabled` simply never
learn the session is gone, and stay believing cold-tap is still armed indefinitely.

**Fix — a new, purpose-built session-end signal, expected vs. unexpected distinguished on the Swift
side**:

- `NfcHceBridge.swift`: a new `stopRequested: Bool`, set synchronously at the top of `stop()` (before the
  existing 3-second-delayed `stopEmulation()`/`invalidate()`) and reset at the top of every fresh
  `startCardSession()`. The `.sessionInvalidated(let reason)` event case now binds and logs `reason`
  (previously discarded entirely) and, only when `!stopRequested`, nils `cardSession` and calls a new
  `NfcHceBridgeDelegate.sessionEndedUnexpectedly()` — added to both the Swift protocol and the matching
  `NfcHceBridge.def` Objective-C declaration. An *expected* end (our own `stop()` already called, e.g.
  right after a successful Option 4 handover) never calls it — those paths' own existing teardown is
  already correct.
- `IosNfcHceTransport.kt`: a new `onSessionEndedUnexpectedly: () -> Unit = {}` constructor parameter,
  threaded into `ApduDelegate` (a new `onSessionEndedUnexpected` parameter) and its new
  `override fun sessionEndedUnexpectedly()`, which logs and invokes the callback.
- `IosProximityPresenter.kt`: `nfcTransport` now constructed with
  `IosNfcHceTransport(onSessionEndedUnexpectedly = ::onCardSessionEndedUnexpectedly)`. The new
  `onCardSessionEndedUnexpectedly()` sets `nfcEngagementEnabled = false`, calls
  `disarmColdTapEngagement()` (safe/idempotent even though `CardSession` is already gone by this point —
  `stop()`'s own early-return guard on a `nil` session makes the repeat call a no-op), and emits a new
  one-shot `nfcEngagementDisabledUnexpectedly: SharedFlow<Unit>` (mirrors the existing
  `mutableNfcNotice`/`nfcNotice` pattern). **Deliberately does not auto-re-arm** — the switch represents
  whether NFC is genuinely active right now, not a standing intent, so the correct response is to turn it
  off and require the user to explicitly opt back in, which shows the system sheet again, deliberately.
- The established 4-file UI ripple: `IosProximityCoordinator.kt`'s `qrEvents()` merges the new flow in
  (same "subscribed from the moment collection starts" reasoning as `nfcNotice`);
  `ProximityQRInteractor.kt` gains `ProximityQRPartialState.NfcEngagementDisabledUnexpectedly` (iOS-only,
  no-op on Android, same as `Connecting`/`NfcNotice`); `ProximityQRViewModel.kt`'s `generateQrCode()`
  handles it with `setState { copy(nfcDataRetrievalEnabled = false) }` — deliberately *not* routed through
  `Event.NfcDataRetrievalToggled`, since that would call `interactor.toggleNfcDataRetrieval(false)` again
  (redundant) and `restartEngagementForNfcToggle()` (which would re-arm engagement, the auto-re-arm this
  fix explicitly rejects); `WalletEngineProbe.kt`'s own exhaustive `when` over `ProximityQRPartialState`
  needed the same new branch, caught by the compiler rather than a manual grep — same pattern as
  `Connecting` before it.

**Tests added** to `IosProximityPresentmentTest.kt`, driving the real callback chain
(`IosNfcHceTransport.ApduDelegate.sessionEndedUnexpectedly()` → `onSessionEndedUnexpected` →
`IosProximityPresenter.onCardSessionEndedUnexpectedly()`), not a stand-in for it — required widening
`IosNfcHceTransport.delegate` from `private` to `internal`, same justification as `nfcTransport`'s own
`internal` visibility:

- `an_unexpected_card_session_end_turns_the_nfc_switch_off_and_signals_the_ui`: arms the switch, calls
  `nfcTransport.delegate.sessionEndedUnexpectedly()` directly, asserts `isNfcEngagementEnabled() == false`
  and that `nfcEngagementDisabledUnexpectedly` emitted (collected via
  `launch(start = CoroutineStart.UNDISPATCHED)` so the collector is subscribed before the synchronous
  trigger, not raced against it).
- `an_expected_card_session_end_does_not_signal_the_unexpected_flow`: arms the switch, calls
  `onScreenExited()` (an existing, expected `disarmColdTapEngagement()` call site), asserts the new flow
  did *not* emit and that `isNfcEngagementEnabled()` is untouched — `disarmColdTapEngagement()` alone
  never touches the switch's own state, only the unexpected-end path does.

**Verified the first test actually catches the regression**, not just that it's green: temporarily
reverted `nfcTransport`'s construction back to the no-arg `IosNfcHceTransport()`, confirmed the test
failed with `kotlin.AssertionError` on the expected assertion, then re-applied the fix and confirmed both
tests pass again — a genuine positive control.

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test` 458/458
(+2 for the two new tests), `:shared-ui:testAndroidHostTest` 482/482, `:shared-ui:iosSimulatorArm64Test`
530/530 — all from actual `TEST-*.xml` files, each task genuinely executed (not `UP-TO-DATE`/
`FROM-CACHE`). `detekt`/`ktlintCheck` clean. `generateIosProject` succeeded. **No real device was
connected at verification time** (`xcrun devicectl list devices` showed all three paired devices as
`unavailable`) — fell back to `xcodebuild -destination 'generic/platform=iOS'` for `EudiWallet`, which
succeeded (`** BUILD SUCCEEDED **`) and does compile `iosApp/NfcHceBridge/`, but — same distinction
flagged for the previous checkpoint — this is a compile-correctness check only, not a substitute for a
real-device run. **Still needs a real-hardware test**: cancel the system NFC sheet deliberately and
confirm the switch turns off immediately; re-enable it and confirm the sheet reappears.

### Third real-device finding on Option 4: `NFCPresentmentIntentAssertion`'s own assertion-level cooldown, reachable through the switch-off fix itself

Reported immediately after the above: re-enabling the switch right after it auto-turned off (the second
finding's own fix) can itself fail, surfacing this app's existing generic notice: *"Could not start NFC
data retrieval. Sharing continues over Bluetooth."*

**Traced to the specific `NfcStartResult` case and failure condition, not assumed**: this only comes from
`IosProximityPresenter.kt`'s `nfcStartFailureMessage()`, reached from `armColdTapEngagement()`'s
`nfcTransport.start { result -> ... }` callback when `result != NfcStartResult.Started`. On the Swift
side, that code (previously always `4`/`.transientFailure`) is set in `startCardSession()`
(NfcHceBridge.swift) in exactly two places: the `NFCPresentmentIntentAssertion.acquire()` do/catch, and
the `CardSession()`/`startEmulation()` do/catch. Given the timing — an immediate re-toggle re-triggers
`armColdTapEngagement()` with no delay — the far more likely of the two is the assertion `acquire()`
catch's `NFCPresentmentIntentAssertion.Error.systemNotAvailable` branch, already logged distinctly as
`"...systemNotAvailable (cool-down)"`.

**This is a separate, Apple-imposed cooldown from the previously-documented `CardSession`-level one** —
worth stating explicitly since both are "~15 seconds" and easy to conflate:

- The **`CardSession`-level cooldown** (`Timing.maxRespondRetries`'s own doc comment, NfcHceBridge.swift):
  Apple's `CardSession` runs for roughly 15 seconds before a roughly 15-second cool-down, during which
  `respond(response:)` can transiently throw `CardSession.Error.transmissionError` — this is empirically
  sourced from pagopa/iso18013-ios's own measured retry count, not from Apple's documentation directly,
  and it governs mid-conversation APDU response retries *within an already-active session*.
- The **`NFCPresentmentIntentAssertion`-level cooldown** (this finding): `startCardSession()`'s own doc
  comment, written earlier in this project directly against Apple's `NFCPresentmentIntentAssertion`
  documentation, already states an acquired assertion is hard-capped at 15 seconds, followed by a
  mandatory 15-second cool-down before a new one can be acquired — and flags this as a known,
  unsolved limitation. It governs whether a *new* `CardSession` can even start at all. A fresh live fetch
  of Apple's documentation page was attempted to re-verify the exact wording, as requested — it returned
  only the page title (the same JS-rendered-content limitation already hit earlier in this project for
  `CardSession.Event`'s full case list), so this finding relies on the in-repo comment's own prior
  citation, not a newly-quoted one. One detail that comment already flags as unverified and remains
  unverified here: whether the cooldown clock starts from the original `acquire()` or from an early
  release (`stop()`'s own `presentmentIntentAssertion = nil`) — either way, an immediate re-toggle lands
  well inside the window.

At the time this was investigated, no real device was connected, so no fresh log capture confirmed the
exact `systemNotAvailable` line for this specific occurrence — the determination above is inferred from
the code paths and Apple's documented contract, not a captured reproduction.

**Fix — split the ambiguous case out, on both distinguishing message and switch state**:

- `NfcHceBridge.swift`: new `StartResult.assertionCooldown = 5`, returned by the
  `NFCPresentmentIntentAssertion.Error.systemNotAvailable` catch specifically (previously
  `.transientFailure`, now reserved for any other `CardSession` construction/`startEmulation()` failure).
  `NfcHceBridge.def`'s doc comment updated to document code `5` alongside the existing `0`-`4`.
- `IosNfcHceTransport.kt`: new `NfcStartResult.AssertionCooldown`, decoded by `fromCode(5L)` — the
  existing `else -> TransientFailure` fallback is untouched, so any further undocumented code still
  degrades to the generic case rather than silently miscategorizing as this one.
- `IosProximityPresenter.kt`: a new message constant, `NFC_ASSERTION_COOLDOWN` — *"NFC needs a moment to
  reset after the last attempt — please wait a few seconds and try again. Sharing continues over
  Bluetooth."* No countdown timer — deliberately a static, accurate explanation, not a precision the
  underlying Apple API doesn't actually document reliably enough to promise. `nfcStartFailureMessage()`
  gets one new `when` branch.
- The switch-off half **reuses the exact mechanism the previous finding's fix built**, not a new one:
  `onCardSessionEndedUnexpectedly()`'s `nfcEngagementEnabled = false` + `nfcEngagementDisabledUnexpectedly`
  emission was factored out into a small shared `turnNfcEngagementOffAndSignalUi()`, called both from
  there (after `disarmColdTapEngagement()`'s teardown) and from the new
  `NfcStartResult.AssertionCooldown` branch (no teardown needed there — `CardSession` never started, so
  there is nothing `disarmColdTapEngagement()`/`nfcTransport.stop()` would do beyond what the existing
  inline cleanup, now factored into `onNfcStartResult()`, already does).
- `armColdTapEngagement()`'s previously-inline `nfcTransport.start { ... }` callback body was extracted
  into `internal fun onNfcStartResult(result: NfcStartResult)` — needed for the test below, and matches
  this file's own established pattern of widening exactly the piece a fix touches, not more.

**Test added**, `an_assertion_cooldown_start_failure_shows_the_cooldown_message_and_turns_the_switch_off`:
calls `onNfcStartResult(NfcStartResult.AssertionCooldown)` directly (no real `CardSession` — the
Simulator has no NFC radio), collecting both `nfcNotice` and `nfcEngagementDisabledUnexpectedly` via
`launch(start = CoroutineStart.UNDISPATCHED)` so each collector is subscribed before the synchronous
trigger, and asserts: the emitted notice text matches `NFC_ASSERTION_COOLDOWN` exactly (the constant
itself stays `private`, so the test's literal string intentionally mirrors it rather than exposing it
further); the disabled-unexpectedly flow emitted; `isNfcEngagementEnabled()` is `false` afterwards.
**Verified as a genuine positive control**: temporarily disabled the `AssertionCooldown` branch (a
short-circuited `false &&` guard), confirmed the test failed, re-enabled it, confirmed it passes again.

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test` 459/459
(+1 for the new test), `:shared-ui:testAndroidHostTest` 482/482, `:shared-ui:iosSimulatorArm64Test`
530/530 — all from actual `TEST-*.xml` files, genuinely executed. `detekt`/`ktlintCheck` clean.
`generateIosProject` succeeded. **A real device was connected this time** (`Martin's iPhone`) — full
`xcodebuild` for `EudiWallet` (embedding `EudiWalletDocumentProvider`) against it succeeded
(`** BUILD SUCCEEDED **`), and the build was installed onto that device (`xcrun devicectl device install
app`). **Not yet exercised on the device itself**: rapidly re-toggling the switch within the cooldown
window right after an unexpected session end, to confirm both the clearer message and the switch turning
off, still needs a hands-on real-device pass.

### Fourth real-device finding on Option 4: the system Wallet/Pay card picker can appear after a successful tap, if the phone lingers near the reader

Reported after a successful end-to-end run: the native Apple Pay/Wallet card picker sometimes appears
after engagement completes and `CardSession` is deliberately ended (Option 4's own design, switching to
BLE) — more noticeable the longer the phone stays near the reader afterward, e.g. during a slow BLE
connection (the reader-side 40-second discovery-retry timeout case).

**Confirmed as the same mechanism already documented here, from the opposite direction, not a new one.**
The "Second real-device finding" above (this same §9) already established how iOS decides which app
answers an NFC field-detect event: the background default-contactless-app entitlement (not used here),
or `NFCPresentmentIntentAssertion` — *"Eligible apps running in the foreground can prevent the system
default contactless app from launching... acquire a presentment intent assertion when the user expresses
an active intent to perform an NFC transaction"* (Apple's own documentation, quoted there). That finding
was about the assertion's absence **before** a tap; this one is its absence **after** one. Tracing
`NfcHceBridge.swift`'s actual teardown timing:

- `presentmentIntentAssertion = nil` runs **synchronously, immediately** inside `stop()` — its own
  existing doc comment already states why: *"we no longer want to suppress the system's default
  contactless app once we've stopped listening ourselves."* General-purpose behavior, not specific to
  Option 4.
- `stopEmulation()`/`invalidate()` — what actually ends `CardSession`'s ability to answer APDUs — are
  deferred behind the existing `Timing.stopDelay` (~3 seconds).
- `onColdTapHandoverComplete()` (Option 4) calls `nfcTransport.stop()` **immediately on handover
  completing** — essentially at the moment of the tap itself, well before BLE connects or consent shows.

So from ~0s after the tap, the suppression that was keeping the default picker away is already gone;
`CardSession` itself lingers ~3 more seconds, still nominally claiming the field. Once that ~3-second
window ends, nothing claims the field at all. If the phone is still resting near an actively-polling
reader at that point — the slow-BLE case keeps the reader polling far longer than 3 seconds, and there's
no reason to expect the phone has moved during a deliberate tap-and-wait — the next field-detect event
has only the default-routing path left, exactly the mechanism the second finding already confirmed fires
whenever nothing suppresses it. A fresh attempt to re-verify Apple's `stopEmulation(status:)`/`CardSession`
documentation for this investigation returned only page titles on every fetch (the same JS-rendered-
content limitation hit repeatedly elsewhere in this project) — this finding relies on the already-
established, previously-cited doc quote and the code's own verified timing, not a new live quote.

**No documented way to tell iOS the interaction is over independent of the timing above** — checked
before implementing anything: `stopEmulation(status:)` is always called with `.success` at both call
sites in this file, and `invalidate()` takes no reason argument. Neither appears to be a signal to iOS's
own UI-routing subsystem (the routing decision is governed by whether a `CardSession`/assertion is
currently held, per the second finding's own quote, not by how the previous one ended). A more invasive
mitigation — holding `NFCPresentmentIntentAssertion` open a few seconds longer specifically after
handover, independent of `CardSession`'s own teardown — was considered as a real option but not pursued
this pass, in favor of the simpler UX mitigation below.

**Fix implemented — UX mitigation, not a timing change**: a snackbar shown the moment
`ProximityQRPartialState.Connecting` arrives (`ProximityQRViewModel.kt`'s `generateQrCode()`), telling
the user the tap itself is complete and they can move the phone away — *"Tap complete — you can move
your phone away now."* Deliberately plain, un-localized text (a new top-level `private const
TAP_COMPLETE_MOVE_PHONE_AWAY`), matching this same NFC-status-message family's existing convention: every
sibling message this feature shows (`NfcNotice`, and all of `IosProximityPresenter`'s own
`NfcStartResult` failure messages) is already plain English, not routed through this app's
`composeResources` localization — introducing localization for just this one new string while its
siblings stay hardcoded would be its own, out-of-scope inconsistency. Reuses the exact same
`Effect.ShowSnackbar` pipe `NfcNotice` already uses — no new UI, no new screen, no timing logic.

**Test added**, `connecting_also_tells_the_user_the_tap_is_done_so_they_can_move_the_phone_away`
(`ProximityQRViewModelTest.kt`, `commonTest` — runs on both `testAndroidHostTest` and
`iosSimulatorArm64Test`): feeds `ProximityQRPartialState.Connecting` through a fake interactor and
asserts the exact `Effect.ShowSnackbar` message, mirroring the existing `NfcNotice` test's own style
(hardcoded literal, not the private constant — consistent with that sibling test rather than exposing
the constant further). **Verified as a genuine positive control**: temporarily commented out the new
`setEffect` call, confirmed the test failed (`UncompletedCoroutinesError` — `viewModel.effect.first()`
never resolved), restored it, confirmed it passes again.

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test`
459/459, `:shared-ui:testAndroidHostTest` 483/483 (+1), `:shared-ui:iosSimulatorArm64Test` 531/531 (+1,
the same `commonTest` test on both platforms) — all from actual `TEST-*.xml` files, genuinely executed.
`detekt`/`ktlintCheck` clean. This change touches only `shared-ui`'s `commonMain`/`commonTest` — no
`iosApp/NfcHceBridge/` file changed, so per this file's own verification rules a full `xcodebuild` is not
required this pass (the still-open real-device passes for the two findings above remain outstanding
separately).

### Fifth real-device finding on Option 4: an early-invalidation path for `CardSession`, complementary to the UX mitigation above — not a replacement for it

Investigated whether the fourth finding's fixed `Timing.stopDelay` (3 seconds) could instead be tied to
actual BLE progress, to shrink the window that lets the system Wallet/Pay picker appear. **The
investigation's own conclusion, load-bearing for what follows**: `stopDelay` already ends `CardSession`
well before a slow BLE connection completes in every realistic case (the reported, dominant scenario —
tap #1's 40-second discovery-retry timeout) — an event-driven trigger tied to `IosProximityState.Requesting`
can only ever preempt the *already low-risk* fast-BLE case (tap #2), because `Requesting` is essentially
never reached before the fixed 3-second timer already fires. **This is why the fourth finding's UX
message stays in place and is not superseded by what follows** — the request that prompted this fix
described it as possibly making that message "no longer needed," and that determination doesn't hold: for
the dominant slow-BLE case, this fix changes nothing, and the UX message remains the only mitigation that
actually addresses it.

**What this fix does add, honestly scoped**: for the fast-BLE case specifically, `CardSession` can now be
torn down as soon as a BLE request has genuinely arrived and been matched (`IosProximityState.Requesting`),
rather than always waiting out the full fixed 3 seconds regardless. A real, if narrower, improvement for
that one case — shrinking an already-small window further, not eliminating a large one.

**Implementation — a race between two paths, with the original one kept intact as the floor/fallback**:

- `NfcHceBridge.swift`: `stop()` now stores the delayed-teardown `Task` (`pendingTeardownTask`) and the
  `CardSession` it captured (`pendingTeardownSession`), both type-erased `Any?` for the same reason as
  `cardSession`/`presentmentIntentAssertion`. A new `invalidateNow()` cancels that pending task and
  performs the teardown itself instead, after a short, deliberate `Timing.earlyInvalidateBuffer` (500ms)
  — *not* instantly on call, specifically to cover residual uncertainty about NFC/BLE sequencing this app
  doesn't control (a cross-repo boundary, §8) rather than a specific known race. Both the delayed task's
  own completion and `invalidateNow()` clear `pendingTeardownTask`/`pendingTeardownSession` *before*
  performing their respective teardown (not after), so whichever path reaches that check first is the one
  that runs — the other finds nothing left pending and no-ops. `stop()`'s existing `try? await
  Task.sleep(...)` was changed to an explicit `do`/`catch` that returns early on cancellation: `try?`
  would have swallowed `invalidateNow()`'s cancellation and torn the session down a second time anyway.
- `NfcHceBridge.def` / `IosNfcHceTransport.kt`: `invalidateNow()` added to both, mirroring `stop()`'s own
  seam. `IosNfcHceTransport.kt` gains `pendingStopBridge` (`internal` — see below), set by `stop()` before
  it nulls the general-purpose `bridge` field (which itself is nulled synchronously, well before Option
  4's `Requesting` transition could ever fire, so `invalidateNow()` can't just read `bridge` again).
- `IosProximityPresenter.kt`: `awaitConsent()` calls `nfcTransport.invalidateNow()` immediately after
  publishing `IosProximityState.Requesting` — unconditionally, not gated on cold-tap origin specifically:
  a no-op for a QR-only presentment, where `pendingStopBridge` is simply `null` since cold-tap was never
  armed at all.

**Test added**, `IosNfcHceTransportTest.kt`'s `invalidateNow consumes the pending bridge left by stop and
is a no-op without one`: the actual fast-vs-slow timing race lives entirely in Swift `Task`
cancellation/scheduling and needs real `CardSession` hardware to observe — unreachable from the Simulator,
same limitation this test file's own header comment already states, and the same reason
`IosProximityPresentmentTest.kt` can't reach `awaitConsent()`'s real call site either (no BLE wire on the
Simulator). What a Simulator test genuinely can verify, and does: the Kotlin-side bookkeeping the
mechanism depends on — `stop()` leaves the bridge reachable for a later `invalidateNow()` (the "something
real to preempt" case), `invalidateNow()` consumes it exactly once, and both a pre-`stop()` call and a
repeat call afterward are safe no-ops. Required widening `IosNfcHceTransport.pendingStopBridge` from
`private` to `internal`, same justification pattern as `delegate`'s own visibility in the same class.
**Verified as a genuine positive control**: temporarily removed the `pendingStopBridge = null` line,
confirmed the test failed, restored it, confirmed it passes again.

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test` 460/460
(+1), `:shared-ui:testAndroidHostTest` 483/483, `:shared-ui:iosSimulatorArm64Test` 531/531 — all from
actual `TEST-*.xml` files, genuinely executed. `detekt`/`ktlintCheck` clean. `generateIosProject`
succeeded. A real device was connected (`Martin's iPhone`) — full `xcodebuild` for `EudiWallet`
(embedding `EudiWalletDocumentProvider`) against it succeeded (`** BUILD SUCCEEDED **`), and the build was
installed onto that device (`xcrun devicectl device install app`). **Not yet exercised on the device
itself**: a real fast-BLE tap, confirming `CardSession` tears down at `Requesting` rather than waiting the
full 3 seconds, and confirming the card picker doesn't appear (or appears less often) when the phone is
held near the reader only briefly afterward — this, and the still-open real-hardware passes for the two
findings above, remain outstanding.

**Update, tried and reverted the same night, after real-device testing (see the Sixth finding below for
the joint verdict): this early-invalidation path is a confirmed reliability regression, not just an
insufficient optimization — see the correction at the end of the Sixth finding for the real-device
evidence and what was reverted.** Recorded here rather than deleted so the same optimization isn't
re-attempted without knowing why it failed.

### Sixth real-device finding on Option 4: a third, faster `CardSession`-invalidation trigger, based on NFC's own trailing-read pattern — the fifth finding's fixed count rejected, inactivity-based version implemented instead

The fifth finding's BLE-`Requesting`-triggered `invalidateNow()` still only preempts the fixed 3-second
timer in the fast-BLE case. Investigated a third, faster trigger based on the trailing-NDEF-read pattern
itself — this needs no BLE progress at all, so it can fire in *every* case, slow or fast.

**Design confirmed safe before implementing, per two explicit questions**:

1. *What happens to a trailing `READ_BINARY` that arrives after `CardSession` has already been
   invalidated?* Traced precisely: a genuinely new APDU can't reach this app at all once invalidated —
   `session.eventStream`'s `.received` case is only ever delivered for an active session, and CoreNFC
   simply stops yielding events for one that's gone. An APDU already in flight at the moment of
   invalidation hits `respond(to:with:attempt:)`'s own pre-existing terminal `catch { }` (`NfcHceBridge.swift`)
   — already there, adapted from pagopa, not something added for this. **Correction made explicitly, not
   assumed**: the actual safety net here is CoreNFC's own event-stream lifecycle plus that pre-existing
   catch-all — *not* `ndefHandoverCompleted`, which guards a different, unrelated failure mode (a repeat
   `SELECT` re-entering `MdocNfcEngagementHelper`'s construction logic, the original 6A82-adjacent bug).
2. *Is "exactly 2 trailing reads" (observed once on real hardware) a reliable count to hard-code?*
   No — rejected. The NDEF Type 4 Tag read pattern genuinely needs at least two reads (`NLEN`, then
   content) — a real protocol floor, not coincidental — but a larger Handover Select (more connection
   methods, longer service names) can need the content read split across additional `READ_BINARY`s at
   increasing offsets, exceeding 2. A fixed-count trigger would invalidate `CardSession` before such a
   reader finished reading its own message — not a graceful fallback, a silently broken tap, since the
   reader would never learn the BLE connection method's UUID and never attempt to connect at all.

**Implemented instead: an inactivity-based trigger, general rather than count-based.** Tracks the time
since the last APDU once handover has completed; fires `invalidateNow()` after 500ms of silence,
resetting on every new APDU in between — self-adapting to however many reads a given message actually
needs, rather than assuming a specific count.

- `IosNfcHceTransport.kt`: `ApduDelegate` gains `resetInactivityTimer()` (`internal`), called from
  `processCommandApdu` for every APDU (a no-op before handover completes) and from a new
  `IosNfcHceTransport.armInactivityTimeout()` (called once, directly, at the moment handover completes —
  covering the case where that same `SELECT`/`READ` is the reader's *last* NFC interaction, with no
  further APDU ever arriving to reactively re-arm it). Cancels and replaces any still-pending timer job
  each time; fires `onInactivityTimeout` (wired to `invalidateNow`) once `INACTIVITY_TIMEOUT` (500ms, a
  new named `internal val` constant) elapses with nothing new. A dedicated log line —
  `"inactivityTimeout: 500ms of NFC silence since the last APDU — invalidating CardSession early"` —
  distinguishes this trigger from the BLE-`Requesting` one in device logs, as asked.
- `IosProximityPresenter.kt`: `onColdTapHandoverComplete()` calls `nfcTransport.armInactivityTimeout()`
  right alongside setting `ndefHandoverCompleted = true`.
- `invalidateNow()`'s own doc comment updated to name all three redundant triggers in firing order:
  this one, then the BLE-`Requesting` one, then `Timing.stopDelay` itself as the floor — each is a safe
  no-op once an earlier one has already run (`invalidateNow()`'s existing idempotency, unchanged).

**Tests added**, `IosNfcHceTransportTest.kt`, both using `scope = this` (the test's own `TestScope`) so
the `delay()`-based timer advances on virtual time rather than a real 500ms wall-clock wait:

- `500ms of silence after handover completes triggers the inactivity timeout`: sends one trailing read,
  advances exactly the timeout window, asserts it fired.
- `a new APDU just before the window elapses resets the inactivity timer instead of firing early`: sends
  a read, advances to just short of the window, asserts it hasn't fired; sends a second read (asserting
  it's still answered `SUCCESS`, not rejected — the exact failure mode the fixed-count design would have
  risked); advances to just short of the *reset* window, asserts still not fired; advances past it,
  asserts it fires.

**Verified both as genuine positive controls**: temporarily removed `inactivityJob?.cancel()` — the
reset test failed as expected (fired prematurely). Separately, temporarily removed the
`resetInactivityTimer()` call from `processCommandApdu` entirely — both new tests failed (nothing ever
arms the timer without it). Both reverted and reconfirmed green.

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test` 462/462
(+2), `:shared-ui:testAndroidHostTest` 483/483, `:shared-ui:iosSimulatorArm64Test` 531/531 — all from
actual `TEST-*.xml` files, genuinely executed. `detekt`/`ktlintCheck` clean. `generateIosProject`
succeeded. A real device was connected (`Martin's iPhone`) and the full `xcodebuild` for `EudiWallet`
(embedding `EudiWalletDocumentProvider`) against it succeeded (`** BUILD SUCCEEDED **`) — genuinely
compiled and signed for that real device, not a generic/simulator fallback. **The device disconnected
before the install step** (`xcrun devicectl device install app` failed:
`com.apple.dt.CoreDeviceError error 4000`, `xcrun devicectl list devices` then showed it `unavailable`)
— so, unlike the two findings above, this one was **not** installed or run on hardware this pass, and
**no log-timestamp comparison between the inactivity trigger and the previous BLE-triggered approach was
captured**, contrary to what was asked. Once reconnected: install, tap, and pull the device log to
confirm the new `inactivityTimeout: ...` line fires (and roughly when, relative to
`onColdTapHandoverComplete`) ahead of `awaitConsent`'s own `invalidateNow` call in the same session.

**Update, later the same night: both early-invalidation triggers (this finding's inactivity timer and the
Fifth finding's BLE-`Requesting` trigger) were tried on real hardware, found to cause a genuine BLE
reliability regression, and reverted — a rejected approach, recorded here rather than deleted.**

A user report after further real-device testing — quiet-period NFC teardown behaving correctly, but BLE
peripheral discovery failing repeatedly per the device log — prompted a direct, real-device A/B test
rather than further theorizing:

1. Both triggers were temporarily disabled (the inactivity timer short-circuited with an unconditional
   early return; the `awaitConsent()` call to `invalidateNow()` commented out), leaving only the
   original, unmodified `Timing.stopDelay` as the sole `CardSession` teardown path — exactly the state
   before the Fifth finding.
2. Rebuilt, reinstalled, and real-device tested: phone moved away immediately after the tap, no card
   picker, no backgrounding involved at all.
3. **Confirmed reliable**: BLE connected and completed a full, clean end-to-end transfer — a real
   `DeviceResponse` sent, session-termination received, transport closed gracefully. The same scenario
   with both early-invalidation triggers active had been failing.

**Conclusion**: ending `CardSession`/CoreNFC's HCE session early does not give CoreBluetooth's own
peripheral stack enough time to reach a stable, discoverable advertising state before a scanning reader
starts looking for it. The original `Timing.stopDelay` (3 seconds) is not an arbitrary, safely-shortenable
constant — empirically, on real hardware, it is close to the floor CoreBluetooth's peripheral stack
actually needs after `CardSession`/`NFCPresentmentIntentAssertion` teardown for a scanning reader to
reliably discover the already-advertising BLE peripheral. Neither trigger's own individual reasoning was
wrong on its own terms (the BLE-`Requesting` trigger's timing analysis, the inactivity trigger's rejection
of a fixed read-count, the safety analysis of a post-invalidation trailing read) — the actual, real-device
cost was a layer beneath any of that reasoning, in CoreBluetooth's own peripheral-advertising stack, not
in this app's own NFC-side logic.

**Reverted, cleanly, not just disabled**: `NfcHceBridge.swift`'s `pendingTeardownTask`/
`pendingTeardownSession` properties, `invalidateNow()`, and `Timing.earlyInvalidateBuffer` removed;
`stop()`'s delayed-teardown `Task` reverted to its original, non-cancellable `try? await
Task.sleep(for: Timing.stopDelay)` form. `NfcHceBridge.def`'s `invalidateNow` declaration removed.
`IosNfcHceTransport.kt`'s `invalidateNow()`, `pendingStopBridge`, `armInactivityTimeout()`,
`ApduDelegate.resetInactivityTimer()`/`inactivityJob`/`onInactivityTimeout`, and the `INACTIVITY_TIMEOUT`
constant all removed, along with the `processCommandApdu` call site and now-unused imports.
`IosProximityPresenter.kt`'s `onColdTapHandoverComplete()`/`awaitConsent()` calls into either mechanism
removed. `IosNfcHceTransportTest.kt`'s three tests for this mechanism (and their now-unused helpers)
removed along with it — they tested real code that no longer exists. `Timing.stopDelay`'s own doc comment
now carries this finding's summary directly, so a future reader hits the "tried and reverted" context at
the exact constant someone would otherwise be tempted to shorten again.

**Unaffected by this revert, confirmed still valid**: the Fourth finding's UX mitigation (the "Tap
complete — you can move your phone away now" snackbar) and the Seventh finding below (the
`bluetooth-peripheral` background mode fix) — both are independent of `CardSession`'s teardown timing
itself, and neither touches `Timing.stopDelay` or anything this revert removed.

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test` 459/459
(−3, the three removed tests), `:shared-ui:testAndroidHostTest` 483/483, `:shared-ui:iosSimulatorArm64Test`
531/531 — all from actual `TEST-*.xml` files, genuinely executed. `detekt`/`ktlintCheck` clean. This is
now the correct, final state for today's commit regarding `CardSession` teardown timing — do not
re-attempt early invalidation of `Timing.stopDelay` without first establishing, on real hardware, how much
time CoreBluetooth's peripheral stack genuinely needs after `CardSession` teardown, independent of
anything on the NFC side.

### Closing note on the card-picker investigation: confirmed as expected iOS behavior, not further pursued

A separate report, distinct from the fourth/fifth/sixth findings above (those are about the picker
appearing *after* a successful tap, while `CardSession` is winding down): with "Share over NFC" **off**
— no `CardSession`/`NFCPresentmentIntentAssertion` ever active — tapping the phone against a reader can
still show the native Apple Pay/Wallet card picker.

**Confirmed as expected iOS default routing, not a bug, and not caused by any entitlement in this app**:

- Traced the full call graph: `armColdTapEngagement()` (the only function that ever calls
  `nfcTransport.start()`, in turn the only path to `NFCPresentmentIntentAssertion.acquire()`/
  `CardSession()`) has exactly one call site, `onScreenEntered()`'s `if (nfcEngagementEnabled)` branch;
  `nfcEngagementEnabled` has exactly three writers (its `false` default, `setNfcEngagementEnabled()`,
  and `turnNfcEngagementOffAndSignalUi()`), none of them capable of leaving it stale relative to what the
  switch shows. No code path starts `CardSession` while the switch is off.
- Re-checked `iosApp/project.yml` directly (`git status --short` confirmed no uncommitted changes to it):
  `com.apple.developer.nfc.hce.default-contactless-app` is not present and never has been active — it
  was tried once, failed a real, signed device build outright (`Entitlement ...
  default-contactless-app requires approval from Apple to include in a profile`, "Second real-device
  finding" above), and was reverted before ever shipping. Only the base `com.apple.developer.nfc.hce`
  entitlement and `select-identifier-prefixes` are present, and that pairing was already proven
  (that same earlier finding) to be insufficient on its own to claim priority over the system's default
  routing — claiming priority needs an actively-held `NFCPresentmentIntentAssertion`, which the switch
  being off means never happens.

**Independent confirmation this is a known, shared limitation, not specific to this app — verified via a
live fetch of Apple Developer Forums thread 789477 ("HCE issues"), not just cited secondhand.** Another
developer building a similar HCE-based app reports the identical symptom: *"if our application is not
selected as default NFC application our users may see Wallet popup when there's no active presentment
intent."* An Apple DTS engineer's own reply in that thread is more definitive than a "maybe" on the two
possible mitigations, worth recording precisely:

- `PassKit`'s `requestAutomaticPassPresentationSuppression()` — **explicitly ruled out by DTS, not merely
  unverified**: *"Pass suppression won't work, because it will suppress your app too"* — and, more
  fundamentally, *"the pass presentation suppression managed entitlement and its API family does not
  interact with HCE communication at all, as they are on completely separate layers of the system."*
  Requires its own separate entitlement (`com.apple.developer.passkit.pass-presentation-suppression`)
  regardless, so this is doubly not worth pursuing.
- **The only fix DTS names**: register AIDs via `com.apple.developer.nfc.hce` *and* have the user set the
  app as their device's default contactless app — i.e. exactly the
  `com.apple.developer.nfc.hce.default-contactless-app` entitlement this project already attempted and
  had rejected outright for this App ID (Second real-device finding). Apple's own forum answer confirms
  there is no other supported way to suppress this popup while `CardSession` isn't active.

**Conclusion**: accepted as an unavoidable, Apple-controlled UX characteristic of HCE-based apps whose
NFC involvement is switch-gated rather than registered as the device default — which is deliberate here,
not an oversight (`wiki/IOS_NFC_PLAN.md`'s own design: NFC is always explicit, never passive). No further
investigation planned unless Apple documents a new supported mitigation, or this App ID is ever granted
the default-contactless-app capability and a product decision is made to pursue that path (its own
tradeoffs — becoming the device's default handler for these AIDs generally — not evaluated here).

### Seventh real-device finding on Option 4: BLE discovery unreliability traced to the app losing foreground focus while cold-tap is armed, not to BLE itself

Reported: BLE works reliably via QR-then-BLE, but was unreliable specifically for cold-tap-then-BLE, with
one real-device log showing BLE advertising 37+ seconds before the reader started scanning for it —
including the exact window the previous finding's card picker took over foreground focus.

**Investigated whether the two paths differ in *when* advertising starts — they don't.** Traced both:
`IosProximityCoordinator.qrEvents()`'s flow builder calls `onScreenEntered()` then `startQrEngagement()`
back to back at screen-entry time, and `startQrEngagement()` starts advertising *before* the QR is even
generated — the QR payload embeds the same BLE connection method advertising already produced.
`armColdTapEngagement()` is called from the very same `onScreenEntered()`, at the same trigger point.
**The real asymmetry is human-driven elapsed time, not a code-level design difference**: once a QR is
visible, scanning it is the tester's one obvious next action, so the gap is naturally short; once
cold-tap is armed, nothing couples "screen open" to "user taps soon" — the user can leave the screen
open indefinitely, including long enough for the previous finding's card picker to take over.

**Root cause, confirmed against Apple's own documentation, not assumed — with a real quote this time, not
just a page title:** *"On the peripheral side, advertising is disabled, and any central trying to access
a dynamic characteristic value of one of the app's published services receives an error"* — Apple's Core
Bluetooth Background Processing guide, describing exactly what happens when an app loses foreground
focus **without** the `bluetooth-peripheral` background mode declared. Checked this app's own
configuration directly (`grep -rln "UIBackgroundModes" iosApp/` returned nothing): **this app declared no
Bluetooth background mode at all.** So the moment anything takes foreground focus away — including the
previous finding's own card picker — BLE advertising doesn't merely degrade, it **stops outright**, and
only resumes once the app returns to the foreground. A purely-foreground, never-backgrounded long wait
has no documented degradation in the same source; this is specifically a foreground-focus-loss mechanism,
not an elapsed-time one.

**A third option (advertise at `onHandoverComplete` instead of arm time) was considered and rejected as
architecturally incompatible**, not merely suboptimal: Option 4's static Handover Select design requires
the BLE peripheral to already exist and be advertising *before* any tap can happen — the reader learns
the connection method's UUID from the Handover Select it reads *during* the tap itself, which by
definition happens against an already-live peripheral. By the time `onColdTapHandoverComplete()` runs in
Kotlin, the reader has already read that UUID from the NFC exchange that preceded it; advertising a fresh
UUID at that point wouldn't reach a reader already holding the old one.

**Fix: declare `bluetooth-peripheral` background mode** (`iosApp/project.yml`, `EudiWallet` target's
`Info.plist` properties). **Confirmed against Apple's documentation before adding, not assumed** — a
real quote, not a page title, from the same Core Bluetooth Background Processing guide: *"The Core
Bluetooth background execution modes are declared by adding the `UIBackgroundModes` key to your
`Info.plist` file"* — purely self-declared, no mention anywhere of requiring separate Apple approval or
a provisioning-profile capability, unlike `com.apple.developer.nfc.hce.default-contactless-app` (Second
finding above), which explicitly does. The real, signed-device `xcodebuild` below confirms this in
practice too: no provisioning error, unlike that earlier attempt.

**Flagged and reconciled against an existing, deliberate decision in the same file before touching it**:
`project.yml` already had a comment dated 2026-09-04 stating "NO BACKGROUND MODES... deliberate" — a
`BGProcessingTask` for credential top-up was removed specifically so the wallet database could carry
`NSFileProtectionComplete` (unreadable while the device is locked). `bluetooth-peripheral` is a
categorically different mechanism — it only keeps an *already-armed*, user-initiated peripheral alive
through a foreground-focus loss, nothing scheduled or deferred — so it doesn't reopen that decision, and
the comment now says so explicitly rather than being silently left stale/contradictory. **One real
limit, documented in the same comment, not glossed over**: this only helps the backgrounded-but-unlocked
case (the picker scenario is exactly that — it doesn't lock the device). If the device is genuinely
*locked* during the wait, the document database is still unreadable regardless of whether BLE itself
survives, so a share attempted while truly locked still cannot complete either way — `bluetooth-peripheral`
doesn't change that, and was never expected to.

**Kept, not replaced**: the "Tap complete — you can move your phone away now" snackbar (third finding)
stays — even with BLE surviving a foreground-focus loss, avoiding the card picker's visual interruption
entirely is still the nicer experience than merely surviving it in the background.

**Verified**: no Kotlin/Swift production code changed (config-only), so the Kotlin scoped test suites and
`detekt`/`ktlintCheck` are unaffected by this specific change — not re-run for it alone.
`generateIosProject` succeeded; the generated `Info.plist` was checked directly (`PlistBuddy -c "Print
:UIBackgroundModes"`) and confirmed to contain `bluetooth-peripheral`. The real-device `xcodebuild`
(`EudiWallet` + `EudiWalletDocumentProvider`, `Martin's iPhone`) succeeded with no provisioning/entitlement
error, and the build was installed onto that device. **Not yet exercised on the device itself**: the
actual cold-tap-then-backgrounded-then-tap-close scenario needs a real hands-on test — hold the phone
near a reader after tapping, let the card picker (or another app switch) take foreground focus, and
confirm the verifier still discovers and connects, which requires a physical action this investigation
cannot perform on its own.

### Eighth real-device finding on Option 4: diagnostic logging added, then real-device use confirms the app itself loses foreground focus during the BLE connection window

Added purely diagnostic logging (`BleDiagnosticsLogger.swift`, new file in the `NfcHceBridge` Swift
package, plus small wiring in `IosProximityPresenter.kt`/`NfcHceBridge.def`) — no behavior change —
specifically to move from inferring the Seventh finding's backgrounding hypothesis to directly observing
it: `UIApplication` lifecycle state and the system Bluetooth radio's own `.state`, logged once at
cold-tap arm time (right after `armColdTapEngagement()`'s own call to `advertise()` returns) and then
once per second for ~20 seconds starting at `onColdTapHandoverComplete()`, plus live logging of
`willResignActive`/`didEnterBackground`/`willEnterForeground`/`didBecomeActive` notifications the instant
they fire (not just at the next 1-second poll).

**Two scoping limits, deliberate, not oversights**: this cannot observe multipaz's own
`BlePeripheralManagerIos` directly — that class and the `CBPeripheralManager` it owns are private to
multipaz, a dependency with no source in this repo (the same cross-repo boundary as elsewhere, §8) — so
this creates its own, separate, diagnostic-only `CBPeripheralManager` purely to read the *shared system
radio's* power state, never logging its own (always-`false`) `isAdvertising` as if it reflected
multipaz's real activity. The logged "advertising arm time" is the moment this app's own call to
`advertise()` returned, not literally multipaz's internal `startAdvertising()` call, for the same reason.

**Real-device finding, reported by the user after installing and using a build with this logging** (not
independently captured by this investigation — no device was connected at the time this logging shipped,
so this is the user's own hands-on report, recorded here the same way every other real-hardware result
in this section is): the app genuinely loses foreground focus — `applicationState=inactive` and a
`willResignActive` notification — for several seconds right after NFC handover completes, matching the
previously-suspected Apple Pay/Wallet card-picker interruption (Fourth finding). BLE only succeeds
reliably once the app returns to `active` state (`didBecomeActive`). **This moves the Seventh finding's
own conclusion from inferred to directly observed** — the mechanism (foreground-focus loss stops BLE
advertising per Apple's own documented behavior, already cited there) is confirmed actually happening on
this exact flow, not just consistent with Apple's general documentation.

**Open follow-up, identified but not attempted tonight, given the length of this session**: BLE
connection attempts are currently fired once, unconditionally, regardless of `UIApplication`'s state —
if the app is mid-focus-loss when a connection attempt would otherwise succeed, that attempt is wasted.
Making the connection attempt foreground-aware — waiting for, or retrying on, `didBecomeActive` rather
than firing regardless of current app state — is the logical next step this finding points to, but is
not implemented here. Whether the `bluetooth-peripheral` background mode (Seventh finding) reduces how
often this matters in practice, versus how often a foreground-aware retry would still be needed on top
of it, is not yet known and would need its own real-device comparison.

**Verified**: `:shared-logic:testAndroidHostTest` 115/115, `:shared-logic:iosSimulatorArm64Test` 459/459,
`:shared-ui:testAndroidHostTest` 483/483, `:shared-ui:iosSimulatorArm64Test` 531/531 — all from actual
`TEST-*.xml` files, genuinely executed (no test count changed by this pass — a diagnostic-logging-only
change, no test behavior affected). `detekt`/`ktlintCheck` clean. `generateIosProject` succeeded. A real
device was connected (`Martin's iPhone`) and the full `xcodebuild` for `EudiWallet` (embedding
`EudiWalletDocumentProvider`) against it succeeded (`** BUILD SUCCEEDED **`).

# KMP unification feasibility — EUDI wallet (Android + iOS)

Research + planning note on whether the EUDI reference wallet could be converted to
Kotlin Multiplatform (KMP) / Compose Multiplatform (CMP) to run on both Android and iOS
with full feature parity — and the role of **Multipaz** (OpenWallet Foundation) as the
shared engine.

> **Status:** research/planning only — no code changes proposed here.
> **Date:** 2026-07 · **Android** `eudi-lib-android-wallet-core` 0.29.0 (on `org.multipaz` 0.99.0), Kotlin 2.4.0, AGP 9.3.0 · **iOS** `eudi-lib-ios-wallet-kit` 0.37.5, Swift 6 / SwiftUI · **Multipaz** 0.100.0 (Apache-2.0, pre-1.0).

---

## 1. Bottom line

A single shared codebase for Android + iOS is **more feasible than a naive reading suggests**,
because a production-grade **KMP wallet engine already exists and we already ship it**: EUDI's
Android `wallet-core` is a wrapper over **Multipaz** (`org.multipaz`), a Kotlin-Multiplatform
identity SDK with a **working iOS ISO 18013-5 proximity stack** — the exact thing the EU's own KMP
verifier stalled on.

The recommended target is therefore **not** a from-scratch unification, and **not** a Multipaz-only
rewrite, but a **combined stack**:

- **Multipaz as the explicit shared KMP engine on both platforms** — SecureArea, Storage,
  Document/Credential, ISO 18013-5/-7 proximity + DC-API, mdoc/SD-JWT, Longfellow ZK.
- **EUDI's EU-specific libraries layered on top** — RQES (no Multipaz equivalent), `statium`
  status-list (already KMP), `etsi` trusted-list (already KMP), and the ARF/LoA trust + EU-issuer
  VCI/VP behaviour.
- **Compose Multiplatform UI** (`multipaz-compose` is already CMP with iOS variants).

This dissolves the two blockers identified below (no-KMP-core; iOS-proximity-is-a-separate-Swift-stack).
It remains a **multi-quarter program**, and RQES + ARF compliance stay EU-specific, but the central
technical wall is removed.

---

## 2. The reframing — we already ship Multipaz on Android

Verified from this repo's own resolved dependencies (Gradle cache): `org.multipaz:multipaz`,
`multipaz-android`, `multipaz-android-legacy`, `multipaz-compose`, and `multipaz-dcapi` — all at
**0.99.0** — are pulled in transitively via `eudi-lib-android-wallet-core` 0.29.0. The cache even
holds the **iOS KLIB variants** (`multipaz-compose-iosarm64/-iossimulatorarm64/-iosx64`,
`multipaz-dcapi-ios*`), because Multipaz publishes those targets.

The `eudi-lib-android-wallet-core` README's architecture diagram labels `org.multipaz` as its
"SecureArea, Storage" layer. So **Multipaz is not an alternative to what we run — it is the engine
underneath what we run, on Android.** EUDI re-implemented the equivalent lower layers in Swift for
iOS rather than using Multipaz's iOS targets. The strategic question is whether to **promote Multipaz
from a hidden Android-only substrate to our explicit shared engine for both platforms.**

Corroborating the EU's own direction: the EU **Age-Verification backend** (`av-dc-api-backend`)
vendors the full Multipaz stack, and **Longfellow ZK** is shared Google/EU technology. The two
ecosystems are converging, not diverging.

---

## 3. The (softened) governing constraints

| Constraint | Status after Multipaz | Consequence |
|---|---|---|
| "No KMP wallet-core" | **Softened.** A KMP wallet *engine* exists (Multipaz) and is already our Android substrate. What's missing is a KMP *EUDI wallet-core* wrapper. | Build/port a wrapper on Multipaz, or push EUDI to ship a KMP wallet-core — not invent an engine. |
| ISO 18013-5 proximity on iOS | **Cleared by Multipaz** (QR + BLE; see §4). | iOS holder NFC-tap engagement is impossible on any iOS SDK (Apple limit), not a Multipaz gap. |
| Both apps already exist at parity | Unchanged | This is a refactor-to-unify of two mature apps; RQES + ARF stay EU-specific. |

---

## 4. Multipaz fact sheet + iOS proximity verdict

| | |
|---|---|
| Identity | OWF KMP SDK for ISO mdoc/mDL + SD-JWT VC — issuance, storage, proximity & online presentment |
| Lineage | Google "Identity Credential" (`com.android.identity`, David Zeuthen et al.) → donated to OWF → rebranded Multipaz, namespace → `org.multipaz` |
| Version / license | **0.100.0** (2026-07-08), **Apache-2.0**, pre-1.0 (1.0 targeted late-2026/early-2027), releases every 4–8 weeks |
| Governance | OpenWallet Foundation (Linux Foundation); maintainers are Google engineers |
| KMP targets | Android, **iOS (first-class)**, JVM/server, JS/Wasm — one `commonMain` core |
| Key modules | `multipaz` (core), `multipaz-compose` (CMP UI), `multipaz-swiftui`, `multipaz-doctypes` (mDL/EU-PID/photoID), `multipaz-csa` (Cloud Secure Area), `multipaz-openid4vci`, `multipaz-verifier`, `multipaz-dcapi`, `multipaz-longfellow` (ZK) |

**iOS proximity verdict — affirmative.** Multipaz has a real iOS ISO 18013-5 stack in shared code,
not stubs: `BlePeripheralManagerIos.kt` (~533 lines, `CBPeripheralManager` + L2CAP, holder),
`BleCentralManagerIos.kt` (~670 lines, reader), `SecureEnclaveSecureArea.kt`, `NfcTagReader.ios.kt`
(reader). Shared proximity screens compile and run on iOS in the TestApp.

**Apple-imposed nuance:** iOS holders engage via **QR + BLE** (Apple grants no NFC HCE to third-party
apps, so NFC-tap holder engagement is unavailable on any iOS SDK). Android holders keep QR + NFC + BLE.

---

## 5. Architecture difference — Multipaz vs eudi-lib

| Dimension | Multipaz | EUDI eudi-lib |
|---|---|---|
| Codebase | One KMP core, all platforms | Per-platform SDKs; Android `wallet-core` umbrella + separate iOS Swift `wallet-kit` |
| Secure keys | `SecureArea` iface: Keystore/StrongBox, Secure Enclave, Software, Cloud Secure Area | Uses **Multipaz's `SecureArea`** on Android; bespoke Swift on iOS |
| Storage | `Storage` KMP abstraction (SQLite) — same everywhere | Multipaz `Storage` on Android; separate Swift lib on iOS |
| 18013-5 transfer | In the KMP core → **shared Android + iOS** | Android = thin wrapper over Multipaz; **iOS = separate Swift stack** |
| UI | `multipaz-compose` (CMP incl. iOS) + `multipaz-swiftui` | Jetpack Compose (Android) + SwiftUI (iOS), no sharing |

The difference is not the engine (on Android it is the same engine) — it is that Multipaz already
extends that engine to iOS, whereas EUDI re-implemented it in Swift.

---

## 6. Shareability map (this app)

Of ~401 Android Kotlin files:

- **~15–20% immediately platform-neutral** — domain models, config, interactors/mappers, Ktor
  networking. Ktor, kotlinx-serialization, coroutines-core, kotlinx-datetime, Koin-core are KMP-capable.
- **~10–15% shareable after dep swap / expect-actual** — DI (koin-android → core), Ktor engine, prefs.
- **~55–60% platform-bound today** — the ~215 Compose UI files, `storage-logic` (Room + SQLCipher —
  note Multipaz supplies its own KMP Storage), Keystore/StrongBox crypto, biometrics, WorkManager,
  manifest/deep-links, and every wallet-core call site.

Five hardest subsystems (ranked): ① wallet-core call sites → ② ISO 18013-5 proximity (now solvable
via Multipaz iOS) → ③ secure storage → ④ the Compose UI surface → ⑤ biometrics + app-shell.

---

## 7. Reuse map — what Multipaz covers vs what stays EUDI

Mapped to this app's actual EUDI-SDK import surface (import counts in parentheses):

| Our dependency | Multipaz coverage | Verdict |
|---|---|---|
| `wallet.document.*` (105) — Document/Credential/formats | Multipaz `DocumentStore`/`Document`/`Credential` (already the substrate) | **Reuse Multipaz** |
| `iso18013.transfer` (11) + `wallet.transfer.openId4vp` (9) — proximity/presentment | Multipaz mdoc transport + presentment, **incl. iOS** | **Reuse Multipaz — the iOS win** |
| `wallet.dcapi` (5) — DC-API / 18013-7 | `multipaz-dcapi` (already pulled, incl. iOS variants) | Reuse Multipaz |
| `sdjwt.vc` (5) | Multipaz SD-JWT VC | Reuse Multipaz |
| `wallet.issue.openid4vci` + `openid4vci.*` (~30) | Multipaz has a VCI client; EUDI's `openid4vci-kt` is more battle-tested vs EU issuers | Keep EUDI (evaluate) |
| `wallet.trust` (5) | Multipaz `TrustManager` is generic, not ARF-aware | Keep EUDI (ARF/LoA-high) |
| `etsi1196x2`/`etsi119602` (20), `statium` (2) | KMP *in name*: `eudi-lib-kmp-statium` publishes **only** `androidJvm`/`jvm` variants (checked in its Gradle module metadata on Maven Central, 0.4.1 and 0.5.1 — no Kotlin/Native at all) | Keep EUDI on Android; iOS uses multipaz's own `org.multipaz.revocation` |
| `rqesui.*` + `rqes.*` (28) — remote signing | **Multipaz has none** | **Keep EUDI RQES** (biggest EU-only piece) |
| `wallet.transactionLogging` (15) | app/EUDI-specific format | Keep EUDI |

---

## 8. Feature-parity target

The Android and iOS apps are **already at parity** — no Android feature is missing on iOS. The only
permanently-native, Apple-only items are: the **Apple "Identity Documents in Wallet" document-provider**
entitlement (iOS 18+), the **W3C Digital Credentials API** bridge, and Keychain/Secure Enclave /
LocalAuthentication / CoreBluetooth (native by nature — and largely handled inside Multipaz's iOS actuals).
A unified app must *not lose* these.

---

## 9. Options

**A — Combined stack: Multipaz engine + EUDI EU-libs + CMP UI.** ⭐ *recommended*
Multipaz as the shared KMP engine on both platforms; keep EUDI's Apache-licensed RQES / statium / etsi /
ARF-trust on top; share the UI via Compose Multiplatform. Solves the no-KMP-core + iOS-proximity blockers
while retaining EU-specific compliance. Big but tractable; the engine already exists and is our Android substrate.

**B — Multipaz-only engine (drop eudi-libs).**
Cleanest single stack, but **loses RQES, ARF/LoA-high trust, and EU-issuer-specific VCI/VP handling.**
Not recommended for an EUDI-lineage national wallet.

**C — Status quo: two native stacks.**
No engineering cost now, but permanent duplication (Android on Multipaz-under-wallet-core; iOS on the Swift
stack) and the iOS proximity stack stays the weaker, separately-maintained one.

---

## 10. Recommended path — combined stack, incremental

Two routes to promote Multipaz to the explicit shared engine:
- **(a) Push EUDI to ship a KMP `wallet-core`** built on Multipaz's iOS targets, and adopt it — lowest
  effort for us; depends on the EU roadmap (which is trending this way).
- **(b) Build our own thin KMP wrapper directly on Multipaz** + port EU-specific bits to `commonMain` —
  more work, under our control; Multipaz does the heavy lifting.

| Phase | Scope | Risk | Rough effort |
|---|---|---|---|
| **0. Spike** | Reproduce the KMMBridge/XCFramework → SPM path; run the Multipaz TestApp on iOS; prove one shared module in both apps. | Low | 1–2 wks |
| **1. Shared domain + utils** | Platform-neutral models, config, validators, formatters, statium/etsi usage → `commonMain`. Koin-android→core; Ktor engine per platform. | Low–Med | 1–2 mo |
| **2. Multipaz as explicit shared engine** | Adopt route (a) or (b): a KMP wallet API over Multipaz `SecureArea`/`Storage`/`DocumentStore`/transfer; stop leaking Android-wrapper types into view-models. | High | 3–5 mo |
| **3. Shared presentation logic** | View-models/interactors → `commonMain` on top of the engine + shared networking. | Med | 2–3 mo |
| **4. EU-specific + native edges** | RQES (stays Swift on iOS for now), ARF trust config, biometrics, camera/QR, deep links; validate Multipaz iOS proximity against EUDI verifiers. | High | 3–5 mo |
| **5. Compose-Multiplatform UI** | Migrate UI + resources to CMP (`multipaz-compose` foundation); retire SwiftUI screens; keep Apple document-provider / W3C-DC native. | Med–High | 4–6 mo |

Full Option A ≈ **12–18 months**, but every phase delivers standalone value and can stop early (after
Phase 3 you have a shared engine + logic with two native UIs).

---

## 11. Corrections to earlier premises

1. **Licensing:** the eudi-lib *libraries* are **Apache-2.0**, not EUPL — only the reference *apps*
   are EUPL-1.2. Mixing Multipaz (Apache) with eudi-libs (Apache) under our EUPL app is clean and is
   *already the current shape*. There is no new license conflict.
2. **"No KMP wallet-core" is not an absolute blocker** — a KMP wallet *engine* (Multipaz) exists,
   handles iOS proximity, and is already our Android substrate. The missing piece is a KMP EUDI
   *wrapper*, which is far smaller than an engine.

---

## 12. Risks & unknowns

- **iOS validation burden** — Multipaz's iOS surface (Secure Enclave, BLE, SKIE/SwiftUI bridge) is
  newer than its Android surface; must be proven against target iOS versions and EUDI verifiers — but
  it's one codebase, not a parallel Swift stack.
- **Pre-1.0 churn** on both stacks (Multipaz 0.x, EUDI libs) — pin versions, budget for bumps.
- **RQES + ARF stay EU-specific** — not provided by Multipaz; the combined stack must retain them.
- **Attestation** models differ (Android key-attestation vs Apple App Attest) — needs backend work.
- **Flagged / unverified:** no published formal EUDI↔Multipaz interop certification; exact OpenID4VP
  draft (1.0 vs draft-24) to confirm at integration; "US states ship the Multipaz SDK specifically"
  is plausible but unverified. Confirm SDK/version status against the upstream orgs before committing budget.

---

## 13. Phase 3 — shared presentation (Compose Multiplatform), execution plan

Goal: move view-models + presentation logic + Compose UI into `commonMain` (CMP), so both
platforms render the same screens on top of the `WalletEngine` seam. This is also where the
Phase-2 "rich document consumers" land, because they are entangled with presentation-layer
localization.

Surface (measured on this repo): **29 view-models** (MVI — `MviViewModel`/`MviContract`
already isolated in `ui-logic`), **103 Compose files / 351 composables**, and the localization
surface that blocked Phase 2: **583 `R.string`/`stringResource` sites across 56 files using
`resourceProvider`**.

Sub-phases (dependency order):

- **3a — Resources & localization foundation (the linchpin).** Migrate strings/drawables to a
  KMP resource system (Compose-Resources `Res` or moko-resources; ~583 sites) and replace the
  Android-`Context` `resourceProvider` with a **KMP string resolver** injected into view-models.
  This is the exact entanglement that blocked the rich document consumers, so it retroactively
  unblocks them. Risk: **high** (largest surface; user-visible text must stay identical).
- **3b — MVI + view-models → commonMain.** Move `MviViewModel`/`MviContract` to `:shared`, then
  view-models feature-by-feature (they depend on interactors [shareable via `WalletEngine`] +
  the 3a resolver + a nav abstraction). Risk: med.
- **3c — Navigation.** Replace Android `navigation-compose` (9 files) with CMP navigation
  (androidx.navigation multiplatform) or a KMP nav abstraction; deep links per platform. Risk: med.
- **3d — Compose UI → CMP.** Move `ui-logic` + feature screens to `commonMain`. Concrete
  blockers: `LocalContext` (23 files), `AndroidView` (1 — QR camera), `accompanist` (2), `coil`→
  coil3 (KMP), `activity-compose` (3). The iOS SwiftUI layer is retired here. Risk: med-high
  (per-platform look/accessibility parity).
- **3e — Platform edges stay native (`expect/actual`).** Camera/QR (CameraX ↔ AVFoundation/
  Vision), biometrics (`BiometricPrompt` 3 files ↔ LocalAuthentication), WorkManager (4), deep
  links, FileProvider. The engine is already covered by the `WalletEngine` seam.
- **3f — Land the Phase-2 leftovers.** With 3a done, migrate the documents list-builder +
  `getDocumentById` success/detail flows: model claim-paths + localized issuer metadata (now
  resolvable via the KMP string resolver), retiring the last wallet-core UI-type coupling
  (`DocumentIdentifier`, `DocumentCategory`).

Overall: multi-quarter, executed feature-by-feature behind the existing seam; each feature stays
shippable on Android throughout.

## 14. iOS WalletEngine implementation — plan

`WalletEngine` is a `commonMain` interface; today only the Android `WalletEngineImpl` (over
`eudi-lib-android-wallet-core`) exists. iOS needs an implementation — the *how* is the decision:

| Option | Approach | Verdict |
|---|---|---|
| **A — Kotlin impl in `iosMain` over Multipaz (KMP)** | Implement `WalletEngine` in `iosMain` using Multipaz's KMP APIs (`SecureArea`, `DocumentStore`, mdoc transport). | **Recommended** — one Kotlin engine; Multipaz's iOS proximity / Secure Enclave already work. |
| **B — Bridge to Swift `eudi-lib-ios-wallet-kit`** | Swift implements a protocol injected into KMP (Kotlin can't call Swift directly). | Awkward inversion; keeps two SDK stacks. Avoid unless Multipaz gaps force it. |
| **C — Hybrid** | Multipaz engine (A) + EUDI Swift **RQES** (no KMP equivalent). | Realistic end-state: A for the engine, Swift for RQES. |

Recommended path (A → C): implement `WalletEngine` on iOS over Multipaz capability-by-capability,
mirroring what exists (revocation, bookmarks, `getMainPidDocument`, `getAllDocuments`, claims),
then grow with Phase-3's richer fields. RQES stays Swift.

Sequencing (why presentation comes first): until the `WalletEngine` *consumers* (view-models /
interactors) run in `commonMain` (Phase 3), an iOS engine has nothing to drive. So Phase 3 is the
enabler; the iOS engine then makes iOS functional. **Retire the proximity (BLE) risk early** by
proving Multipaz on-device against EUDI verifiers before committing the rest.

Effort/risk: med-high; the two risks are (a) proving Multipaz iOS proximity / secure-area against
EUDI verifiers, and (b) RQES remaining platform-specific.

## 15. Navigation migration — Navigation 3 (supersedes UiSerializer/string routes)

Decision: adopt **Navigation 3 (Nav3)** type-safe routing for the shared presentation layer,
instead of KMP-ifying the legacy `UiSerializer` + string-route apparatus. Nav3's back stack is
plain observable state (`List<NavKey>`) and routes are `@Serializable` `NavKey` objects carrying
**typed** arguments, so the whole legacy stack — `UiSerializer` (Base64 + `Class<M>` reflection +
Compose-type serializers), `generateComposableArguments`/`generateComposableNavigationLink`, and
the `Screen(name, "?arg={x}")` string contract — is **retired, not ported**.

**Prototype (done):** `navigation3-runtime` 1.1.0-alpha01 (iOS variants resolve) + kotlin-
serialization in `:shared`; `AppRoute` `@Serializable NavKey` routes in `commonMain` round-trip
via kotlinx-serialization on Android + iOS (`AppRouteTest`). Note: `navigation3-ui` (`NavDisplay`)
currently resolves only for `-android` in the cache — the iOS host needs its iOS variant confirmed.

**Current surface:** ~15 `Screen`s, 39 files import `androidx.navigation`, 24 serialize sites
(9 `fromBase64` + 15 `toBase64`), 9 `UiSerializable` configs (2 use Compose `Color`, 1 `TextAlign`,
1 `TextOverflow`, 1 `java.net.URI`), one `RouterHost` (`NavHost`).

Migration phases:

- **N1 — Foundation (done).** Nav3 runtime + prototype routes + serialization in `:shared`.
- **N2 — Route model.** Convert all ~15 `Screen`s to `@Serializable NavKey` routes in
  `commonMain`; **strip Compose-type/`URI` fields out of the configs** (UI styling doesn't belong
  in nav args) so configs become plain KMP-serializable data. This is what retires `UiSerializer`.
- **N3 — Host.** Replace `RouterHost` (`NavHost`/navigation-compose) with a Nav3 `NavDisplay` +
  `entryProvider` (Android first; iOS host once `navigation3-ui` iOS resolves). Back stack becomes
  `rememberNavBackStack()` state.
- **N4 — View-models.** VMs emit navigation as typed routes (`Effect.Navigation` carrying an
  `AppRoute`) instead of route strings; the host mutates the `NavKey` back stack. The 24 serialize
  sites collapse into typed route construction.
- **N5 — Deep links.** Map external deep-link URIs → typed routes; the Android intent/URI helpers
  (`Context`/`Intent`) stay native behind the host.
- **N6 — Retire legacy.** Delete `UiSerializer`, `generateComposableArguments`/
  `generateComposableNavigationLink`, the `Screen` string contract, and the `navigation-compose`
  dependency.

Risks: Nav3 is **alpha** (pin the version, expect API churn); `navigation3-ui` **iOS** availability
must be confirmed for the iOS host; and refactoring Compose-type fields out of the 9 configs touches
those configs + their screen consumers. Effort: multi-week (touches ~39 nav files + 24 serialize
sites + 9 configs + `RouterHost`), executed feature-by-feature behind the current system.

## 15b. Status — the iOS document layer exists (Phase 2, route (b), mdoc-only)

The Phase-2 row "Multipaz as explicit shared engine" is no longer theoretical: **iOS now has a real
`WalletEngine` written in Kotlin over multipaz's `DocumentStore`.** It answers `getAllDocuments()`,
`getAllDocumentsWithDetails(locale)` and `getMainPidDocument()`, plus bookmarks, and it was verified on
the simulator against a fixture mdoc it mints through multipaz itself (real Secure-Enclave keys, a real
MSO-certified `MdocCredential`).

This is **route (b)** — our own thin KMP layer on multipaz — taken for the document half only, and it
came out much smaller than §10's "3–5 mo" suggests, for a reason worth recording:
`eudi-lib-android-wallet-document-manager` has **zero `android.*` imports**. The `-android` in its name
is packaging. Of its ~2,000-LOC read path the substance is roughly 120 lines — `IssuedDocument`'s
credential filtering and selection — and its whole JVM crypto/CBOR stack (upokecenter, cose-java,
nimbus, bouncycastle) is confined to three *certification* files, i.e. issuance. So the read path
reimplements in the low hundreds of lines over already-multiplatform APIs.

**How it is split, and why.** The parts that make decisions about a document are platform-neutral and
live in `:shared-logic/commonMain` — issuer metadata + locale selection, the credential policy, the
usable-credential filter, and the `StoredDocument -> WalletDocument` projection — so they are covered by
`commonTest` running on **both** platforms and cannot silently diverge from the Android engine. Only the
multipaz-facing adapter is in `iosMain`. multipaz is declared as an **`iosMain`-only** dependency on
purpose: Android reaches the very same multipaz through wallet-core, so putting it in `commonMain` would
add a second version request to the app's classpath for no gain (verified: `:androidApp`'s
`devDebugRuntimeClasspath` is unchanged).

**Three limits, all deliberate:**

- **mdoc-only claims.** SD-JWT VC claim parsing needs `eudi-lib-jvm-sdjwt-kt`, which has no iOS
  artifact. Everything *else* about an SD-JWT document (name, format, credential counts, validity,
  issuer display) reads fine, so such a document still lists correctly — only its claims are absent.
- **Revocation: done on iOS, over multipaz.** Android's revoked-id list comes from a WorkManager job
  caching status-list results in Room. iOS now does the same work through
  `org.multipaz.revocation` — which implements the same IETF Token Status List draft in commonMain —
  caching into a multipaz storage table, with the *trigger* supplied by the host since there is no
  WorkManager. **Correction to an earlier claim in this document: `eudi-lib-kmp-statium` is NOT
  consumable from iOS.** It publishes only `androidJvm` and `jvm` variants; verified against its Gradle
  module metadata on Maven Central rather than the local cache, which holds only the Android artifact
  because that is all anything requested.
- **No issuance.** OpenID4VCI stays JVM-only, which is exactly the boundary the hybrid draws: the
  Swift `eudi-lib-ios-wallet-kit` is the intended provider behind the same `WalletEngine` seam.

**One upstream divergence to know about:** multipaz's iOS `SecureEnclaveSecureArea.getKeyInvalidated`
returns a hardcoded `false`. The usable-credential filter therefore cannot exclude invalidated
credentials on iOS, so counts can over-report and a document whose key is gone stays listed and fails at
*signing* time. Accepted rather than worked around — answering it honestly means probing the Secure
Enclave per credential, which can raise an authentication prompt and is far too expensive for rendering
a list. The code still calls `isInvalidated()`, so iOS inherits the correct behaviour as soon as
multipaz implements it.

**Worth an upstream ask:** a library with zero Android imports, four JVM-stdlib couplings and all its
JVM crypto in three issuance files is a strong candidate for EUDI to publish as KMP themselves. And a
second, cheaper ask while there: **`eudi-lib-kmp-statium` already has a KMP source layout but publishes
no Kotlin/Native targets** — adding the iOS ones looks like a build-configuration change, and it would
let both platforms share one status-list implementation instead of two.

## 16. References

- Android app: this repo (`core-logic`, `build-logic/convention`, `gradle/libs.versions.toml`; Gradle cache shows `org.multipaz` 0.99.0).
- iOS app: `github.com/eu-digital-identity-wallet/eudi-app-ios-wallet-ui`.
- Multipaz: `github.com/openwallet-foundation/multipaz` · `developer.multipaz.org` · OWF project page.
- EUDI-on-Multipaz: `eudi-lib-android-wallet-core` (README arch diagram; dependency on `org.multipaz`), `eudi-lib-android-iso18013-data-transfer`; EU AV backend `av-dc-api-backend` vendors Multipaz.
- EUDI iOS separate stack: `eudi-lib-ios-iso18013-data-transfer`, `eudi-lib-ios-wallet-kit`.
- EU-specific libs: `eudi-lib-android-rqes-core`, `eudi-lib-kmp-statium`, `eudi-lib-kmp-etsi-1196x2`; ARF `eudi.dev`.
- ZK: `github.com/google/longfellow-zk`; EU `av-lib-ios-longfellow-zkp`.
- KMP verifier precedent (stalled on iOS 18013-5): `eudi-app-multiplatform-verifier-ui`.
- KMP production precedents: JetBrains case studies (Bitkey/Block, Worldline, McDonald's, Cash App, Netflix, Philips).

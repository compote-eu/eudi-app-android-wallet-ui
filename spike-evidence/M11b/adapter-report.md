# M11b: real Services adapter probe (OR-5)

A probe that measures cost. It is not counted toward the verdict. Branch `spike/ux-runtime`; the run was unattended
and Claude Opus 5.5 was the agent.

## What was built

`InteractorPresCrossDeviceServices` implements the generated `PresCrossDeviceServices` and
`PresCrossDeviceResultDeclaration` (services contract `SERVICES_CONTRACT_VERSION` of the synced export). It sits on
the app's REAL interactor interfaces: `PresentationRequestInteractor`, `PresentationLoadingInteractor`,
`BiometricInteractor` and, through the loading interactor, `DeviceAuthenticationInteractor`. No interactor, wallet
logic, wallet-core or Multipaz file changed.

| Service | Real calls |
|---|---|
| `fetchRequest(uri)` | `setConfig(RequestUriConfig(OpenId4Vp(uri, DashboardRoute)), null)`, then the first event of `getRequestDocuments()`, mapped |
| `authenticate()` | `getBiometricUserSelection()`, `getBiometricsAvailability()`, `authenticateWithBiometrics(context, notify = true, listener)` |
| `verificationCode()` | none. The app has no source, so the adapter returns an explicit `Error`. It never makes up a code |
| `send(selection)` | `updateRequestedDocuments(card with the selection ticked)`, `loading.setScopeId(request scope)`, then `observeResponse()` in the order of the loading screen: `sendRequestedDocuments()` on `RequestReadyToBeSent`; `handleUserAuthentication(...)` per document on `UserAuthenticationRequired`; the first terminal state is the result |
| `cancel()` | `rejectPresentation()` (tells the verifier), `stopPresentation()`, then the Android Koin scope is closed |
| (no contract call) | `close()` (`AutoCloseable`), called by `UxFlowEntry` on flow exit: `stopPresentation()` and the scope is closed |

Files (spike only):
- `ux-spike/src/realAdapterMain/kotlin/eu/europa/ec/uxspike/real/`: `InteractorPresCrossDeviceServices.kt`,
  `FetchRequestAdapter.kt`, `AuthenticateAdapter.kt`, `SendAdapter.kt`, `AdapterGaps.kt`.
- `ux-spike/src/realAdapterTest/kotlin/eu/europa/ec/uxspike/real/`: `InteractorPresCrossDeviceServicesTest.kt` (37
  tests) and `FakeLoading.kt`.
- Wiring: `UxFlowEntry.kt` (`UxSpikeFlow.REAL_SERVICES = "real"`, `realServices` parameter, `close` on exit),
  `AndroidUxSpikeHook.kt` (Koin and `LocalContext` activity), `IosUxSpikeHook.kt` (Koin, null context),
  `ux-spike-android/build.gradle.kts` (+7) and `shared-ui/build.gradle.kts` (+13, T2, a pre-spike file already on the
  touch list).

**Placement (finding F-M11b-1).** The brief asks for `:ux-spike` commonMain. That cannot compile: the adapter needs the
interactor interfaces in `:shared-ui` commonMain, and `:shared-ui` iosMain depends on `:ux-spike` (Debug), so the
dependency would form a cycle. The adapter source therefore lives under `ux-spike/src/realAdapterMain`, and that
directory is compiled in three places:
- `:shared-ui` iosMain, only when `CONFIGURATION` is not `Release` (the same rule as `iosUxSpikeMain`);
- `:ux-spike-android`, which only `debugImplementation` pulls in;
- `:shared-ui` androidHostTest, the test source set only, so it never reaches the AAR.

The KC1 guard glob `ux-*/src/*Main/**` covers the directory. During this milestone it caught one comment that held a
state-name literal, and the comment was fixed.

Running the real mode:
- Android: `adb shell run-as $PKG sh -c 'echo real > files/ux-spike.on'`.
- iOS: `--ux-spike real`.

The switch redirects the same two routes as the fake modes. The real mode was not run against a verifier, because
that is out of scope.

## Cost (LOC)

Lines: total / code. Code lines exclude blank lines, comments and the licence header. All of it is hand-written; the
adapter has no generated LOC.

| Service | Main | Test (tests) |
|---|---|---|
| fetchRequest (`FetchRequestAdapter.kt` + 6 lines in the class) | 166 / 116 | 12 tests, about 120 lines |
| authenticate (`AuthenticateAdapter.kt` + 3) | 65 / 39 | 6 tests, about 45 lines |
| send (`SendAdapter.kt` + 12) | 152 / 110 | 12 tests, about 120 lines, plus `FakeLoading` 67 / 41 |
| cancel (+ `close`, the teardown) | 30 / 20 | 4 tests, about 45 lines |
| verificationCode | 6 / 4 | 1 test, 6 lines |
| conformance declaration + plumbing (class header, `guarded`, `AdapterGaps`) | 143 / 77 | 2 tests (`undeclared`, invoker), about 15 lines |
| **Total adapter** | **562 / 366** (5 files) | **468 / 362** (2 files, 37 tests × 2 targets = 74 runs) |
| Wiring (debug switch, both hooks, two build files) | +87 / −5 (4 spike files, 1 pre-spike build file: `shared-ui/build.gradle.kts`, T2) | 0 |

Compile breaks while writing: 2, both in build configuration. AGP 9 rejects `android.sourceSets["main"]` in a plain
library (a class-cast in the DSL); `androidComponents.onVariants { sources.kotlin.addStaticSourceDirectory }` fixed it.
The Kotlin code compiled the first time on both targets, and the tests passed the first time (37/37 on the host, 37/37
on the simulator).

Wall time: step 0 took about 2 min (18:05 to 18:07). The adapter took 18:07 to about 18:47 (about 40 min), including the reading (about
15 min), the code and tests (about 10 min), one full gate of about 20 min (regen-smoke 8 min) and the release build for the
isolation check (2 min 22 s). No rework loop.

## Results: mapped, lossy, impossible

The generated contract has 21 results across 5 services. **5 are mapped, 10 lossy, 6 impossible.** The adapter
declares the 15 it can produce. `PresCrossDeviceServiceResults.undeclared(adapter)` therefore returns exactly the 6
impossible results, and the test pins that set (`AdapterGaps.UNDECLARED`):

```
{authenticate=[Expired], fetchRequest=[DocInvalid, Expired], send=[Expired], verificationCode=[Ready, Expired]}
```

Mapped means a real, typed source maps one to one. Lossy means the result is produced, but the adapter derives it,
folds several sources into it, or drops information. Impossible means there is no real source. Every lossy mapping logs
a `UX-SPIKE: GAP …` line when it happens, and the tests assert the important log lines.

| Generated result | Real source | Status | What the app team would need to change |
|---|---|---|---|
| fetch `RequestReady` | `Success` with `claimsAreSelectable = false` and the RP fully verified | lossy | A per-claim requirement: `ClaimDomain.isRequired` is `false` on every path (Android `DocumentHelper`, iOS `IosPresentmentConsentMapping`), so the adapter derives the requirement for the whole request from `claimsAreSelectable`. Populate `isRequired` from the DCQL query |
| fetch `OptionalOnly` | `Success` with `claimsAreSelectable = true` and the RP fully verified | lossy | Same as the row above. Also, the app's card starts with every row ticked, while the contract's rule wants optional rows unticked. Product has to pick one |
| fetch `RpUntrusted` | `Success` with `!relyingParty.isFullyVerified` | lossy | The app shows an unverified RP without the badge, while the spec wants a warning plus explicit approval. With only optional rows the result breaks `RUL-pres-request-has-mandatory` (the conflict is logged). The app's own `VerifierNotTrusted` is a hard block that carries NO request, so it becomes `Error`. Decide whether an untrusted RP is blocked or warned, and expose the request in either case |
| fetch `DocMissing` | `NoData(relyingParty)` | lossy | `NoData` names no document. Add the missing document type or name |
| fetch `DocInvalid` | — (Android folds REVOKED documents into `NoData`; iOS has no revocation filter) | impossible | A typed "matching document is revoked or invalid" partial state |
| fetch `Expired` | — (an untyped `Failure(String)`) | impossible | Typed failures: `Failure(kind: Expired / Network / Protocol …, message)` from wallet-core and Multipaz errors |
| fetch `Error` | `Failure(error)` (also folded in: `VerifierNotTrusted`, `Disconnect`, no event, a throw) | mapped | — |
| authenticate `Authenticated` | `BiometricsAuthenticate.Success` | mapped | — |
| authenticate `Failed` | `Failed(msg)`, and `Cancelled` folded in | lossy | Nothing in the app. The contract has no Cancelled result |
| authenticate `NotEnrolled` | `BiometricsAvailability.NonEnrolled` | mapped | — |
| authenticate `Expired` | — | impossible | Not meaningful for a local prompt. The spec could drop it, or the app could report a request timeout here |
| authenticate `Error` | `Availability.Failure(msg)`, a PIN-only user (`getBiometricUserSelection() = false`), a throw | lossy | A PIN path: the contract has none, so the app's PIN users cannot pass this step |
| verificationCode `Ready` | — (the app has no verification-code step) | impossible | A code source (OpenID4VP has none; the spec's step is product-specific), or the spec drops the step for this app |
| verificationCode `Expired` | — | impossible | Same as the row above |
| verificationCode `Error` | the adapter's explicit "no source" | lossy (a stub) | Same as the row above. **With this adapter the flow can never reach `sending`** |
| send `Sent` | observe `Success`, and `Redirect(uri)` folded in (the redirect is dropped) | mapped | The contract's `Sent` has no `redirect_uri`, so the app's redirect-after-presentation is lost. The spec needs the field |
| send `Expired` | — (an untyped `Failure(String)`) | impossible | Typed failures (see fetch `Expired`) |
| send `Error` | `sendRequestedDocuments` `Failure`, observe `Failure`, `Rejected`, `IntentToSend`, a failed per-document key prompt, no host | mapped | — |
| cancel `Cancelled` | `rejectPresentation()` + `stopPresentation()` | lossy | Both return `Unit`, so `Cancelled` means "asked", not "confirmed". The interactor would have to return whether the rejection reached the verifier |
| cancel `NothingToCancel` | the adapter's own bookkeeping (no exchange, or one already finished) | lossy | The interactor would have to expose whether an exchange is live |
| cancel `Error` | a throw only | lossy | Same as the row above |

## Mismatches beyond the result table

Each item below is a real app state or obligation that has no place in the contract.

1. **Authentication order and coupling (G-AUTH-ORDER).** The spec has `authenticate` before `send`, using app-level
   biometrics. The real key-bound authentication happens inside the send: Android raises `UserAuthenticationRequired`
   once per document with a `BiometricCrypto`, and on iOS the Secure Enclave prompts itself. On Android the adapter
   therefore causes a second prompt. To fix it, the app could expose "pre-authorize the presentation keys", or the spec
   could move `authenticate` into `send`.
2. **Presentation scope lifecycle (G-SCOPE).** On Android the request and loading interactors share a Koin scope
   (`vp_presentation_scope_id`) that holds the exchange's controller. The adapter has to `setScopeId` on the loading
   interactor and close the scope at the end. The contract has no teardown call, so the adapter is `AutoCloseable` and
   the host closes it on exit. A real Services contract needs a `dispose` or `finish` call, or the app needs
   scope-free interactors.
3. **`PlatformContext` (G-CTX).** The Android biometric and key prompts need the host activity, which the hook passes
   from `LocalContext`. iOS passes null, which is correct there because the system owns the prompt. The contract is
   context-free, so every real adapter needs a host handle injected outside the contract.
4. **What the request card carries (G-LABEL, G-VALUE, G-COMB).**
   - `label_copy` is typed as a copy KEY, but the app has only resolved display titles. The runtime would draw
     `⟦Family name⟧` (`CopyResolver` marks a missing key). The spec needs a `label` string.
   - Image claims (portraits) have no text value.
   - Alternative combinations, several documents per combination, and `transactionData` (signature details the user
     must see) have no field: the adapter uses the first combination and the first document name, and drops the
     transaction data. **Dropping transaction data is a security gap** if the flow is ever used for signing or payment
     requests.
5. **Module placement (F-M11b-1, above).** A real adapter belongs with the app's interactors, not in a spike module.
   For the app team this is the natural home: `:shared-ui` (or a feature module) would own the adapter, and
   `:ux-contract` would be its dependency.

## Verification

- Unit tests:
  - `:shared-ui:testAndroidHostTest`: `InteractorPresCrossDeviceServicesTest` 37/37.
  - `:shared-ui:iosSimulatorArm64Test`: 37/37.
  - The tests reuse the app's own fakes `PresentationRequestViewModelTest.FakePresentationRequestInteractor` (with its
    `document` and `success` builders) and `FakeBiometricInteractor`, plus `testPlatformContext` and
    `testPlatformIntent`. A new `FakeLoading` was needed because the app's loading fake is private.
- Links into the apps (`logs/m11b-release-isolation.log`): the iOS Debug `EudiWallet.debug.dylib` and the extension's
  debug dylib carry the adapter's symbols (3 matching lines each). The Android dev debug APK has the
  `eu.europa.ec.uxspike.real` dex package (212 lines from `apkanalyzer dex packages`).
- Release isolation: the iOS Release `EudiWallet` and `.appex` have 0 matches. Android `assembleDevRelease` (throwaway
  keystore) has 0 spike dex packages, 0 spike files and no `UxSpikeInit` provider. `devReleaseRuntimeClasspath` has
  0 `project :ux-` lines.
- Gate (`logs/m11b-gate.log`): full `./spike-gate.sh`, including regen-smoke 9/9. **TOTAL 4444/0/0/0, GATE GREEN**:
  4364 + 6 (`LaunchIntentGuardTest` +3, dev and demo) + 74 (adapter, 37 × host and iOS).

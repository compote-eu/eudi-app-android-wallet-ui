# SPIKE-PLAN: the flow runtime and the typed seam (`spike/ux-runtime`)

Status: **approved by the agent review panel (RULING-1, 2026-10-16)**, with the review fold-in applied. No human
reviewed it before code (owner ruling R1). The owner approves at the end.

| | |
|---|---|
| Requirements | `~/code/dvm-edoklady-eudiw-analysis/ux/evaluation/kmp-ui-from-spec/spike-brief.md` (the brief) |
| Orchestration | the approved orchestration plan, rev 2 (owner rulings, verdict rules, debug isolation, milestones, gate) |
| Interface boundary (M1b) | [`spike-contract-boundary.md`](spike-contract-boundary.md) and [`spike-evidence/boundary/FLW-pres-cross-device.bundle.PROVISIONAL.json`](spike-evidence/boundary/FLW-pres-cross-device.bundle.PROVISIONAL.json) (sha256 `31bfda65…cf8d71`, `spec_hash` `provisional:sha256:849289…`) |
| Background only | `spike-plan.md`, `verdict.md` in the brief's folder (2026-10-16 study; the code wins where they disagree) |
| Worktree / branch | `~/code/eudi-kmp-spike`, `spike/ux-runtime`, from `origin/feature/kmp` @ `b23f6a1f5` |
| State files | `SPIKE-PLAN.md` (design), `spike-results.md` (one row per drill run), `SPIKE-LOG.md` (milestones, rulings, gate counts, SHAs), `HUMAN-QUEUE.md` (owner steps), `spike-evidence/` |

Path convention: paths are relative to the worktree root. `SUI` = `shared-ui/src`. `CM` = `SUI/commonMain/kotlin/eu/europa/ec`.
Line anchors were read at `b23f6a1f5`.

---

## §0 Owner rulings, deviations, verdict rules

### 0.1 Owner rulings for this run (2026-10-16) and the brief clauses they change

| # | Ruling | Brief clause it deviates from | Consequence here |
|---|---|---|---|
| R1 | No human review of `SPIKE-PLAN.md` before code. An agent panel replaces it (RULING-1); the owner approves at the end. | §10.1, §9 (plan review) | The touch list (§2) is approved by the panel. A ruling may widen it by **at most one file**, inside the 8-file cap; every widening is flagged in the final report. |
| R2 | iOS first; Android drills move to M11 but **stay part of the verdict**. | §7 drills are platform-symmetric | KC2 stays unresolved, and the verdict stays Incomplete, until the Android rows exist (0.5). |
| R3 | Build ahead of `ux-spec/`, bounded by the M1b boundary. | §4 (week-0 inputs first) | PROVISIONAL fixtures and a provisional contract copy (boundary §6); every provisional assertion re-runs against the real export in M6. |
| R4 | Worktree at `~/code/eudi-kmp-spike`; the other clone's `main` is untouched. | §2 | — |
| R5 | No push unless the owner says so. | §2 | `sonar.yml` runs on every push (`.github/workflows/sonar.yml:2-7`). |

Hard stops: a confirmed kill criterion (re-derived by an independent Opus agent first).

### 0.2 Facts that moved since the brief

| Fact | Effect |
|---|---|
| Week 0 has not started: no `tools/export_kmp.py`, no `product/services/`, no `UX_SPEC.lock` (boundary, "Week-0 state found"). | M6 is blocked; M1b's boundary goes to week 0 (Q-002). |
| REV-012 is taken (eudiw_v1 dark-mode review). Week 0 files its evidence record as **REV-013**. | Q-002 names REV-013. |
| eudiw_v1 now has dark modes (DEC-066/067). Dark mode is out of scope (brief §3). | The hosted flow forces `light` (`bundle.theme.default_mode`). |
| Xcode 27.1 is installed (`xcode-select -p` = `/Applications/Xcode.app/Contents/Developer`); Q-001 is done. The iOS simulator runtime is being installed. | M0 records the available simulator name; the gate's `iPhone 17` destination is replaced if needed (logged). |

### 0.3 Setup milestones (no drill)

M0 (bootstrap, toolchain, baseline gate), M1a (this plan and the state files) and M1b (the interface boundary) are
setup milestones and end with no brief §7 drill. Every later milestone ends with at least one §7 drill, possibly a
**preliminary** variant (§3).

### 0.4 Brief §5 decisions, adopted unchanged (as bundle data: boundary §5)

| Topic | Decision | Bundle |
|---|---|---|
| `request` | Back = cancel (declined). Restore = snapshot. | `back {policy: event, event: REJECT}`; `restore {policy: snapshot}` |
| `auth` | Back = return to `request`. Restore = `request`; authenticate again. | `back`/`restore {policy: to_history, candidates: [request…], fallback: request}` |
| `sending` | Back = blocked. Restore = `error`; `send` never re-called. | `back {policy: blocked}`; `restore {policy: to, target: error}`; `send.idempotent = false` |
| iOS cold start | Flow abandoned, Home opens; a deep link starts a new flow at `connecting`. | `process_death.ios = "abandon"`; `entries.deeplink` |
| Auth order | After consent (DEC-001). | spec order `request → auth → verification-code` |
| verification-code | 8th screen. | `verificationCode()` service (boundary Q4) |
| PIN fallback | Logged as a DEC; fake is biometric only. | `AuthenticateResult` has no PIN result |

### 0.5 Verdict rules

From the orchestration plan, extended by RULING-1:

- **Execution status** and **architectural verdict** are reported separately.
- Each kill criterion is **passed**, **failed** or **unresolved**, with its evidence rows: KC1 ← D8 ×4 + code review of
  the runtime; KC2 ← D1 and D2/D3 on **both** platforms; KC3 ← D7-iOS (VoiceOver, real iPhone; brief wording);
  KC4 ← D9 ×2; KC5 ← the app team's estimate.
- **Verdict preconditions** (RULING-1). Neither **Go** nor **Go with exceptions** is given unless all of these hold:
  **D4, D5 and D6 passed on both platforms**, and **D7-Android (TalkBack) passed**. If any is missing, the report is
  **"Incomplete: awaiting evidence"**.
- **Go** requires every criterion to pass.
- **Go with exceptions** only for the brief's named exceptions: a track-C (D10) **failure** that was run, and a missing
  KC5 estimate. **D10 not run** makes the verdict Incomplete, not an exception.
- If any of KC1–KC4 is unresolved, the report is **"Incomplete: awaiting evidence"**. It lists the missing rows and gives
  no verdict.
- An evidence row that rests on a weaker check than the one this plan names counts as **unresolved**. One example is a
  graph-only Android release check without an APK.
- **Fall back** requires a confirmed kill.
- Agents may not rule on brief §5, the kill criteria or these rules.

### 0.6 Refinements of the orchestration plan (accepted in RULING-1)

| # | Refinement | Why (evidence) |
|---|---|---|
| F1 | A 6th new module **`:ux-spike`** (KMP) holds the platform-neutral spike code: `UxFlowRoute`, the fake engine, the hand-written `PresRequestScreen`. | Otherwise both are written twice. It cannot live in `shared-ui` commonMain (ships in Release) and must not depend on `:shared-ui` (iOS cycle, §1.1). |
| F2 | Android registration uses a **plain `ContentProvider`** in `:ux-spike-android`'s manifest. It registers `ActivityLifecycleCallbacks`; `onActivityPreCreated` calls `loadKoinModules` **once** (run-once guard). | Content providers (incl. `androidx.startup.InitializationProvider`, `assembly-logic/src/main/AndroidManifest.xml:228-237`) run before `Application.onCreate`, where Koin starts (`assembly-logic/.../Application.kt:45-48` → `setupKoin()`, `AssemblyModule.kt:64`). |
| F3 | `--ux-spike` is parsed **in Kotlin** (`iosUxSpikeMain`) and persisted to the app-group defaults. `iOSApp.swift` is a reserved slot (P5). | That code is absent from Release by construction. |
| F4 | No `export(...)`: `UxDcApiSubflowViewController` lives in `shared-ui`'s `iosUxSpikeMain`, and its signature uses only `shared-logic`/stdlib types. | `shared-ui`'s own API is always in the SharedKit header (`ConsentHost.swift:20`); `export` is for dependencies (`shared-ui/build.gradle.kts:69`). `export()` stays reserved for Swift-visible API of other modules. |
| F5 | The common hook carries **no spike name**: `eu.europa.ec.shared.navigation.DebugFlowHook`. | The Release grep can then require 0 matches for every spike package, with no exemption. |

---

## §1 Design

### 1.1 Modules, packages, dependency direction

All modules are **new**. Each KMP module copies the plugin block of `shared-ui/build.gradle.kts:24-43`:
- `id("org.jetbrains.kotlin.multiplatform")` and `id("com.android.kotlin.multiplatform.library")`, both without a version;
- `alias(libs.plugins.jetbrains.compose)`, `alias(libs.plugins.compose.compiler)` and `alias(libs.plugins.kotlin.serialization)`.

Each also copies the `android { }` block of `:47-57`:
- `namespace = "eu.europa.ec.ux.<module>"`, `compileSdk = 37`, `minSdk = 29`, `withHostTest {}`;
- `compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }`;
- `androidResources { enable = true }`, in **`:ux-contract`** only, which carries compose resources.

The targets are `iosArm64` and `iosSimulatorArm64`. There is no convention plugin, no `LibraryModule` entry, and **no Koin**
in any `ux-*` module.

The tests use `runComposeUiTest`. The dependency is `"org.jetbrains.compose.ui:ui-test:${libs.versions.composeMultiplatform.get()}"`,
written in the new module's build file. The plugin's `compose.uiTest` accessor still exists in 1.12.2 but is deprecated
("Specify dependency directly", checked in `compose-gradle-plugin-1.12.2.jar`). This adds **no** `gradle/libs.versions.toml` touch.

| Module | Kind | Packages | Contents | Hand / generated |
|---|---|---|---|---|
| `:ux-theme` | KMP | `eu.europa.ec.ux.theme`, `…ux.theme.generated` | the token value types (shell); `UxTheme(brand, mode)`; `LocalUxTokens`. Never touches `ThemeManager`. | shell hand; `UxBrandTokens`, `UxBrandConfig`, `UxThemeSpec` from `ux-spec/theme/` (boundary §2.6) |
| `:ux-contract` | KMP | `eu.europa.ec.ux.contract.pres` (+ `Res`) | `PresCrossDeviceSpec`, `…State`, `…Event`, `…Services` (sealed results), `…Exit`, `…Invoker` (boundary §2); copy; bundle; `verifyUxSpec`. Until the sync: hand-copy in `ux-contract/provisional/kotlin/…`, selected by `file("ux-spec/UX_SPEC.lock").exists()` (boundary §6.3). | generated |
| `:ux-elements` | KMP | `eu.europa.ec.ux.elements`, `…elements.templates` | 9 elements and 4 templates; registry keyed by `EL-*`/`TPL-*` id; semantics from node `a11y` (boundary §1.7). | hand |
| `:ux-runtime` | KMP | `eu.europa.ec.ux.runtime.bundle`, `.engine`, `.screen`, `.host`, `.a11y` | loader + handshake; engine; interpreter; `FlowHost` + `FlowViewModel`; focus/announce (`expect`/`actual`). **No screen id, no app type.** | hand |
| `:ux-spike` (F1) | KMP | `eu.europa.ec.uxspike` | `UxFlowRoute : NavKey`; `FakePresCrossDeviceServices` (a generic player of `ux-spec/fixtures/scripts/*.json`, with call counters); `PresRequestScreen` + its registration. | hand |
| `:ux-spike-android` | Android library (`com.android.library`, AGP 9 built-in Kotlin) | `eu.europa.ec.uxspike.android` | `AndroidUxSpikeHook : DebugFlowHook`; Koin module (classic DSL); F2 provider; switch reader. | hand |
| `shared-ui` `iosUxSpikeMain` | srcDir, not Release | `eu.europa.ec.uxspike.ios` | `IosUxSpikeHook : DebugFlowHook`; switch reader; `UxDcApiSubflowViewController` (track C). | hand |

```
:ux-theme ◄── :ux-elements ◄── :ux-runtime
:ux-contract (serialization-json, compose resources; no ux-* dependency)
:ux-spike ──► :ux-runtime, :ux-elements, :ux-theme, :ux-contract (+ navigation3-runtime for NavKey)
shared-ui iosMain ──► :ux-spike        only when CONFIGURATION != "Release"   (iOS glue compiled inside shared-ui)
:ux-spike-android ──► :shared-ui, :ux-spike
:androidApp ── debugImplementation ──► :ux-spike-android
```

Nothing in `ux-*` depends on `:shared-ui` or `:shared-logic`. The KMP Android library has a single variant, so `shared-ui`
cannot hold Android debug-only code. On iOS, a separate module that depended on `:shared-ui` and was also a dependency of
`shared-ui` would be a cycle, so the glue compiles inside `shared-ui`. The two hooks are thin (≤ 100 lines each).

`:ux-runtime` takes the back handler as a parameter, `backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit`.
Each hook passes exactly one adapter onto `eu.europa.ec.uilogic.component.PlatformBackHandler` (`CM/uilogic/component/PlatformBackHandler.kt:34`).
Track C passes a no-op, because the extension has no `NavDisplay` (§4 R7).

The contract does not depend on the runtime. The hook passes `PresCrossDeviceInvoker(services)::invoke`, a
`suspend (String, JsonObject) -> JsonObject`, to the runtime (boundary §2.5).

### 1.2 How `ux-spec/` is consumed

Layout, lock and consumers are fixed in **boundary §3**. The files are vendored and committed, and never edited by hand
(brief §6.1). Each sync is one commit, `ux-sync <ux-sha>: <reason>`.

| File | Consumer |
|---|---|
| `contract/kotlin/**` | `:ux-contract` commonMain srcDir |
| `theme/kotlin/**` | `:ux-theme` commonMain srcDir |
| `resources/values/strings.xml` | `:ux-contract` `compose.resources { customDirectory("commonMain", …) }`. The `sk` copy is the default locale (boundary Q7), so drills need no device locale. |
| `resources/files/FLW-pres-cross-device.bundle.json` | `Res.readBytes(PresCrossDeviceSpec.BUNDLE_PATH)` |
| `fixtures/scripts/*.json` | the fake engine and the tests (boundary Q14) |

- **`:ux-contract:verifyUxSpec`** checks every file's sha256 against `UX_SPEC.lock`. It fails on a mismatch, a missing file,
  an unlisted file, a lock `spec_hash` that differs from the contract and theme literals, or `ux_dirty` outside `spike/d8-*`
  (boundary §3, L1–L3). It is configuration-cache safe (the repo sets `org.gradle.configuration-cache=true`), wired into
  `check`, and named in the gate.
- **Spec-hash handshake**: `FlowBundle.load(bytes, expectedFlowId, expectedSpecHash, themeSpecHash, allowProvisional)` returns
  `Loaded` or `Refused(reason)`. It covers cases H1–H12 (boundary §4), and those are commonTest cases in M3. A `Refused`
  result builds no engine and calls no service. The production host always passes `allowProvisional = false`.
- **Lint**: detekt runs from the root over every `**/*.kt` and `**/*.kts` file, except `build/` (`CodeQualityConventionPlugin.kt`,
  `setSource(files(rootProject.projectDir))`). There is no baseline. So committed `ux-spec/**/*.kt` and every new build
  file are linted. ktlint 1.8.0 runs on `allprojects` (ledger `.editorconfig:39-112`). The exporter's constraints are in
  boundary §2. A finding in generated code goes back to week 0. It is **never** fixed by hand, and never silenced by
  editing `detekt.yml` or `.editorconfig`.
- No scratch directory, worktree or unpacked export may sit under the repo root, because detekt would scan it. D8 scratch
  goes to `$TMPDIR`.
- **Brand selection** lives in `ux-spec/` only:
  - `bundle.theme.default_brand` / `default_mode` (boundary §1.9);
  - the per-brand `UxBrandConfig` (`styles`, `layouts`, profile) and the `UxBrandTokens` in `ux-spec/theme/`.

  It reaches the runtime like this: `FlowHost` reads `bundle.theme.default_brand`, looks up the generated `UxBrandConfig`
  and tokens for it, and applies `UxTheme(tokens, config, mode = light)`. The interpreter passes the layout selection
  (`TPL-sheet {decision-steps: sheet|page}`) to the template. No Kotlin outside `ux-spec/` names a brand.

### 1.3 The flow engine (`eu.europa.ec.ux.runtime.engine`, pure Kotlin)

The semantics are boundary §1.4–§1.6, adopted as written:
- `on` is an ordered branch list, assign-then-guard, with internal transitions (`target: null`);
- guards use the `rule.schema.json` condition language only;
- the assign ops are `set`, `merge` and `toggle_item`; `derive` supports `count_where` and `ids_where`.

| Concept | Rule |
|---|---|
| State-scoped jobs | Each `invoke` with `scope: state` runs in a child `Job` of the state entry, which exiting the state cancels. `detached` (`cancel()` in `cancelled`) may finish, and its results are ignored. |
| Generation tokens | Each state entry sets `gen += 1`. Results and UI events carry their `gen`; a mismatch is dropped and logged. |
| Idempotent events | A UI event that changes state is accepted at most once per `gen`. A double tap gives one `CONTINUE` and one `send`. |
| Pending invokes | While an invoke is pending, only events in `accepts_while_pending` are accepted. Nodes that emit a non-accepted event are drawn disabled (boundary §1.6). |
| Back | `event`, `to`, `to_history`, `blocked` (handler enabled, press consumed), `pass` (handler disabled, Nav3 pops). |
| Restore | `snapshot`, `to`, `to_history`. The snapshot is `{bundle_schema_version, spec_hash, state, context, history, gen}`. A non-idempotent invoke is never re-run; an idempotent one re-runs with a new `gen`. On iOS the snapshot is ignored (`process_death.ios = abandon`). |
| Entries | `default → scan`; `deeplink → connecting` with `requires: [unlocked]`. The host passes `unlocked = true`, because the Dashboard is the gate. |
| Exit | Structured; `PresCrossDeviceExit.of(finalState, errorClass)` → the hook maps it to `AppNavigator`. |

### 1.4 The screen interpreter (`eu.europa.ec.ux.runtime.screen`)

- **Traversal** (boundary §1.7): screen → `template` (`TPL-*`) → `regions` in order → nodes → `slots`. Every node goes through
  the **generic element registry**, keyed by `EL-*` id. The runtime knows no `SCR-*` id.
- **Custom screens**: the host passes `customScreens: Map<String, @Composable (ScreenScope) -> Unit>`. Only
  `PresRequestScreen.kt` in `:ux-spike` names `SCR-pres-request`. An unknown element gets a visible placeholder that the
  tests assert.
- **Bindings**: prop values are literals, `{copy, args}` or `{bind: path}`. Elements receive whole data objects, so a new
  field (`/verifier/logo_url`) reaches `EL-rp-identity` without any screen code.
- **Copy**: `Res.allStringResources[res]`, with the brand override (`__<brand>`) first (boundary §1.8).
- **Rule effects**: `ui_state_when` picks the first match. The effects `hide`, `show`, `enable`, `disable` and `set_copy`
  apply. `allow_transition` and `message` are ignored on screens (boundary Q12). Any other effect is logged and counted for M6.
- **Events**: an element action emits `(event, payload, gen)` to the engine. Consent sends intent only (brief §6.6). The
  runtime builds the consent snapshot from `/verifier/*` and `/request/*`.
- **Focus and announcements** (`.a11y`, from the screen's `a11y`):
  - after each state change settles (`AnimatedContent` done), `on_enter: focus-heading` focuses `a11y.heading`;
  - `announce-live` uses the polite live region, which is how `sending` is announced once;
  - the TPL-sheet `appbar` is inert;
  - the iOS `actual` posts `UIAccessibilityScreenChangedNotification`.

  D7 fixes go only here or in `:ux-elements` (KC3).

### 1.5 Hosting in Navigation 3

| Item | Design | Evidence |
|---|---|---|
| Route | `@Serializable @SerialName("UxFlow") data class UxFlowRoute(flowId, entry, launch: String?) : NavKey` in `:ux-spike`. | `AppRoute` is sealed (`CM/shared/navigation/AppRoute.kt:51-52`); `AppRouteAnalytics.kt:27` uses an exhaustive `when`. Neither is touched. |
| Analytics | Not reported, and that is accepted: `AppNavDisplay` reports `as? AppRoute` only. | `AppNavDisplay.kt:83-88` |
| Entry | One `entry<UxFlowRoute>` from `DebugFlowHook.entries(navigator)`, called from `sharedAppEntries`. | `SharedEntries.kt:73` |
| Inside the entry | Flow states are not Nav3 entries. `FlowHost` renders them with `AnimatedContent`. | — |
| Back | One `backHandler(enabled = policy != pass) { engine.back() }` call, in `FlowHost`. `PresRequestScreen` must not use `ContentScreen`, which has its own handler (`ContentScreen.kt:236`). | D1 grep (§3) |
| Saved state | `FlowViewModel(createSavedStateHandle())` through lifecycle `viewModel { }`. The snapshot is stored as JSON in the `SavedStateHandle`. The entry owner comes from `rememberViewModelStoreNavEntryDecorator` (`AppNavDisplay.kt:96-101`). | probe P4 |
| Android restore | `rememberNavBackStack(SplashRoute)` (`ui-logic/.../navigation/RouterHost.kt:80`). | probe P6 (M11) |
| iOS restore | None across process death (`IosNavHost.kt:30-34, 81`), which is brief §5. | D3 |
| Exits | `done`/`closed` → `navigator.popUpTo(DashboardRoute::class)`; `add-document` → `AddDocumentRoute`; `device-settings` → platform settings; `to-help` → placeholder plus a logged gap. | `AppNavigator.kt:77-107` |

**Redirect site (probe P1).** `redirect(route: NavKey): NavKey?` returns a `UxFlowRoute` when the switch is on, and
`null` otherwise.

| Option | Where | Returns non-null for | Cost |
|---|---|---|---|
| **A (default)** | `entry<PresentationRequestRoute>` (`SharedEntries.kt:227-237`) and `entry<QrScanRoute>` (`:83-85`). When the result is non-null, the entry calls `LaunchedEffect { navigator.replaceCurrent(it) }` and renders nothing, so the real `koinViewModel` is never created. | `PresentationMode.OpenId4Vp` only (null for `DcApi` and `Ble`); `QrScanFlow.Presentation` only (null for `Issuance` and `Signature`, `CM/commonfeature/config/QrScanUiConfig.kt`) | 0 extra files |
| B (fallback) | `DashboardViewModel.handleDeepLink`, `OPENID4VP` branch (`CM/dashboardfeature/ui/dashboard/DashboardViewModel.kt:283-291`), plus the A call in `QrScanRoute`. | the same | +1 file (T7) |

P1 runs on the simulator. It passes when all of these hold in D4: the real request screen never shows a frame,
`PresentationRequestViewModel` is never constructed (log), and Back from the flow follows the policy. The redirect
handles navigation only: it registers no back handler and never calls `pop`.

### 1.6 The fake engine in place of the presentation interactors

- The three interfaces stay **untouched**, and so do their Android and iOS implementations:
  `PresentationRequestInteractor`, `PresentationLoadingInteractor` and `PresentationSuccessInteractor`
  (`CM/presentationfeature/interactor/*.kt`; `presentation-feature/.../interactor/*Impl.kt`;
  `SUI/iosMain/.../shared/ui/di/IosPresentationInteractors.kt`).
- When the switch is on, the redirect runs before `PresentationRequestViewModel` exists. When it is off, nothing changes.
- `FakePresCrossDeviceServices : PresCrossDeviceServices` replays a script from `ux-spec/fixtures/scripts/<name>.json`.
  - The scripts are `happy`, `expired-at-request`, `expired-at-sending`, `slow`, `untrusted` and `doc-missing`.
  - Each step returns a result, decoded with the generated serializers, after an optional delay.
  - There are call counters per method, logged as `UX-SPIKE: send#1`.
  - Before the sync, PROVISIONAL scripts sit in commonTest (boundary §6.2).
- The fake also supplies what the app cannot: `verificationCode()` and `Expired` (§6).
- **D8 rule (RULING-1).** Any D8 diff outside `ux-spec/**` is **failed** for KC1, unless the owner ruled on that exact
  case in advance. The data-driven scripts are there to prevent it. If the generated `Services` interface grows a method,
  the fake breaks at compile time, and that is a D8 failure unless the owner has ruled otherwise (asked in Q-002).

### 1.7 The debug-only switch

**Common hook.** One new file, `CM/shared/navigation/DebugFlowHook.kt` (F5), compiled in every build. It references no
spike type:

```kotlin
interface DebugFlowHook {
    fun EntryProviderScope<NavKey>.entries(navigator: AppNavigator)
    fun redirect(route: NavKey): NavKey?
}
internal fun debugFlowHookOrNull(): DebugFlowHook? = KoinPlatform.getKoinOrNull()?.getOrNull<DebugFlowHook>()
```

`SharedEntries.kt` calls `debugFlowHookOrNull()?.run { entries(navigator) }` once, and calls `redirect` at the redirect
site. With no hook, both are no-ops.

The test lives in `shared-ui` commonTest (a new file):
- start Koin **without** the hook module and assert that `debugFlowHookOrNull()` is null;
- start it **with** a test module that binds a stub `DebugFlowHook` and assert that it is non-null.

**iOS**

- `shared-ui/build.gradle.kts` uses the **same** condition as the probes (`:237`,
  `providers.environmentVariable("CONFIGURATION").orNull != "Release"`). Under it, it adds `kotlin.srcDir("src/iosUxSpikeMain/kotlin")`
  and `iosMain.dependencies { implementation(project(":ux-spike")) }`.
- Koin registration is decided by probe P2:

  | Option | How | Touch |
  |---|---|---|
  | **A (default)** | `@Single(binds = [DebugFlowHook::class]) class IosUxSpikeHook` in `iosUxSpikeMain`, with a constructor that has no dependencies. It is picked up by `@ComponentScan("eu.europa.ec")`, because "the scan runs per compilation" (`IosAppRoot.kt:143-145`; `SharedUiModule.kt:41-44`). The app (`IosAppRoot.kt:141-147`) and the extension (`DcApiConsentViewController.kt:475-478`) both load it. | 0 |
  | B (fallback) | `IosAppRoot.startKoinIfNeeded` adds `uxDebugModules()`. A Release stub srcDir, `src/iosUxSpikeStub/kotlin`, is added in the guard's `else` branch, because `IosAppRoot.kt` compiles in Release. | +1 (T6) |

- Activation (F3): `IosUxSpikeHook` reads `--ux-spike <script|off>` from `NSProcessInfo.processInfo.arguments` and persists
  it to the app-group `NSUserDefaults` (`IosAppGroup`; `project.yml:140`). The value survives a `simctl openurl` cold start
  (D4) and is visible to the extension. There is no other activation path. If P5 fails, the parse moves to a `#if DEBUG`
  block in `iOSApp.swift` `init()` (`:294-300`, next to the probes).
- Track C (T5): in `ConsentHost.swift`, both the import (`import class SharedKit.UxDcApiSubflowViewControllerKt`) and the
  branch in `makeUIViewController` (`:39-47`) sit **inside `#if DEBUG`**. The branch returns
  `UxDcApiSubflowViewController(request:onDecision:)` when the app-group flag is set.

**Android**

- `:ux-spike-android` depends on `:shared-ui` and `:ux-spike`. It holds:
  - `AndroidUxSpikeHook`;
  - `module { single<DebugFlowHook> { AndroidUxSpikeHook() } }`;
  - the F2 `ContentProvider` in its own `AndroidManifest.xml`, merged into debug variants only.
- The provider's run-once guard is an `AtomicBoolean` together with `KoinPlatform.getKoinOrNull() != null`. After the first
  successful `loadKoinModules`, the callbacks unregister themselves.
- `androidApp/build.gradle.kts` (`:87-90`) gets `debugImplementation(project(":ux-spike-android"))`.
- Activation: `adb shell run-as $PKG sh -c 'echo happy > files/ux-spike.on'`. With no file, the switch is off.

**Release-isolation check** (M2, at every ⟲, M12). Every item must be **0 or absent**; the patterns are
`eu.europa.ec.ux.`, `uxspike`, `UxSpike`, `FakePresCrossDevice`, `UxFlowRoute`, `ux-theme|ux-contract|ux-runtime|ux-elements|ux-spike`.
No exemption: `DebugFlowHook` matches none of them.

| Check | Command (sketch) |
|---|---|
| iOS Gradle graph | `CONFIGURATION=Release ./gradlew :shared-ui:dependencies` (iOS compile/klib configurations), grep `ux-` |
| iOS symbols | a Release simulator build (`-configuration Release CODE_SIGNING_ALLOWED=NO`), then `nm -gU` and `strings` on `EudiWallet.app/EudiWallet` and on the `.appex` binary |
| iOS resources | `find EudiWallet.app -path '*compose-resources*'`, including the `.appex` |
| Android graph | `./gradlew :androidApp:dependencies --configuration devReleaseRuntimeClasspath` and `--configuration demoReleaseRuntimeClasspath` |
| Android APK (**required**) | `./gradlew :androidApp:assembleDevRelease` signed with a throwaway keystore through `-Pandroid.injected.signing.*`, or left unsigned; no build-file change. Then `apkanalyzer dex packages` and `apkanalyzer files list` (assets, `composeResources`). A graph-only result counts as **unresolved** (0.5). |
| Behaviour off | a Debug build without the flag: `redirect` returns null, and a screenshot matches `spike-evidence/M0-baseline-launch.png` |

---

## §2 Existing files touched

The cap is ≤ 8. Planned: **5**. Reserved, used only if a probe fails: **3**. Every other file is new. A ruling may widen
the list by at most one file and must stay inside the cap.

| # | File | Why | Expected change | Status |
|---|---|---|---|---|
| T1 | `settings.gradle.kts` | include the 6 new modules | +6 lines | planned (M2) |
| T2 | `shared-ui/build.gradle.kts` | Debug-only iOS srcDir and `implementation(project(":ux-spike"))`, under the existing `CONFIGURATION != "Release"` rule (`:230-251`) | +8 to +12 | planned (M2) |
| T3 | `shared-ui/src/commonMain/kotlin/eu/europa/ec/shared/navigation/SharedEntries.kt` | `debugFlowHookOrNull()?.run { entries(navigator) }`; redirect option A in two entries | +10 to +16 | planned (M2) |
| T4 | `androidApp/build.gradle.kts` | `debugImplementation(project(":ux-spike-android"))` | +1 | planned (M2) |
| T5 | `iosApp/DocumentProvider/ConsentHost.swift` | track C, import and branch inside `#if DEBUG` | +10 to +14 | planned (M10) |
| T6 | `shared-ui/src/iosMain/kotlin/eu/europa/ec/shared/ui/IosAppRoot.kt` | Koin option B | +1 | reserved (P2) |
| T7 | `shared-ui/src/commonMain/kotlin/eu/europa/ec/dashboardfeature/ui/dashboard/DashboardViewModel.kt` | redirect option B | +3 to +5 | reserved (P1) |
| T8 | `iosApp/iosApp/iOSApp.swift` | `#if DEBUG` parse of `--ux-spike` | +6 to +8 | reserved (P5) |

**New locations** (not touches):
- the modules `ux-theme/`, `ux-contract/` (incl. `provisional/`), `ux-elements/`, `ux-runtime/`, `ux-spike/` and `ux-spike-android/`;
- `ux-spec/`;
- the new `shared-ui` sources `src/iosUxSpikeMain/`, `src/iosUxSpikeStub/` (only with option B), `CM/shared/navigation/DebugFlowHook.kt` and its commonTest;
- `.maestro/ux-spike/`, `spike-evidence/`, the state files and `spike-contract-boundary.md`.

If the M2 resource probe fails, the fallback is new files under `SUI/commonMain/composeResources/files/ux/` and `values/ux_strings.xml`.

**Never touched:**
- navigation and app wiring: `AppRoute.kt`, `AppRouteAnalytics.kt`, `AppNavDisplay.kt`, `AppNavigator.kt`, `IosNavHost.kt`,
  `RouterHost.kt`, `MainActivity.kt`, `AssemblyModule.kt`, `Application.kt`, every existing `AndroidManifest.xml`,
  `SharedUiModule.kt`, `DcApiConsentViewController.kt`, `PlatformBackHandler*.kt`;
- the theme (`CM/resourceslogic/theme/**`) and every `composeResources/values*/strings.xml`;
- the presentation interactors, view models and screens;
- build and CI configuration: `iosApp/project.yml`, `gradle/libs.versions.toml`, `detekt.yml`, `.editorconfig`,
  `build-logic/**`, `.github/workflows/**`, `local.properties`.

**Check at every gate (Haiku):** `git diff --name-only --diff-filter=MDRT origin/feature/kmp...spike/ux-runtime` must list
only T1–T8. Any other path is a failure and goes to the orchestrator.

---

## §3 Milestones

Legend:
- ★ = a human is needed.
- ⟲ = rebase onto `origin/feature/kmp`. First create the backup branch `backup/spike-ux-runtime-pre-rebase-<date>`. After
  the rebase, run the full gate and the release-isolation check.
- **Bold** drills count toward the verdict.
- *preliminary* drills are recorded in `spike-results.md` and never count.

**Gate after every code commit** (orchestration plan; `wiki/BRANCHES.md` §4 plus the new modules):
- The full command set, plus `:ux-spike:allTests :ux-contract:verifyUxSpec`.
- Counts come from `*/build/test-results/**/*.xml`. A task that executes 0 tests where tests exist is red.
- M2 confirms that `allTests` runs both `testAndroidHostTest` and `iosSimulatorArm64Test`. If it does not, both are named.
- The baseline is `spike-evidence/M0-baseline.md`: SHA `b23f6a1f5`, no named failing tests, so no exemptions.

| M | Goal | Done when | Closing drill(s) | Marks |
|---|---|---|---|---|
| M0 | setup: toolchain, baseline gate, `M0-baseline-launch.png` | baseline recorded | none (setup) | ★ Xcode (done, Q-001) |
| M1a | setup: this plan + state files | RULING-1, commit | none (setup) | — |
| M1b | setup: interface boundary | committed; Q-002 queued | none (setup) | ★ forward to week 0 |
| M2 | six module skeletons; `DebugFlowHook`; conditional iOS/Android wiring; a placeholder `UxFlowRoute` screen; probes P1 (redirect), P2 (Koin), P3 (compose resources from `:ux-contract` reach the app **and** the extension), P5 (args) | gate green; release check passes (iOS full, Android graph + APK); probes logged | *D4-iOS-preliminary*: `simctl openurl openid4vp://…` cold start → unlock → placeholder flow entry; switch off → baseline screenshot | ⟲ at end |
| M3 | engine + loader + handshake (H1–H12) on the PROVISIONAL bundle | the 6 M3 tests of the orchestration plan + H1–H12 green, host + iOS sim | *D1-engine*, *D2-engine*, *D5-engine* (the Kotlin engine over the L58 pre-fix vs provisional-fix fixture: fails, then passes) | — |
| M4 | `FlowHost` + `FlowViewModel` (P4), back by policy, fake engine player, provisional contract copy | `SavedStateHandle` round trip green; switch off = unchanged | *D1-iOS-preliminary* (swipe in `request`, `auth`, `sending` on placeholders), *D3-sim-preliminary* | — |
| M5 | 9 elements + 4 templates; semantics per node `a11y` | template semantics tests (`runComposeUiTest`) green; Opus a11y review | *D7-semantics-preliminary* (heading/live/inert assertions per template, Accessibility Inspector on a template gallery) | — |
| M5b | interpreter (§1.4); `PresRequestScreen` against the provisional contract | bundle-only render test, added-state test, KC1 guard green | *D6-iOS-provisional* (simulator, happy + expired, provisional bundle), *D8-added-state-preliminary* | — |
| M5c | styling hardening (OR-2): `style-guard` gate step (no hard-coded dp/sp/Color/alpha/FontWeight in `:ux-elements` main, templates included), `wireEnum` and unknown variants/props fail loudly, registry-vs-library-YAML test, `UxTokens` shell grown (elevation drawn, font family and weight, `comp.*`, modes incl. HighContrast), exporter requirements for week-0 batch 3 | gate green incl. `style-guard`; a planted hard-coded value turns it red (proved once); the YAML test is green or its mismatches are listed; `spike-evidence/M5c/exporter-requirements.md` written; metrics row | none (hardening; not counted toward the verdict) | — |
| M5d | Home-screen probe (OR-1): render only the dashboard Home screen from the bundle through the interpreter (no dashboard flow), after M5c | the Home screen is drawn from bundle data by the generic interpreter, gaps listed | *probe*, not counted toward the verdict | — |
| M6 | wait for `ux-spec/` + lock; sync; lock + handshake; lint report; re-check per boundary §6 (shape diff, API diff, re-run, classify, delete provisional); bind the fake to the generated `Services` | divergence list in `SPIKE-LOG.md`; `git grep PROVISIONAL` clean | **D6-iOS**, **D1-iOS**, **D3-sim**, **D4-iOS**, **D5** | ★ `ux-spec/`; ⟲ after sync |
| M7 | accessibility fixes in runtime/elements only; per-transition semantics assertions; Inspector audit | Inspector clean; walk queued | **D7-iOS** (VoiceOver, real iPhone) | ★ device; ⟲ at end (it is the M8 baseline) |
| M8 | regeneration drills, all from **one named baseline SHA** (logged in `SPIKE-LOG.md` when cut, right after the M7 rebase); no rebase during M8 | 4 rows (§5 KC1) | **D8-logo**, **D8-offline**, **D8-brand**, **D8-copy** | ★ only if §1.6 case arises |
| M9 | track B inside the TPL-sheet implementation | numstat per option per platform | **D9-brand**, **D9-sheet-page** | — |
| M10 | track C: `UxDcApiSubflowViewController`, T5; in-app harness on the simulator (a scripted request, as in the `DcApiProbeRequest` builder, `shared-logic/.../multipaz/harness/DcApiHarness.kt`). The harness is evidence only, **not** a D10 pass. HTTPS `org-iso-mdoc` page written. | harness completes; steps queued | **D10 run 1–5** (device) | ★ iPhone iOS 26.2+, signing, hosting; ⟲ at end (before M11) |
| M11 | Android drills (emulator/device); **runs at the end of the spike (OR-4)**, after everything is confirmed on iPhone | rows recorded | **D1-Android**, **D2-Android** (`am kill`, ★ LMK), **D4-Android**, **D6-Android**, **D7-Android** (TalkBack ★) | ⟲ at start; ★ device |
| M11b | real adapter probe (OR-5), after M11 and before M12: one real `Services` adapter binds this flow's request step (and, as far as cheap, auth and send) to the app's real presentation interactors, behind the debug switch | cost measured (LOC, files, compile breaks per OR-3), typed-error gaps listed (untyped `Failure` to `Expired` etc.); metrics row | *probe*, not counted toward the verdict; feeds the estimates | — |
| M12 | close-out: gate, release check, independent KC re-derivation, DEC draft, copy-back, final report, **and a per-phase cost model for a new flow (OR-6)** built from the `SPIKE-LOG.md` "Phase metrics" table (spec / exporter / runtime / elements+templates / hand-written screens / services adapter / drills+a11y / sync: wall time, hand-written LOC, generated LOC, files touched, gate runs, rework) as input to the post-spike challenge of estimates | report delivered | all rows final | ⟲ final; ★ approval, KC5 |

**Phase metrics (OR-6), part of every milestone's definition of done.** Each milestone adds one row to the "Phase metrics"
table in `SPIKE-LOG.md`: phase category (spec / exporter / runtime / elements+templates / hand-written screens / services
adapter / drills+a11y / sync), agent wall time, hand-written LOC (main and test, per module), generated LOC, existing files
touched, gate runs, rework loops with their cause. A milestone without its row is not done.

**Order after the owner rulings of 2026-10-16** (OR-1, OR-4, OR-5): M5b, M5c, M5d (probe), M6, M7, M8, M9, M10, M11
(Android, at the end), M11b (probe), M12.

**Drill specifics** (each command goes into its result row):

- **D1** — Back in the three hard states.
  - iOS: an edge swipe (Maestro `swipe: {start: "1%,50%", end: "70%,50%"}`) in `request`, `auth` and `sending`.
  - Android: `KEYCODE_BACK` and a predictive-back swipe.
  - Pass:
    - the resulting state matches §0.4;
    - `git grep -n -E '\bbackHandler\(' -- ux-runtime` finds exactly one call, in `FlowHost`;
    - each hook (`shared-ui/src/iosUxSpikeMain`, `ux-spike-android`) has exactly one `PlatformBackHandler` adapter;
    - `git grep -n -E 'BackHandler\(|NavigationEventHandler|NavigationBackHandler|onBack *=' -- ux-* shared-ui/src/iosUxSpikeMain`
      finds nothing beyond those;
    - the redirect site in `SharedEntries.kt` contains no back handling.
- **D2** (Android) — per hard state, using the `slow` script for `sending`:
  - `KEYCODE_HOME`, then `am kill $PKG`; `pidof` must print nothing; relaunch from Recents;
  - ★ a real low-memory kill (2 GB AVD or a device, background-process limit, `logcat -b events | grep am_kill`);
  - pass: the restore matches §0.4, and the `send` counter is unchanged.
- **D3** — `xcrun simctl terminate booted $PKG` in each hard state, then relaunch. Pass: unlock → Home, no flow. The
  device repeat is ★ (Q-003).
- **D4** — the cold start `simctl openurl booted "openid4vp://?client_id=spike&request_uri=https://example.invalid/r"`
  (the schemes are at `project.yml:313-316`); on Android, `am force-stop` and `am start -W -a android.intent.action.VIEW -d …`.
  Pass: unlock gate → `connecting`, and the link is consumed once.
- **D5** — the week-0 model check, read-only, on the pre-fix (L58) and the fixed spec. The Kotlin engine runs over the same
  sequences on the host (Android) and iOS test targets.
- **D6** — Maestro and simctl/adb with video:
  - happy path: `scan → connecting → request → auth → verification-code → sending → success`;
  - expired path: `… → error-expired`;
  - every screen except `SCR-pres-request` is drawn from the bundle (interpreter log plus testTags).
- **D7** — the owner walks `request → success` with swipe and double-tap only, with a recording. Pass, after each state change:
  - focus is on the new heading;
  - `sending` is announced once, and `success` is announced;
  - no focus remains on a node of the old state.
- **Pre-D8 baseline grep** (on the D8 baseline SHA, recorded in `spike-evidence/D8/README`):
  `git grep -n -i -E 'logo_url|logoUrl|idsk' -- ':!ux-spec' ':!*.md' ':!spike-evidence'` must return 0. This shows that no
  hand-written code already handles the field or the brand. The guard below must also return 0.
- **KC1 guard grep** (M5b, every gate after it): no `SCR-` literal and no screen name in any spike module or hook:
  `git grep -n -E 'SCR-|"pres-' -- ux-theme ux-elements ux-runtime ux-spike ux-spike-android shared-ui/src/iosUxSpikeMain shared-ui/src/commonMain/kotlin/eu/europa/ec/shared/navigation/DebugFlowHook.kt ':!ux-spike/**/PresRequestScreen.kt'`
  must return 0. `PresRequestScreen.kt` is the only exception, and it also holds that screen's registration.
- **D8/D9/D10** — see §5.

---

## §4 Risks

| # | Risk | Likelihood / effect | Mitigation |
|---|---|---|---|
| R1 | **Xcode/toolchain.** Xcode 27.1 is now installed, but the iOS simulator runtime is still being installed, and the gate names `iPhone 17`. The M0 iOS tests failed with `MissingXcodeException` (`spike-evidence/M0-baseline.md`). | medium / iOS gate | M0 re-runs the iOS gate rows once the runtime exists. The destination name is logged and substituted if needed. |
| R2 | **`ux-spec/` delay and boundary divergence.** Week 0 has not started. | high / M6+ blocked | The boundary was forwarded (Q-002). The provisional contract and fixtures follow boundary §6. M6 runs the shape diff, the API diff and the re-run. `bundle_schema_version` is strict. |
| R3 | **Exporter Kotlin fails detekt/ktlint.** | medium / gate red at sync | The constraints are in boundary §2. Findings are reported to week 0 and never fixed by hand. |
| R4 | **Compose resources across modules on iOS and in the extension.** | medium / copy and bundle missing | Probe P3 in M2. The fallback, by ruling, is new files in `shared-ui` composeResources. |
| R5 | **Koin compiler plugin scanning iosMain.** The Debug and Release graphs differ, and compile-time checks may reject a definition. | medium | The hook's constructor has no dependencies. Probe P2, with option B (T6 plus a stub srcDir). Android uses the classic DSL outside the plugin. |
| R6 | **KMP Android library has a single variant; Koin starts after the content providers.** | certain | `:ux-spike-android` through `debugImplementation`. F2 defers the call and guards it to run once. The release APK is checked. |
| R7 | **DC-API cannot be driven on the simulator.** On iOS, `PlatformBackHandler` throws without a `LocalNavigationEventDispatcherOwner` (`PlatformBackHandler.ios.kt` KDoc), and the extension has no `NavDisplay`. | certain / D10 on a device only | The harness is evidence only. Track C passes a no-op back handler and its own `ViewModelStoreOwner`. A D10 that was run and failed is a declared exception. D10 not run means Incomplete. |
| R8 | **VoiceOver correctness in Compose MP iOS.** The baseline has 0 `liveRegion` in `SUI/commonMain`. | medium / KC3 | The iOS `actual` posts the screen-changed notification. The a11y data is derived per boundary §1.7. The Inspector runs before the device walk. |
| R9 | **The Nav3 iOS back stack is not saveable**, and the Android standalone `UxFlowRoute` must serialize. | certain (iOS) / medium (Android) | iOS behaves as brief §5 requires and is tested by D3. Android is tested by P6 in M11; a failure is KC2 evidence. Neither leads to an `AppRoute` edit. |
| R10 | **Footprint creep.** | medium | The §2 cap; the `--diff-filter=MDRT` check at every gate; at most one file per ruling. |
| R11 | **A D8 change outside `ux-spec/`** (the fake engine, or a growing `Services`). | medium / KC1 failed | Data-driven scripts (boundary Q14). The owner rules in advance (Q-002), or the drill fails. |
| R12 | **Android release signing** (`androidApp/build.gradle.kts:27-40` reads `${rootDir}/sign` plus environment keys). | high | `-Pandroid.injected.signing.*` with a throwaway keystore in `$TMPDIR`. Without an APK the check is unresolved. |
| R13 | **`feature/kmp` moves daily**, so T2 and T3 may conflict. | high | ⟲ only at the marked milestones, with backup branches and small anchored edits. |
| R14 | **The Nav3 entry owner may not provide a `SavedStateHandle`.** | low-medium | Probe P4. The fallback is a `rememberSaveable` snapshot in the entry's saveable-state holder (`AppNavDisplay.kt:99`). |

---

## §5 Kill criteria: evaluation

Haiku measures (counts, diffstats, lock checks). Opus interprets, and a kill signal is re-derived independently before
anything stops. The owner judges everything; the app team gives KC5.

| KC | Criterion (brief §8) | How evaluated | Rows | Passed | Failed | Unresolved |
|---|---|---|---|---|---|---|
| KC1 | A regeneration needs an edit in generated/interpreted screen code, or the runtime contains screen-specific code. | See "KC1 procedure" below. | D8-logo, D8-offline, D8-brand, D8-copy; KC1 guard; review note | All 4 rows pass: the diff is restricted to `ux-spec/**` **and** the behaviour changed, and the review finds nothing. | A diff outside `ux-spec/**` that the owner did not rule on in advance, or screen-specific runtime code. | A D8 row was not run. |
| KC2 | More than 1 of the 3 hard states needs Back or restore handling outside the declared policy. | Count the hard states where D1, D2 or D3 needed a handler or fix outside the bundle policy, on both platforms. Also check the D1 grep. | D1-iOS, D1-Android, D2-Android (`am kill` + LMK), D3-sim, D3-device; the engine rows support only | ≤ 1 state, and all rows ran | ≥ 2 states | A row was not run. |
| KC3 | The runtime cannot make focus and announcements correct for VoiceOver after a state change. | The owner's device walk, with fixes only in `:ux-runtime`/`:ux-elements` (diff check). | D7-iOS | Every state change is correct. | Correct behaviour needs screen-specific code, or cannot be reached. | The owner's result is absent. |
| KC4 | A track-B variant needs changes outside TPL-sheet, or one option costs more than about 150 hand-written lines per platform. | See "KC4 procedure" below. | D9-brand, D9-sheet-page | Both options ≤ 150 on each platform, and only TPL-sheet files changed. | An option exceeds the limit or leaks outside TPL-sheet. | D9 was not run. |
| KC5 | The app team estimates that moving navigation control out of their VMs costs more than 2× the spike. | Inputs to the estimate: §6, the spike diff against `origin/feature/kmp`, and the touched-file count. | estimate | ≤ 2× | > 2× | No estimate. The verdict is then at most "go with exceptions". |

**KC1 procedure.** Every mutation starts from the **same baseline SHA**.
1. Run the pre-D8 baseline grep.
2. Save the spec change to `spike-evidence/D8/<n>/spec.patch`. The mutation is made in a throwaway worktree of the
   analysis repo, and that worktree is never committed.
3. Export, and save the export and its lock as `export.tar.gz`.
4. Sync the export into `spike/d8-<n>`.
5. Verify the lock.
6. Run the full gate.
7. Show the behaviour with a screenshot or video, plus a semantics assertion.
8. Record `git diff --stat <baseline>..spike/d8-<n>`.
9. Opus reviews the code of `:ux-runtime`.

**KC4 procedure.**
- Count with `git diff --numstat <D9-baseline>..<option-commit>`, where `<D9-baseline>` is the SHA right before M9 (named in
  `SPIKE-LOG.md`).
- Split the count into `commonMain`, `androidMain` and `iosMain`. **The commonMain lines count toward each platform's
  total**: Android = common + androidMain, and iOS = common + iosMain.
- Check the paths: only TPL-sheet implementation files may change.

The verdict also needs D4, D5, D6 and D7-Android (§0.5). D10 not run means Incomplete; a D10 that ran and failed is an
exception.

---

## §6 Port-mismatch findings already visible

These were read from the code at `b23f6a1f5`. They feed REV-013 (week 0), the fake engine and the KC5 estimate.

| # | Finding | Evidence | Spike handling |
|---|---|---|---|
| P1 | **No verification-code screen.** After biometric success the app goes straight to sending. | `CM/presentationfeature/ui/request/PresentationRequestViewModel.kt:62-87` (`onSuccessNavigation = PushRoute(PresentationLoadingRoute(...))`) | The fake supplies `verificationCode()` (boundary Q4). |
| P2 | **No expired outcome.** `expir*` appears nowhere in `presentationfeature/` or `commonfeature/ui/request\|loading` (grep, 0 hits). | as stated | Fake scripts `expired-at-*`. A real adapter cannot produce `Expired` without an engine change. |
| P3 | **Untyped failures**: `Failure(val error: String)`. | `PresentationRequestInteractor.kt:44`; `PresentationLoadingInteractor.kt:47, 57` | Generated sealed results (boundary §2.4). |
| P4 | **A PIN fallback is always present.** | `CM/commonfeature/ui/biometric/BiometricScreen.kt:68-71`; `PresentationRequestViewModel.kt:68` (`textAbovePin`) | DEC; the fake is biometric only. The navigator-free overload is at `BiometricScreen.kt:142`. |
| P5 | **Navigation authority lives in VMs and configs.** | `PresentationRequestViewModel.kt:62-87`; `PresentationLoadingViewModel.kt:84, 95, 121, 192, 222, 237` | The main KC5 cost. |
| P6 | **`connecting` is not a state**: it is the request VM's `isLoading`. | `CM/commonfeature/ui/request/RequestViewModel.kt:40` | Separate states in the bundle. |
| P7 | **Share is gated by a disabled button**, where the spec shows a `no-selection` error after Continue. | `RequestViewModel.kt:55` (`allowShare`) | The spec's behaviour, in `PresRequestScreen`. |
| P8 | **Sending is interruptible and may authenticate a second time** (5 s cancellable timeout; `UserAuthenticationRequired`). | `PresentationLoadingViewModel.kt:103, 153`; `PresentationLoadingInteractor.kt:43` | Back is blocked; the fake has no second auth. |
| P9 | **An untrusted verifier blocks** the presentation; the spec allows an explicit approval (DEC-002). | `PresentationRequestViewModel.kt:169-177` | Script `untrusted`. |
| P10 | **The spec has no deep-link entry** (`initial: scan`), while the app enters at the request route. | `FLW-pres-cross-device.yaml:6`; `DashboardViewModel.kt:283-291` | `entries.deeplink` (boundary §1.5, W0). |
| P11 | **The success screen differs**: it lists the documents and opens the redirect. | `SharedEntries.kt:243-253` | Spec outcome only. |
| P12 | **Restore is asymmetric**: iOS is not saved, and the DC-API intent is lost on Android restore. | `IosNavHost.kt:81`; `PresentationRequestViewModel.kt:89-97` | Brief §5; DC-API is never intercepted. |
| P13 | **Interactors are scoped per mode.** | `CM/commonfeature/config/RequestUriConfig.kt:36-64` | The fake has no scope. |
| P14 | **Attribute labels**: services must return copy ids, while a real adapter has claim names. | boundary Q5 | Logged gap; week 0 adds a `claims → CPY-*` map. |
| P15 | **The a11y baseline is empty**: 0 `liveRegion` uses, and `semantics` in 2 files. | grep `SUI/commonMain` | No comparison baseline for D7. |

Hosting notes:
- A standalone `NavKey` is not reported to analytics (`AppNavDisplay.kt:84`).
- The hook adds `entry<UxFlowRoute>` and redirects. It does not re-register `entry<PresentationRequestRoute>`; whether
  Nav3 tolerates a duplicate registration is not relied on.

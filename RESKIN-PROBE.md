# Reskin probe: option B measured (`spike/reskin-probe`)

Owner-requested measurement for the A / B / hybrid decision. **A** = flows run as data by the runtime plus generated
screens (measured by the spike, `spike-report.md`). **B** = the generated design system (elements, templates, tokens)
plugged into the app team's existing hand-written screens, view models, navigation and logic. This probe gives B
comparable numbers on two screens. Unattended run; decisions are logged in §8.

Branch `spike/reskin-probe` in worktree `/Users/roman/code/eudi-kmp-reskin`, cut from `spike/ux-runtime` @ `599e360ec`.
Never pushed. Nothing committed on `spike/ux-runtime`; `/Users/roman/code/eudi-kmp-device` not touched.

| Commit | What |
|---|---|
| `57aeaa22b` | debug-only consent harness (evidence tooling, not part of the reskin cost), simulator helpers, **before** shots |
| `6e613720c` | **the reskin**: Home (+ bottom bar) and the consent screen on generated elements and `UxTheme` |
| `efaca5dba` | **regeneration check**: `ux-spec/` synced verbatim from a throwaway export; 0 files outside `ux-spec/` |
| `ade35bd9e` | baseline `ux-spec/` restored (eudiw_v1, lock ux@`b55be65`) |
| (this commit) | this report, logs, export archive |

## 1. What was reskinned

- **Home after unlock** = `DashboardScreen` with the Home tab: `HomeScreen` plus the dashboard's `BottomNavigationBar`.
- **Consent** = `PresentationRequestScreen` → `commonfeature/ui/request/RequestScreen` (also used by the proximity
  request screen, which therefore changes with it).

Unchanged: every view model, `State`/`Event`/`Effect`, navigation handling, the effect collectors, the permission
logic, the bottom sheets' logic, the strings logic (one string added, §3). No flow runtime, interpreter or bundle is
used: the screens call `:ux-elements` composables directly under `UxTheme`.

How it is wired (option B, as it would ship):
- `shared-ui` now depends on `:ux-elements` and `:ux-contract` in **commonMain for every build, release included**.
- `UxAppTheme` (adapter) applies `UxBrandConfig.defaultBrand` (so a brand switch in the export needs no Kotlin), loads
  the export's `ux-tokens.json` / `ux-library.json` / `ux-assets.json` / drawables, and **bridges the brand's
  `sys.color.*` roles into the Material colour scheme** of the subtree, so the Material components the screens still
  draw (sheets, loading overlay, kept list items) follow the brand's colours.
- `ContentScreen` stays as the behavioural shell (back handler, loading overlay, error state); visuals come from
  `TplHome` (Home) and from elements (consent).

## 2. Component mapping

Every component the two screens draw, directly or through `ContentScreen` and the dashboard host.
**33 components: 13 direct, 8 adapter needed, 12 missing (kept as the app's component).**

| # | App component | ux element / template | Mapping | Props and behaviour mismatches |
|---|---|---|---|---|
| 1 | `ContentScreen` (Home: no toolbar, back = finish, loading) | `TPL-home` | adapter | shell kept for back/loading/error; its paddings are dropped because the template owns the insets (status band, sides, bottom) |
| 2 | Home `TopBar`: `WrapIconButton(Menu)` | `EL-app-bar` leading=menu | direct | 48 dp target, label from the app's `content_description_menu_icon` |
| 3 | `AppIconAndText` (logo lockup) | — | missing | kept as `TPL-home` header in the band; on idsk the wordmark (tinted `onSurface`) is black on the dark band: **contrast defect** (shot) |
| 4 | welcome `Text(headlineMedium)` | `EL-text` title | direct | gains heading semantics (a11y change); type from `sys.type.title` |
| 5 | `WrapActionCard` | — | adapter `UxActionCard` | card ground from `surface.card`/`radius.card`/hairline border; title becomes an `EL-text` heading (bold, heading colour, larger) |
| 6 | action-card art (`WrapImage`, app drawables) | — | missing | kept; never re-brands |
| 7 | card primary `WrapButton` | `EL-button` primary | direct | radius `control` (square-ish) instead of pill |
| 8 | "Learn more" tonal button with info icon | `EL-link` (internal) | direct | underlined link with the library chevron, left-aligned; info icon gone |
| 9 | `BottomNavigationBar` (Material `NavigationBar`, 3 tabs) | `EL-bottom-bar` | adapter | floating pill vs docked bar; spec has 2 destinations, app 3; tab icon map is app code (Home has no library icon in either brand → no glyph; idsk binds no `icon-id-card` → Documents loses its glyph); **library change**: `tabTag` hook (+4/−2) so `dashboard_screen_bottom_navigation_item_*` survive |
| 10 | `WrapModalBottomSheet` | `TPL-sheet` (no modal) | missing | `TPL-sheet` is a screen template, not a draggable modal; kept, colours bridged |
| 11 | `BottomSheetWithTwoBigIcons` (authenticate / sign) | — | missing | kept |
| 12 | `GenericBottomSheet` (learn more ×2) | — | missing | kept |
| 13 | `DialogBottomSheet` (Bluetooth) | — | missing | kept |
| 14 | `LoadingIndicator` (via `ContentScreen`) | `TPL-progress` is a screen | missing | kept, bridged |
| 15 | consent `ContentScreen` (BACKABLE, sticky bottom, error) | `TPL-sheet` | adapter (template not usable) | `TPL-sheet` `page` ignores `appbar` and owns all insets; a pushed route with a back bar and sticky bottom does not fit it, so the shell stays and the brand's `layouts` selection is never read |
| 16 | `DefaultToolBar` back arrow | `EL-app-bar` leading=back | direct | `icon.arrow-back-ios` is **unbound in both brands** → the back button is an empty (but labelled, tappable) 48 dp box: **visible regression** |
| 17 | `ContentError` (error config) | `TPL-outcome` + `EL-status-hero` | missing | not mapped in the probe; kept |
| 18 | `ContentTitle` | `EL-text` title | direct | — |
| 19 | `RelyingParty(InlineStart)` | `EL-rp-identity` | direct | needs a **text** trust label (app had icon-only "verified", nothing for unverified) → +1 app string; avatar placeholder when no logo; the `(ID: …)` line goes into the service slot |
| 20 | `InfoSection` (intended use) | `EL-rp-identity` reason | direct | merged into the identity card |
| 21 | `InfoLinkSection` (privacy) | `EL-link` external | direct | link text is now "Privacy policy" (was the URL); no external-hint string in the app, so no spoken hint |
| 22 | `SectionTitle` (requested data) | `EL-text` heading | direct | gains heading semantics |
| 23 | `WrapExpandableListItem` (one document) | `EL-attribute-list` | adapter `UxRequestedDocument` | header + expand toggle written in the adapter; nested claims flattened ("group · claim"); image claims lose the image; mandatory inferred from a disabled+checked checkbox (the app marks nothing required, M11b F-M11b-4) |
| 24 | claim rows (`WrapListItem` + `WrapCheckbox`) | `EL-attribute-item` | adapter (via 23) | checkbox left, brand `styles.checkbox` (outline / filled) applies |
| 25 | `WrapSelectableCard` (several combinations) | — | missing | kept |
| 26 | `TransactionDataSection` (signature details) | — | missing | kept (also used by the iOS DC-API consent) |
| 27 | `RequestWarningNote` (`SectionTitle` body small) | `EL-text` small | direct | — |
| 28 | `ErrorInfo` (no data) | `EL-notice` info | direct | — |
| 29 | `ConsentStickyBottomSection` | `TPL-sheet` actions region | adapter `UxConsentStickyBottom` | written inside `RequestScreen` (the shared component stays for the other consent screens); divider dropped |
| 30 | `AcknowledgeWarningCard` | `EL-notice` warning + `extra` | adapter | no switch/checkbox element in the library → the app's `WrapListItem` switch row is kept inside the notice |
| 31 | `WrapStickyBottomContent` two buttons | 2 × `EL-button` (secondary, primary) | direct | the app's order (Cancel \| Share, one row) kept; the spec stacks primary first. Test tags kept as the button ids |
| 32 | `SimpleBottomSheet` (warning) | — | missing | kept |
| 33 | `DialogBottomSheet` (verifier not trusted) | — | missing | kept |

`UxAppTheme` (theme + data files + Material bridge) is the one cross-cutting adapter, not counted in the rows.

## 3. Lines and time

`git diff --numstat 57aeaa22b 6e613720c` (main code; there are no screen tests to change). In brackets: `-w`
(whitespace-insensitive; wrapping a block in `UxAppTheme { }` re-indents it).

| Part | + / − | (`-w`) |
|---|---|---|
| **Consent** `RequestScreen.kt` | 231 / 145 | (152 / 66) |
| **Home** `HomeScreen.kt` | 85 / 122 | (52 / 89) |
| **Home** `BottomNavigation.kt` | 31 / 39 | (28 / 36) |
| Adapter `UxAppTheme.kt` (theme, data files, Material bridge) | 94 / 0 | |
| Adapters `UxReskinAdapters.kt` (`UxActionCard`, `UxRequestedDocument`) | 168 / 0 | |
| Library `UxBottomBar.kt` (`tabTag` hook) | 4 / 2 | |
| `shared-ui/build.gradle.kts` | 5 / 0 | |
| `strings.xml` (+1 string) | 1 / 0 | |
| **Total** | **619 / 308** | |
| Test code | 0 / 0 | the app has no UI tests on these screens |
| Evidence harness (debug-only, not a reskin cost) | 155 / 0 Kotlin + 32 tooling | `ReskinRequestHarness.kt`, hook +5 |

Per screen, adapters included pro rata: **Home ≈ 115 net added lines + `UxActionCard` (~50)**; **consent ≈ 150 net
added lines (incl. the sticky-bottom adapter) + `UxRequestedDocument` (~110)**; `UxAppTheme` is one-time.

Time (agent wall clock, this machine; the run started 14:42:32 and the reskin commit is 14:59:57):
- reading and mapping ≈ 5 min; writing the two screens and three adapters ≈ 8 min; **compile/lint loops 2**
  (duplicate imports; detekt `UnusedImports` + `UnusedParameter`), ≈ 3 min; first iOS Debug build 3 min 47 s.
- spec side of the regeneration check ≈ 3 min (one refusal: copy gate G5 wants an `edit` provenance for reworded copy),
  sync + iOS build + shots ≈ 4 min; module tests 1 min each run.
- **Reskin of two screens ≈ 15–20 min of agent time end to end** (excl. the evidence harness ≈ 5 min).

## 4. Tests, lint, what broke

Run on `6e613720c` and again on the regen sync `efaca5dba`:
- `:shared-ui:testAndroidHostTest :shared-ui:iosSimulatorArm64Test :ux-elements:allTests :ux-theme:allTests`
  (+ `:ux-runtime:allTests :ux-spike:allTests` on the sync): **0 failures** (shared-ui 1 284 results, ux-elements 109,
  ux-theme 18, ux-runtime 196, ux-spike 31). Logs `spike-evidence/reskin/logs/tests-*.log`.
- Root `./gradlew detekt ktlintCheck`: green after the two fixes above. ktlint's configuration covers the Android
  modules only, so the KMP `shared-ui` sources are linted by detekt alone (the app's existing setup).
- iOS Debug `xcodebuild` green on all three states. Android: `shared-ui`'s Android target compiles (host tests); no
  APK built, no emulator run.
- **Which of Martin's tests broke: none.** His unit tests never render these screens (only `HomeViewModelTest`, which
  is view-model only). The tests that *could* break are his Maestro / Appium E2E flows; not run here. Their selectors
  were kept on purpose: `dashboard_screen_bottom_navigation_item_{home,documents,transactions}` (needed the library
  `tabTag` hook), `request_screen_primary_button`, `request_screen_secondary_button`,
  `request_screen_requested_document_<n>`. At risk: flows that tap a claim row or a checkbox by Material structure,
  or read the toolbar back button by its icon; the Appium `request_screen_share_button` id was already stale before.

## 5. Regeneration check (option B)

Throwaway spec worktree `/Users/roman/code/dvm-reskin` (detached at the lock's `b55be65`, removed after the run), one
commit `95b03406374edc017cba088bdca6e5f5edff46aa`, patch `spike-evidence/reskin/regen/spec.patch` (5 files, +15/−15 lines incl. two provenance lines):
`default_brand: idsk`; idsk `sys.color.action.primary.bg` `{ref.color.blue}` → `{ref.color.dark-green}`; copy
`CPY-pres-request-title` and `CPY-home-title` reworded; in `SCR-pres-request` the `privacy` node moved above
`rp-trusted`. The D8-attempt2/3 export was not reusable (exporter 0.3.0: no `ux-tokens.json`/`ux-library.json`), so
the export was redone with exporter 0.5.0. Spec gates: `fast`/`check` green, `test` red on E13 (a spec-side test that
pins eudiw_v1 as the default brand, the same class of pin as M9); the exporter accepted the gates. Sync commit
`efaca5dba`: 38 files, all `ux-spec/**`, **0 Kotlin lines**.

| Change in the spec | Visible on Martin's screens after a sync only? | Evidence |
|---|---|---|
| brand eudiw_v1 → idsk | **yes**: app-bar band (white → dark blue), page ground, card borders, checkbox style (outline → filled, a brand `styles` selection), link and text colours | `after-eudiw_v1-*` vs `after-idsk-*` |
| one token value (`action.primary.bg`) | **yes**: Authenticate, Sign and Share buttons dark green | `after-idsk-home.png`, `after-idsk-request.png` |
| icons / assets bound per brand | **yes, for library parts** (trust glyph, notice icon, checkbox tick, tab icons: idsk drops the Documents glyph); **no** for the app's own drawables (logo, card art) | same shots |
| copy (`CPY-pres-request-title`, `CPY-home-title`) | **no**: the screens read the app's `strings.xml` (English, `request_screen_title`); the export's copy is Slovak and keyed by `CPY-*`. Copy would regenerate only if these screens were moved to the generated copy keys (and the app's locales reconciled with the spec's) | title still "Data sharing request" |
| composition (privacy link above the verifier) | **no, and it cannot**: the order is fixed in `RequestScreen.kt`; the privacy link stays below the identity card | `after-idsk-request.png` |

## 6. Regeneration matrix, A versus B

| What changes in the spec | Option B (this probe) | Option A (spike, `spike-report.md` §3, §9.3) |
|---|---|---|
| token values (`sys.*`, `comp.*` already read) | regenerates, 0 lines | regenerates, 0 lines |
| brand switch | regenerates (colours, type, radii, `styles`), 0 lines | regenerates, 0 lines |
| brand `layouts` (sheet vs page) | **no** (templates do not fit the app's shells; the consent screen never reads it) | regenerates (D9) |
| icons and art of library parts | regenerates where the brand binds them | same |
| the app's own icons and art | no | n/a (all parts are library parts) |
| element look (an element's Kotlin) | on a library release, all screens at once | same |
| new element, prop, variant | needs Kotlin (library) **and** an edit at every call site | needs Kotlin (library) only (§9.2) |
| copy | **no** (app strings) | regenerates, 0 lines (D8 copy) |
| composition / node order / new node | **no** | regenerates, except the hand-written consent screen |
| rules, ui states, flows, back/restore policy | **no** | regenerates (flows as data) |
| a11y order and labels | **no** (fixed in screen code) | from the spec's nodes and a11y block |
| the app's kept components (sheets, loading, error) | colours only, through the Material bridge | n/a |

Cost side, same sources: A needs the one-time runtime (≈ 4.9 k main lines), a Services adapter per flow (≈ 560 main /
470 test for this flow, with 6 of 21 results impossible today, M11b), and its consent screens are hand-written anyway.
B needs none of that: no contract, no adapter, no runtime, no change to the app's behaviour; ≈ 100–260 lines and
≈ 10–20 min per screen.

## 7. Cost to reskin the whole app (B)

App screens: **30** top-level `…Screen` composables in `shared-ui` outside `uilogic/component` (31 minus the
`ContentScreen` shell), of which 3 are hosts or thin wrappers (`DashboardScreen`, `PresentationRequestScreen`, and
`ProximityRequestScreen` which reuses `RequestScreen`), plus ≈ 10 bottom-sheet contents and the side menu. Done: 2.
Left: **≈ 26 screens + ≈ 10 sheets**.

| Item | Lines (main) | Agent time | Basis |
|---|---|---|---|
| 26 screens | 26 × 60–150 = **1.6–3.9 k** | 26 × 10–25 min = **4.5–11 h** | Home ≈ 115, consent ≈ 150 (`-w` added) |
| missing parts as adapters or library elements: list item, selectable/expandable card, modal sheet + its 4 content kinds, PIN field, text field, chip, FAB, loading, error/outcome, switch/checkbox | 10–14 × 50–250 = **0.5–3.5 k** (library elements at the spike's 115–280 per part, or thin adapters at 50–110) | **3–8 h** | M5/M5e per-part cost; this probe's adapters |
| test-tag hooks and E2E selector repair (Maestro, Appium) | 50–300 | 2–5 h incl. device runs | `tabTag` (+4) here; 54 E2E flows in `.maestro/` (outside `ux-spike/`) |
| one-time: theme bridge for the whole app, release packaging (split the design system from `:ux-contract`, which carries the flow bundle), dark mode decision | 150–400 | 1–3 h | `UxAppTheme` 94 |
| visual review on both platforms, device a11y walk | 0 | 4–8 h | not measured here |
| rework reserve | +25–40 % | +25–40 % | spike rework share |

**Total ≈ 2.8–9.8 k main lines incl. reserve, ≈ 18–45 h of agent time (≈ 2.5–6 agent-days)**, plus owner/designer
visual sign-off. The upper end is dominated by building missing parts as proper library elements rather than adapters.

## 8. Risks

- **Theme coexistence.** The bridge recolours only Material colour roles inside `UxAppTheme`; Material typography and
  shapes stay, and the app's extension colours (`success`, `warning`, `pending`) are not bridged. Until every screen is
  reskinned, the app is visibly mixed (the dashboard host and the other tabs are still Material). The wallet's
  `ThemeManager` is untouched; two theme systems must be maintained in parallel during migration.
- **Missing icons are invisible controls.** The library draws nothing for an unbound asset (DEC-080): the consent back
  button is an empty box in both brands, the Home tab has no glyph, idsk drops the Documents glyph. B needs the spec to
  bind every icon an app screen uses, or an app-side fallback (which breaks "no art invented").
- **Kept app components do not re-brand fully.** The logo wordmark is unreadable on idsk's dark band; card art and
  sheets keep the app's look.
- **Android SVG.** Compose on Android draws no SVG drawable (spike finding); these two screens use only vector XML and
  PNG parts, but any SVG art in a brand will vanish on Android. Not run on Android here.
- **Dark mode.** The export has no dark tokens; `UxAppTheme` maps the system setting to `UxMode.Dark`, which falls back
  to light. Reskinned screens stay light while the rest of the app follows the system: a regression for dark-mode users
  until the spec exports dark modes.
- **Test tags.** Elements derive their tags from ids (`<id>.tab.<tab>` etc.); every element whose inner tags an app
  test uses needs a hook like `tabTag`, or the tests move. Material-structure selectors in E2E flows will break.
- **Accessibility.** Changes in this probe: headings added (welcome, section titles); the trust badge becomes text; the
  privacy link is named "Privacy policy" instead of the URL and has no external hint; the expand toggle's state labels
  reuse unrelated app strings ("Arrow up" / "View details"). Needs a device walk (VoiceOver, TalkBack) before shipping.
- **Release footprint.** `:ux-contract` now ships in release (it also carries the spike's flow bundle and fixtures as
  resources). Under B the design system must be its own module without the flow contract.
- **Locale and copy.** The spec's copy is Slovak and keyed `CPY-*`; the app is English with its own keys. B leaves copy
  with the app permanently unless screens migrate to generated keys.

## 9. Recommendation: hybrid, B first

1. **Adopt B now for the whole app.** It is cheap (≈ 10–25 min and 60–150 lines per screen), needs no change to view
   models, navigation, interactors or the Services contract (the M11b gaps do not block it), and it already delivers
   the most frequent design-side changes with 0 Kotlin: tokens, brand switches, `styles`, library icons and element
   looks. Prerequisites: the spec binds every icon the app uses, a dark-mode decision, and a design-system module split
   from `:ux-contract`.
2. **Use A only where behaviour, copy and composition are spec-owned and change often**: the presentation and issuance
   flows, once the A verdict's missing device evidence and KC5 exist and the contract gaps (typed `Expired`,
   verification code, `transactionData`) are closed. There A's extra regeneration (copy, composition, rules, states)
   pays for the runtime and adapters; on the rest of the app (settings, documents, history, PIN) B is enough.
3. **Grow the library from B's missing list** (sheet, list item, selectable/expandable card, inputs, switch, loading,
   outcome): each part built once serves both B screens and A flows, and turns B's adapters into direct mappings.

Why not A alone: it regenerates more, but costs a runtime plus a ≈ 1 k-line adapter per flow and leaves consent
screens hand-written anyway, and the app team's estimate (KC5) is still missing. Why not B alone: copy, composition and
flow logic stay in the app's code, so the design team cannot change those without an app-team edit; that is exactly the
part A was built to solve.

## Evidence

`spike-evidence/reskin/` — simulator iPhone 17 (`3C45E6C6`), iOS 27.0, light, en:
- `shots/before-home.png`, `shots/before-request.png`, `shots/before-request-untrusted.png` — the app's Material
  theme. The before build does not read `ux-spec/` at all, so its look is the same under either brand by construction;
  it was shot once.
- `shots/after-eudiw_v1-{home,request,request-untrusted}.png` — reskin, baseline export (eudiw_v1).
- `shots/after-idsk-{home,request,request-untrusted}.png` — after the sync only (idsk + token + copy + composition).
- `regen/` spec patch, spec commit, spec gate log, export log, `export.tar.gz`; `logs/` builds, lint, tests.
- `sim.sh`, `open-link.yaml` — how the shots were taken: `--ux-spike reskin-request[-untrusted]`, unlock, open an
  `openid4vp://` link; the debug hook redirects it to `ReskinRequestHarness`, which runs the **real**
  `PresentationRequestScreen` and `PresentationRequestViewModel` over a canned `PresentationRequestInteractor` (no live
  verifier needed). Home is the real Home after unlock.

# Decision brief: how the wallet adopts UI generated from the `ux/` spec

For the app team (Martin) and management. 2026-10-18. Sources: `spike-report.md` (A), `RESKIN-PROBE.md` (B),
`spike-results.md`, the operating-model evaluation, the owner's device session of 2026-10-18.
**[est]** = estimated, **[unmeasured]** = no number exists; everything else was measured once, on one flow or two screens.

## 1. The decision

Which part of the wallet UI is generated from the spec, and which part the app team keeps writing by hand:
- **A, full generation.** A flow runtime runs flows as data from the exported bundle; screens are drawn by an interpreter.
- **B, generated design system only.** Generated elements, templates and tokens go into the app team's existing screens;
  view models, navigation and logic stay as they are.
- **Hybrid.** B for the whole app, A only for flows whose copy, composition and behaviour change often.

## 2. Comparison

| | A: flows as data | B: design system in existing screens | Hybrid (B everywhere, A for chosen flows) |
|---|---|---|---|
| Regenerates with 0 Kotlin lines | tokens, brand, sheet/page layouts, copy, composition, rules, UI states, flows, back/restore policy, a11y order | tokens, brand, `styles`, library icons, element looks. **Not**: copy, composition, layouts, flows, a11y order | B's list on every screen; A's list on the A flows |
| Stays hand-written | runtime (≈ 4.9 k main lines, one-time, built); elements (115–280 lines each); consent screens (170–260 each); a Services adapter per flow; entries and exits | every screen, view model, navigation, copy; adapters for missing parts (8 of 33 components needed one, 12 of 33 have no element) | both lists, each on its own part of the app |
| Coupling to app logic | the generated Services contract. On the real interactors (M11b): **21 results: 5 mapped, 10 lossy, 6 impossible**. No typed `Expired`; no verification-code source, so a real adapter **cannot reach `sending`**; `transactionData` dropped (security gap) | none: no contract, no adapter, no view-model change | contract only for the A flows; their gaps must close first |
| App-team effort | **KC5 pending** (Martin): moving navigation out of the view models. Agent figures below | **not estimated by the app team**; agent figures below | KC5 for the A flows; B as below |
| Measured cost | per flow: adapter 562 main / 468 test lines, ≈ 42 min (this flow). A flow of existing parts: 0.4–0.8 k lines, 4–7 h **[est]**; with a consent screen and 3–6 new parts: 1.5–3.5 k lines, 1–2 agent-days **[est]**. Per element: 115–280 main + 8–39 registry + 30–90 test, 15–40 min. UI-only change: 0 lines, 15–25 min | per screen: Home ≈ 115 lines + adapter ≈ 50; consent ≈ 150 + adapter ≈ 110; two screens 619 / 308 lines in 15–20 min. Whole app (≈ 26 screens + ≈ 10 sheets left): **2.8–9.8 k lines, 18–45 h of agent time [est]** | B whole app **[est]** + per A flow as in column A |
| Risks | DC-API extension: **4 jetsam kills at 220 MB on warm reuse** (baseline 0), footprint **[unmeasured]**; real Android LMK and TalkBack unproven; contract gaps; Martin proposed compile-time generation, the spike chose runtime | two themes in parallel during migration; **unbound icons draw nothing** (consent back button is an empty 48 dp box in both brands); dark mode falls back to light; 54 Maestro E2E flows **[not run]**; `:ux-contract` ships in release until split | the sum, but A's risks only on the A flows |
| Proven vs estimated | proven on the iOS simulator, the Android emulator and (2026-10-18) the iPhone: VoiceOver, cold start, extension 5/5 cold. **Not proven**: real Android LMK, TalkBack on a phone, KC5, a second flow | proven on 2 screens, **iOS simulator only**: build, lint, 0 test failures, regen shows brand + token changes. **Not proven**: Android, device a11y walk, E2E, the whole-app figure | B's part is an estimate from 2 screens; A's part as column A |

Copy and every UI change still ship as an app release under all three options: the bundle and tokens are compiled in.

## 3. Recommendation: hybrid, B first

1. **Adopt B now for the whole app.** It costs 60–150 lines and 10–25 min per screen, touches no view model, navigation
   or interactor, and does not depend on the M11b contract gaps. It already delivers the most frequent design changes
   (tokens, brand switch, styles, library icons, element looks) with 0 Kotlin lines.
2. **Use A only for the presentation flows (later issuance)**, where copy, composition and behaviour are spec-owned and
   change often. There, A's extra regeneration pays for the runtime and a ≈ 1 k-line adapter per flow. Elsewhere
   (settings, documents, history, PIN) B is enough.
3. **Grow the element library from B's missing list** (sheet, list item, selectable/expandable card, inputs, switch,
   loading, outcome): each part serves both B screens and A flows.

Why not A alone: 6 of 21 contract results are impossible today, consent screens stay hand-written anyway, KC5 is
missing, and the extension is killed on reuse. Why not B alone: copy, composition and flow logic stay in app code, so
every design change there needs an app-team edit.

**Conditions.** For B: the spec binds every icon the app uses; a dark-mode decision; the design system split from
`:ux-contract` into its own release module; the two reskinned screens run on Android with a TalkBack and VoiceOver walk;
an E2E test-tag policy. For A on a flow: the real LMK and TalkBack phone walk pass; KC5 at most 2× the spike; the
contract gaps (typed `Expired`, a verification-code source, `transactionData`) are closed; the extension's memory on reuse
is fixed or declared an exception.

## 4. Decisions needed

| From | Decision |
|---|---|
| App team (Martin) | KC5 estimate for A on `FLW-pres-cross-device`, and runtime versus compile-time generation |
| App team | accept B in their screens (`shared-ui` depends on the design system in release); E2E selector policy |
| App team | push consent for `spike/ux-runtime` and `spike/reskin-probe` (or a private mirror); fix F-D2-1 upstream |
| App team + owner | the Services contract gaps: verification-code source, typed `Expired`, `transactionData` |
| Owner | adopt the hybrid, B first; dark mode; "the spec binds every icon"; copy and locale policy for B (spec copy is Slovak `CPY-*`, the app's is English) |
| Owner | declare the extension's memory on reuse an exception, or order a fix |
| Owner | confirm or overrule the agent rulings on KC1, KC2, KC4 (Q-KC-CONFIRM); touch list (Q-TOUCH); DEC-069…082 (Q-DEC) |

## 5. Next 4–6 weeks

| Week | Work | Owner |
|---|---|---|
| 1 | Close the spike: real LMK and TalkBack on an Android phone (Q-D2-LMK, Q-D7-Android), KC5, owner rulings; go/no-go meeting | owner team, Martin |
| 1–2 | B foundation: design-system module split, icon bindings in the spec, dark-mode decision, whole-app theme bridge, test-tag policy; run the 2 reskinned screens on Android with screen-reader walks | UI platform, owner team |
| 2–4 | B rollout: ≈ 26 screens + ≈ 10 sheets in batches, 2.5–6 agent-days **[est]**, plus the 10–14 missing parts; visual sign-off per batch; Maestro E2E on both platforms | UI platform, app team review |
| 3–5 | A preparation: close the contract gaps; contract versioning in app CI; measure the extension footprint (fix the instrumentation) and try a memory fix | owner team, app team |
| 5–6 | Decide A for the presentation flow behind a production flag, or stay on B; make the same-device flow executable in the spec | owner team + app team |

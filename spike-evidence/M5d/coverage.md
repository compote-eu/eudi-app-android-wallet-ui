# M5d coverage: dashboard Home screen (SCR-home, template TPL-home), probe OR-1

Spec: `ux/experience/screens/SCR-home.yaml` (spec commit `ec5e58e3`), 28 nodes, 15 ui_states, 19 rules; profile PRF-eudiw-v1
drops `switch` and `trustmark` (features off), and the bottom-nav variant `bar` replaces the `present` button.
Fragments: `bundles/home-<ui state>.bundle.json` (generator `tools/make_home_fragment.py`), drawn by `--ux-spike gallery-home[-<ui state>|-full|-high-contrast]`.
Screenshots: `probe-*.png` (iPhone 17 simulator, light; `probe-high-contrast.png`), `real-wallet-home.png` (the app's real Home, for comparison).

Missing parts draw the M5b tags (`ux-unknown-element.<id>` text `[[EL-x id]]`, `ux-unknown-template.TPL-home`), not the M5c
`ux-error.*` node: the registry has no entry for them, so the schema check (which is the `ux-error` source) never runs. No crash.

LOC sizes: M5 measured 1486 main / 596 test for 9 elements + 4 templates (about 115 main per part, registry adapter and schema
about 22 per part), M5c token shell about 30 per part per four modes. Sizes below are main LOC incl. registry adapter and schema,
test is about +40 %. Estimates, not measurements.

| Part | Used by (nodes) | Variants / states needed | Status | Gap | Add (LOC main) | Comparable in the app |
|---|---|---|---|---|---|---|
| TPL-home | screen | 11 regions (appbar, header, switch, notice x2, identity, documents-heading as one row, cards, body, footer, bottom-fixed actions), scroll + fixed bottom, empty regions take no space | missing | whole template; probe draws the placeholder with regions stacked, unpadded, unscrolled | 190 | `ContentScreen`/`DashboardScreen` scaffold (not a template) |
| EL-document-card | card-id, card-dl (+ revoked, suspended, stale, expired, expiring variants), card-pid-cloud, card-pid | variant `full` (art, title, EN name, holder, number, status chip, storage band) and `holder` (avatar initials, dot, chevron); 3 doc_types; card-face `centered` (eudiw_v1); 9 statuses; `open` event; `mini`/`hero` used by other screens | missing | everything; also needs the synthetic card art assets | 330 | none (the app lists documents as `WrapListItem` rows, no art card); `WrapCard` is a plain card |
| EL-status-chip (inside the card) | status on every card | 13 statuses, styles mark=dot, ring=ring | missing (implicit, not a node) | everything | 90 | `WrapChip` (149 LOC, different semantics) |
| EL-bottom-bar | bottom-bar | 2 tabs with icons + round action `menu`, events `select`, `action` | missing | everything; needs icons | 140 | `BottomNavigation.kt` (dashboardfeature, Material bar) |
| EL-segmented-switch | switch | layout auto/segmented/chips, options with `disabled`, event `change`; hidden in eudiw_v1 | missing | everything | 120 | `WrapSwitch` is a toggle, not comparable |
| EL-trust-mark | trustmark | variant badge, `href`, event `activate`; hidden in eudiw_v1 (DEC-062 temporary) | missing | everything; official EU artwork asset | 70 | `TrustMarkScreen` (a screen, not a component) |
| EL-app-bar | appbar, appbar-unread | leading=menu, trailing=notifications, badge | present but partial | no icon assets: menu is a text glyph, bell draws as a note glyph; app logo/brand mark not drawn | 20 + icon table | the app's top bar (`ContentHeader`) |
| EL-button | present, add-document | variant primary, `icon: qr-code` | present but partial | icon is a placeholder glyph square (no asset table) | 10 + icon table | `WrapButton` |
| EL-text | title, heading, body (x4) | title, heading, body | present | none; heading level 2 not expressible (M5 deviation 1) | 0 | `ContentTitle`, `SectionTitle`, `WrapText` |
| EL-notice | 3 nodes | tone info and warning, title optional | present | none visible; the notices are edge to edge only because TPL-home is missing | 0 | `InlineSnackbar`, `AcknowledgeWarningCard` |
| EL-link | history, all-documents | label, href, event | present | none; the documents heading row (text + link on one line) needs the template's `group_rows: false` | 0 | `WrapTextButton` |
| Icons and art (cross-cutting) | app-bar, button, bottom-bar, card | `assets.yaml` icons, card art, trust-mark art | missing (declared gap since M5) | asset table and renderer (M5 deviation 4) | about 150 | `AppIcons`, `AppIconDrawables` |

Counts, 10 parts (9 elements + 1 template): present 3, present but partial 2, missing 5. With the implicit status chip: 3 / 2 / 6.
Estimate to close the gap for Home alone: about 940 main (parts) + about 150 (icons) + about 100 (comp.* tokens in four modes) =
about 1.2 k main, about 0.5 k test, about 1.7 k in total. Rules and ui_states need no runtime work: the 15 ui_states are context data
and the 19 rules are `hide` effects, all of which the interpreter already evaluates (offline, empty, dl-expiring, id-revoked and
notifications-unread were drawn correctly as far as the present parts go).

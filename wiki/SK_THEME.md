# ID SK 3.1 theming reference

Design tokens for the Slovak (SK) wallet theme, extracted from the official
**ID SK** design system (Slovakia's *Jednotný dizajn manuál elektronických
služieb*, a GOV.UK-Design-System fork).

## Source & licensing

- **Tokens**: [`id-sk/idsk3-frontend`](https://github.com/id-sk/idsk3-frontend)
  — **MIT** licensed. Values below are quoted from
  `packages/idsk-frontend/src/govuk/` (the Slovak overrides), not the vendored
  GOV.UK copy.
- **Font**: *Source Sans Pro* → renamed **Source Sans 3**, on Google Fonts under
  the **SIL Open Font License** (free to bundle or download).
- **Assets/icons**: [`id-sk/idsk3-core`](https://github.com/id-sk/idsk3-core) — Apache-2.0.
- Figma community file *ID SK 3.1.0* exists but its tokens live inside the canvas
  (not machine-readable); the MIT frontend repo is the authoritative source.

> **No dark-mode or high-contrast palette is published by ID SK.** The dark theme
> is derived by us — see [Dark theme (derived)](#dark-theme-derived).

## Colors

### Applied / semantic roles
Source: `settings/_colours-applied.scss` (+ `components/button/_index.scss`).

| Role | Hex |
|---|---|
| Brand / primary | `#126dff` (blue) |
| Text (primary) | `#000000` |
| Text (secondary / muted) | `#757575` |
| Background (body) | `#ffffff` |
| Background (canvas / footer) | `#e0e0e0` |
| Focus | `#d96e00` (orange — IDSK changed GOV.UK's yellow) |
| Error | `#c3112b` |
| Success | `#078814` |
| Border | `#b1b4b6` |
| Input border | `#424242` |
| Control hover | `#eeeeee` |
| Link / visited / hover / active | `#126dff` / `#4c2c92` / `#072c66` / `#000000` |

### Semantic families
Each: **dark / base / medium / light** (`settings/_colours-palette.scss`).

| Family | Dark | Base | Medium | Light |
|---|---|---|---|---|
| Info (blue) | `#072c66` | `#126dff` | `#c3d9f9` | `#eff5fe` |
| Error (red) | `#4e0711` | `#c3112b` | `#f8b5b9` | `#fbeef0` |
| Success (green) | `#033608` | `#078814` | `#9fdaa5` | `#ebf5ec` |
| Warning (yellow) | `#4e2a00` | `#bd730c` | `#ebcfaa` | `#faf4ec` |

### Greys / neutrals
`#000000`, `#757575`, `#b1b4b6`, `#e0e0e0`, then neutral ramp
`#9e9e9e` · `#bdbdbd` · `#e0e0e0` · `#eeeeee` · `#f5f5f5` · `#fafafa`, `#ffffff`.

### Brand accents (decorative / charts)
purple `#4c2c92`, bright-purple `#912b88`, pink `#d53880`, turquoise `#28a197`,
orange `#d96e00`, brown `#b58840`.

### Buttons
- Primary: bg `#126dff`, text white; active `#072c66`; disabled `#9e9e9e`.
- Success: green bg · Warning: red bg.
- Secondary: bg `#eff5fe`, text/border blue.

## Typography
Source: `settings/_typography-*.scss`, `core/_typography.scss`.

- **Family**: `Source Sans 3` (fallback `arial, sans-serif`).
- **Weights**: 400 regular, 700 bold, 900 black.
- **Type scale** — active map is the *legacy* one (`$govuk-new-typography-scale`
  defaults `false`); `size/line-height` in px, mobile → tablet+:

| Style | Mobile | Tablet+ | Weight |
|---|---|---|---|
| Display (80) | 53/55 | 80/80 | — |
| Heading XL (48) | 32/35 | 48/50 | 900 |
| Heading L (36) | 24/25 | 36/40 | 700 |
| Heading M (24) | 18/20 | 24/30 | 700 |
| Heading S (20) | 19/24 | 20/26 | 700 (ls 0.15px) |
| Body default (19) | 16/24 | 19/28 | 400 |
| Small (16) | 14/16 | 16/20 | — |
| Smaller (14) | 12/15 | 14/20 | — |

**Font is bundled in the `sk` source set** (OFL permits embedding unmodified fonts in
apps): the variable TTF is at `resources-logic/src/sk/res/font/source_sans_3.ttf` (one
file spans ExtraLight→Black, so 400/700/900), with the license shipped alongside it at
`resources-logic/src/sk/assets/fonts/OFL-SourceSans3.txt`. Both live in `sk` (not `main`)
so the font ships only in the `sk` flavor that uses it, and always together with its
license. Source Sans 3 carries
the Reserved Font Name "Source" — only relevant if the font is *modified* (it is not).
Compose can load the variable font directly via `FontVariation.Settings`. TODO: surface
the OFL notice in an in-app open-source-licenses list.

## Spacing
`settings/_spacing.scss` — 5px base scale:
`0, 5, 10, 15, 20, 25, 30, 40, 50, 60` px (points 4–9 grow on tablet).

## Shape / radius
No central radius token. From components:
- Buttons / inputs / selects: **5px**
- Textarea: 4px
Borders (`settings/_measurements.scss`): standard 5px, narrow 4px,
form-element 2px, **focus outline 3px**.

## Elevation
IDSK-specific (`helpers/_shadows.scss`), shadow color `#bdbdbd`, `x y blur`:
small `0 4 8`, medium `0 12 32`, large `0 24 40`, head `0 10 20 -10`,
dialog `0 -8 44 -10`.

## Breakpoints
mobile 320 / tablet 641 / desktop 769 px; page width 1440, gutter 30
(mostly irrelevant for the phone app).

## Icons
ID SK uses **Google Material Icons — Filled** (Apache-2.0). The app's icons are otherwise
bespoke EUDI vector drawables. For the `sk` flavor we swap only the **standard 24×24 dp**
UI icons (plus `ic_home`, which is 25×24) for their Material equivalents, and keep the
larger / illustrative / brand icons as the current custom drawables.

Implementation is a **resource override in the `sk` source set** — no code change: each
Material icon is shipped as an Android vector drawable at
`resources-logic/src/sk/res/drawable/<original_name>.xml`, reusing the original resource
name so it overrides `main/res/drawable/<name>.xml` for the `sk` flavor only. `AppIcons.kt`
and every `R.drawable.ic_*` reference are untouched. Colour comes from the theme tint that
`WrapIcon` applies (Material3 `Icon(tint=…)`), so each vector's `fillColor` is a neutral
placeholder. Licensing for the bundled Material icons is Apache-2.0 (noted per-file).

The 31 swapped icons (`original → Material Filled`):

| Original | Material | Original | Material |
|---|---|---|---|
| `ic_add` | `add` | `ic_menu` | `menu` |
| `ic_batch_issuance_counter` | `numbers` | `ic_more` | `more_vert` |
| `ic_bookmark` | `bookmark_border` | `ic_notifications` | `notifications_active` |
| `ic_bookmark_filled` | `bookmark` | `ic_open_in_browser` | `open_in_browser` |
| `ic_certified` | `verified_user` | `ic_open_new` | `open_in_new` |
| `ic_change_pin` | `pin` | `ic_qr_scanner` | `qr_code_scanner` |
| `ic_clock_timer` | `watch_later` | `ic_search` | `search` |
| `ic_delete` | `delete` | `ic_settings` | `settings` |
| `ic_documents` | `text_snippet` | `ic_sign_document` | `draw` |
| `ic_download` | `download` | `ic_touch_id` | `fingerprint` |
| `ic_edit` | `edit` | `ic_transactions` | `history` |
| `ic_error` | `error` | `ic_verified` | `verified` |
| `ic_export` | `save_alt` | `ic_visibility_off` | `visibility_off` |
| `ic_filters` | `filter_list` | `ic_visibility_on` | `visibility` |
| `ic_home` | `home` | `ic_warning` | `warning` |
| `ic_info` | `info` | | |

Kept as the current custom drawables (not swapped): the larger/illustrative icons
(`ic_add_document_from_*`, `ic_present_document_*`, `ic_sign_document_from_*`,
`ic_authenticate_id_cards`, `ic_contract`, `ic_id`, `ic_id_stroke`, `ic_in_progress`,
`ic_wallet_activated`, `ic_wallet_secured`, `ic_qr`, `ic_user`, `ic_message`, `ic_nfc`,
`ic_handle_bar`, `ic_success`, `ic_check`) and the brand logos (`ic_logo_icon`,
`ic_logo_icon_and_text`).

## Localization
The UI is fully string-resourced — every displayed string comes from
`stringResource(R.string.…)` (Compose) or `resourceProvider.getString(…)` (ViewModels);
an audit found **no hardcoded user-facing strings**. The default (English) source is
`resources-logic/src/main/res/values/strings.xml` (342 `<string>` + 1 `<plurals>`).

The `sk` flavor ships **Slovak and Hungarian** (the latter for the Hungarian minority in
Slovakia) as **sk-source-set** locale resources — they are *not* app-wide:
- `resources-logic/src/sk/res/values-sk/strings.xml`
- `resources-logic/src/sk/res/values-hu/strings.xml`

Behaviour: in the `sk` build the UI switches to Slovak on a Slovak-locale device and
Hungarian on a Hungarian-locale device; any other locale falls back to the default
English. dev/demo/local are unaffected (English only). Both files keep 1:1 parity with the
source (same `name`s, `@string/` aliases and format specifiers preserved). Plural
categories follow CLDR: Slovak `one/few/many/other`, Hungarian `one/other`.

Glossary choices (confirmed with the maintainer): *relying party* → SK **overujúca strana**,
HU **ellenőrző fél**; *credential (offer)* → SK **ponuka osvedčenia**, HU **igazolvány-ajánlat**;
*Success* → SK **Hotovo**, HU **Kész**; `EUDI Wallet`, `PID`, `PIN`, `QR`, `NFC` kept as-is.

**Not covered by `strings.xml`** (localize separately if desired):
- **App name** — a manifest placeholder (`${appName}` → "EUDI Wallet SK"), not a string
  resource. For a localized name, convert it to a string resource (see the Typography section
  above and `wiki/THEMING.md`).
- **RQES signing sub-SDK** — carries its own ~35 strings (`LocalizableKey` enum), separate
  from `strings.xml`. Localized for `sk` via `EudiRQESUiConfig.translations` in
  `business-logic/src/sk/.../RQESConfigImpl.kt` (Slovak + Hungarian maps). The SDK selects by
  device language (`Locale.getDefault().language`) and falls back per-key to English.
- **Credential/API metadata** (document types, claim/attribute labels, issuer names) — localized
  server-side, not in `strings.xml`.

To add another language: drop a `resources-logic/src/sk/res/values-<lang>/strings.xml`
mirroring the source (or `src/main/res/values-<lang>/` to make it app-wide).

## Mapping to our Compose theme
Theme lives in `resources-logic/src/main/java/eu/europa/ec/resourceslogic/theme/`:
- `theme/values/ThemeColors.kt` ← applied roles + semantic families (+ derived dark set).
- `theme/values/ThemeTypography.kt` ← Source Sans 3 + type scale.
- `theme/values/ThemeShapes.kt` ← 5px control radius.

Open decisions before wiring:
1. **Font delivery**: bundled OFL TTF (reliable offline) vs Downloadable Google Fonts.
2. **SK-only vs app-wide**: apply to the `sk` flavor only, or replace the shared theme.
3. Which type-scale column (mobile vs tablet) to use as the phone baseline.

## Dark theme (derived)

ID SK publishes no dark palette, so this one is derived from the light tokens
using Material 3 dark-theme practices and validated for WCAG AA contrast.

**Method** (per [M3](https://m3.material.io/styles/color/roles) /
[tone-based surfaces](https://m3.material.io/blog/tone-based-surface-color-m3) /
[Android dark theme](https://m3.material.io/blog/android-dark-theme-tutorial)):
- Accents are **lightened + desaturated** (M3 tonal swap: main tone 40→80,
  container tone 90→30, on-colors 100→20 / 10→90) — never the saturated light
  brand color on a dark surface (it vibrates and fails contrast).
- Surfaces use a **dark neutral (~tone 6, not pure `#000000`)**; elevation is
  shown with progressively **lighter** surface tones, not shadows.
- Text uses off-white **on-surface** tones, not pure white.
- **Contrast validated**: every text pair ≥ 4.5:1, every UI/border/focus pair
  ≥ 3:1 (WCAG 2.1 AA). Measured margins are comfortable (text ≥ 7.7:1).

### Neutrals / surfaces

| Role | Light | Dark |
|---|---|---|
| Background / surface | `#ffffff` | `#131316` |
| Surface container low | `#f5f5f5` | `#1b1b1f` |
| Surface container | `#eeeeee` | `#1f1f23` |
| Surface container high | `#e0e0e0` | `#2a2a2e` |
| Surface container highest | `#bdbdbd` | `#343539` |
| Text (on-surface) | `#000000` | `#e5e2e6` |
| Text secondary (on-surface-variant) | `#757575` | `#c6c6d0` |
| Border / outline | `#b1b4b6` | `#909099` |
| Outline variant | `#e0e0e0` | `#44474e` |

### Brand / primary + accents

| Role | Light | Dark |
|---|---|---|
| Primary | `#126dff` | `#a6c8ff` |
| On-primary | `#ffffff` | `#00315c` |
| Primary container | `#eff5fe` | `#1a468c` |
| On-primary container | `#072c66` | `#d5e3ff` |
| Focus | `#d96e00` | `#ffb871` |
| Link | `#126dff` | `#a6c8ff` |
| Link visited | `#4c2c92` | `#d0bcff` |

### Semantic (base / on / container / on-container)

| Family | Light base | Dark base | Dark on | Dark container | Dark on-container |
|---|---|---|---|---|---|
| Info (blue) | `#126dff` | `#a6c8ff` | `#00315c` | `#1a468c` | `#d5e3ff` |
| Error (red) | `#c3112b` | `#ffb4ab` | `#690005` | `#93000a` | `#ffdad6` |
| Success (green) | `#078814` | `#7fd98a` | `#00390b` | `#005313` | `#9bf6a4` |
| Warning (amber) | `#bd730c` | `#f5be6b` | `#422c00` | `#5e4200` | `#ffddb0` |

> These dark values are a derivation, not an official ID SK spec. Revisit if ID SK
> later publishes a dark palette. A visual light/dark swatch accompanies this doc.

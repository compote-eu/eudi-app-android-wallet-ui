# Exporter requirements from the M5c token shell (for week-0 batch 3)

Status: written at M5c (OR-2), PROVISIONAL. Source of the values: spec repo `dvm-edoklady-eudiw-analysis`, commit
`ec5e58e3def93cfbd3ba846005ccd3e2c13f53ba`, `ux/library/tokens/`, `ux/product/theme/{edoklady,eudiw_v1}/`,
`ux/product/theme/resolver.json`. The runtime shell is `ux-theme/src/commonMain/kotlin/eu/europa/ec/ux/theme/UxTokens.kt`
(types) and `UxBrand.kt`; the hand-written PROVISIONAL values it is filled with are `.../provisional/ProvisionalEudiwV1.kt`.
The exporter must emit objects that fill exactly this shell, so `ProvisionalEudiwV1.kt` can be deleted at the M6 sync.

## 1. Modes (resolver modifier `mode`)

One `UxTokenValues` object per **brand x mode**, each a fully resolved `UxTokens`:

| Resolver mode | `UxMode` | Wire name in the bundle (`theme.default_mode`) | Required? | Fallback when the brand has no sys file |
|---|---|---|---|---|
| `light` | `Light` | `light` | required | (none) |
| `high-contrast` | `HighContrast` | `high-contrast` | required | (none) |
| `dark` | `Dark` | `dark` | optional | `light` |
| `dark-high-contrast` | `DarkHighContrast` | `dark-high-contrast` | optional | `high-contrast` |

`UxBrandConfig` (generated per brand) must pass `light` and `highContrast` always, `dark` and `darkHighContrast` only when
`product/theme/<brand>/sys.<mode>.tokens.json` exists; the shell applies the fallback column. The runtime maps the wire
names with `wireEnum` (hyphen- and case-insensitive, an unknown mode name is an error).

Why all four, not light only: eudiw_v1 binds all four, and the values differ in more than colour. Measured on the commit
above (resolved, eudiw_v1): high-contrast changes 31 `sys.color`, 8 `sys.elevation`, `sys.font.weight.status`
(600 to 700), `sys.type.status.size` (13 to 14) and `.line` (16 to 20), and 234 keys in all; dark 299; dark-high-contrast 328.

## 2. Resolution: the exporter emits RESOLVED values per mode, never aliases

The `comp.*` tier is aliases onto `sys.*` (`comp.app-bar.shadow = {sys.elevation.bar}`), so a comp value changes with the
mode (high contrast: `comp.screen.card-shadow`, `comp.text.link-fg`, ...). The exporter therefore resolves the whole
theme once per (brand, mode) and writes the result: files in order = `sets` sources (`library/tokens/comp.tokens.json`,
`comp.anatomy.tokens.json`), then the brand context (`product/theme/edoklady/ref.tokens.json`, `.../eudiw_v1/ref.tokens.json`,
`.../edoklady/comp.tokens.json`, `.../eudiw_v1/comp.tokens.json`), then `product/theme/<brand>/sys.<mode>.tokens.json`;
later files win; follow `{a.b.c}` aliases to a literal. A reference implementation (Python, read-only on the spec repo)
is `spike-evidence/M5c/tools/resolve-theme.py`; it resolved 940 keys per mode.

## 3. Token groups the shell consumes (must be emitted)

All per mode. Units: `px` becomes `Dp` (`sp` for font size and line height); hex colours become `Color(0xAARRGGBB)`
(alpha from the DTCG `alpha`, or from `hex@alpha` after resolution).

| Shell type | Spec tokens | Notes |
|---|---|---|
| `UxColors` (25 roles) | `sys.color.text.{primary,secondary,inverse,link,heading,label,on-brand,on-app-bar}`, `surface.{default,subtle,inverse,raised,app-bar,page,card}`, `overlay.scrim`, `border.{default,inverse}`, `action.{primary,secondary,floating}.{bg,fg}`, `focus.ring` | |
| `UxFeedbackColors` x 4 | `sys.color.feedback.{info,success,warning,error}.{bg,border,fg,ring}` | |
| `UxTypography` (10 styles) | `sys.type.{title,headline,section,body,small,caption,display,appbar,status}.{size,line}` (button reuses `body`) and the weight and family of each style (below) | `sys.type.status.*` differs in high contrast |
| `UxSpacing` | `sys.space.{xs,s,m,section,gutter}` | |
| `UxRadii` | `sys.radius.{control,card,sheet,pill,floating}` | |
| `UxSizes` | `sys.size.{target,icon.md,icon.sm}`, `ref.size.focus-ring` | `glyphHero` is NOT IN SPEC (section 5) |
| **`UxElevations`** (new) | `sys.elevation.{action,bar,card,floating,floating-action,menu,raised,sheet}` | each is an array of layers `{color(+alpha), offsetX, offsetY, blur, spread, inset}`, 1 to 2 layers, `spread` may be negative, `inset` true for the card ring in light/dark; per mode (8 differ in high contrast) |
| **`UxFonts`** (new) | `sys.font.family.{base,heading}` (a font **stack**, `ref.font.family.sans`); `sys.font.weight.{regular,label,semibold,bold,value,status}` | the shell holds a `FontFamily`; the webfont is not bundled, so a stack must be mapped to a platform family or bundled fonts (asset question, section 6) |
| **`UxComponentTokens`** (new), subset | `comp.app-bar.{min-height,badge-size,badge-offset,weight,shadow}`; `comp.button.{font-weight,icon-shadow,floating-shadow}`; `comp.link.{gap,weight}`; `comp.sheet.{shadow,scrim}`; `comp.rp-identity.{name-weight,name-unverified-weight}`; `comp.attribute-list.value-weight`; and the style weights `comp.text.{title,heading,body,display}-weight`, `comp.status-hero.title-weight` | `comp.*.shadow` values are the resolved `sys.elevation.*` they alias |

Style to weight mapping used by the shell (the exporter must keep it, or the spec must name a `sys.type.*.weight`):
title `comp.text.title-weight`, headline `comp.status-hero.title-weight`, section `comp.text.heading-weight`, body
`comp.text.body-weight`, small and caption `sys.font.weight.regular`, display `comp.text.display-weight`, appbar
`comp.app-bar.weight`, button `comp.button.font-weight`, status `sys.font.weight.status`. Family: title and headline use
`sys.font.family.heading`, the others `sys.font.family.base` (`comp.text.title-font-family`).

## 4. What the shell does not consume yet (recommended to emit anyway)

The shell takes a subset of `comp.*` because the nine elements and four templates read only that much. To avoid a shell
change per element refinement, the exporter should emit **every `comp.<part>.*` of the parts in scope** as typed groups,
one data class per part, named after the part: `text`, `button`, `status-hero`, `notice`, `link`, `app-bar`,
`rp-identity`, `attribute-list`, `attribute-item`, `sheet`. In the resolved eudiw_v1 theme (commit above) that is 227 keys
per mode (text 22, button 28, status-hero 20, notice 28, link 11, app-bar 17, rp-identity 40, attribute-list 16,
attribute-item 35, sheet 10). The library has no `comp.qr-viewfinder.*`, `comp.scan.*` or template comp groups (section
5). The elements then migrate from the `sys.*` roles they use today to the `comp.*` the library YAML names in each
element's `tokens:` block.

## 5. Gaps: values the elements need that the spec has no token for (NOT IN SPEC)

Hard-coded in the elements before M5c, now named in the shell and filled with provisional constants. The spec (or the
brand config) must define them, or the exporter must carry them as brand constants:

| Shell field | Provisional value | Used by | Suggested spec name |
|---|---|---|---|
| `UxEffects.disabledAlpha` | 0.4 | `EL-button` disabled | `sys.opacity.disabled` |
| `UxEffects.scrimAlpha` | 0.4 | `TPL-sheet` backdrop (colour is `comp.sheet.scrim`, no alpha in the spec) | `sys.opacity.scrim` or an alpha in `sys.color.overlay.scrim` |
| `UxEffects.dimmedAlpha` | 0.35 | `EL-qr-viewfinder scanning=false` | `sys.opacity.dimmed` |
| `UxEffects.hairline` | 1 dp | card and notice borders | `sys.size.hairline` |
| `UxScanTokens.cameraMinHeight` | 240 dp | `TPL-scan` camera region | `comp.scan.camera-min-height` |
| `UxQrViewfinderTokens.strokeWidth`, `armRatio` | 6 dp, 5 | `EL-qr-viewfinder` corner marks | `comp.qr-viewfinder.stroke-width`, `.arm-ratio` |
| `UxSizes.glyphHero` | 72 dp | `EL-status-hero` glyph circle (the spec has 96 for the circle and 52 for the icon) | decide which spec size the element should use |

## 6. Findings for the exporter and the spec (not decided here)

1. **Elevation is approximated, not exact.** Compose has no `box-shadow`. `UxShadowLayer` keeps all six DTCG fields, but
   the runtime draws a blurred layer as the platform shadow of an elevation of half the blur (offsets and negative
   spread ignored); rings (blur 0) and inset rings are exact. The exporter should keep all fields so a better renderer
   needs no re-export.
2. **Font family is a stack.** `ref.font.family.sans` is `["Source Sans 3", "Source Sans Pro", "-apple-system", ...]`.
   The exporter has to decide the platform mapping; the font files themselves are an assets question (declared gap).
3. **`px` is read as `dp`** (and `sp` for type). Say so in the spec if it is intended; no unit conversion exists.
4. **App bar height.** The element was `target + 2 x space.xs` = 58 dp high; `comp.app-bar.min-height` is 64 px, which the
   shell now follows, so screens got slightly taller bars. A spec-side confirmation is cheap. (`comp.app-bar.padding-inline`
   resolves to 20 px in eudiw_v1 through the brand override; the element still uses `space.xs`, an open mismatch.)
5. **`sys.type.button` does not exist**; the shell builds the button style from `sys.type.body` size and line and
   `comp.button.font-weight`. Add `sys.type.button.*` or keep this rule.
6. **Dark mode, icons/assets and motion remain declared gaps** (OR-2: out of brief scope). The shell holds the dark
   modes; nothing draws or tests dark in the interpreter beyond the token resolution.
7. **Registry versus library YAML** (M5c test `RegistrySchemaTest`): prop names, enum variants, events, slots and template
   regions match, apart from six listed differences: `EL-button.popup_state_label` (renderer-owned, M5 deviation 5), the
   record fields `id`, `label_copy`, `copyable`, `media` of `EL-attribute-list.items` (boundary 1.7), and the library's
   per-item `requirement_label`, which the registry does not draw (list-level labels are used). The exporter should emit
   node props only from the YAML prop names; `popup_state_label` is a renderer-side extra.

## 7. Acceptance check for the exporter output

- `spike-gate.sh` step `style-guard` stays green (no literal reaches the elements).
- `UxTokensShellTest` (modes, fallbacks, elevation, fonts, `comp.*`) passes on the generated objects, with the expected
  literals re-pointed at the generated values.
- `ProvisionalEudiwV1.kt` is deleted and `git grep PROVISIONAL -- ux-theme` is clean.

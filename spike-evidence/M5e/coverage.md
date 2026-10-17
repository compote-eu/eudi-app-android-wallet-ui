# M5e: Home parts (OR-8), probe from the export

Screens: `shots/<ui state>-<brand>-<light|high-contrast>.png` (56, iPhone 17 simulator, iOS 27.0, Slovak locale), `shots/parts-*.png`
(gallery page `gallery-parts[.brand][.hc]`: the switch, the trust mark, chips and a mini card, which the probe never draws).
Spec reference: `spec-ref/<ui state>-<brand>-<mode>.png`, the library's own renderer (`ux/build/screens/SCR-home--<ui state>.html`,
Chrome headless, `?brand=&mode=`), made by `spec-ref/render-spec-ref.sh`. 14 ui states x 2 brands x 2 modes each.
Tools: `tools/shoot.sh`, `tools/shoot-brand.sh` (script grammar `gallery-home[-<ui state>][.<brand>][.hc]`).

## Visible differences from the spec reference (eudiw_v1, all ui states alike)
1. Card title: the reference draws it upper case with wide letter spacing (`OBČIANSKY PREUKAZ`, number `E A 0 0 0`); the app draws it as written, no
   letter spacing. Neither the case nor `letter-spacing: .12em` is a token or an element prop (CSS only), so nothing is drawn (style guard: no value in Kotlin).
2. Font: the webfont (Source Sans 3) is not bundled (M5 deviation 4); the platform face is wider, so wrapping differs (notice titles, links).
3. Links are drawn with the library's chevron (EL-link marker) and regular weight; the reference's heading-row link is bold, underlined, without chevron.
4. The frosted band and PID gradient are approximated: band opaque (88 % alpha + blur not drawn), PID ground a plain gradient to the accent.
5. Notice titles are SemiBold in the app, Bold in the reference; the inset ring of the notice is a hairline in the border colour in both.
6. Status bar: simulator's own; the reference draws a mock one. The band behind it follows the app bar colour as in the reference.
7. Floating bar: shadow is the platform shadow of half the blur (no offset), a little softer than the reference.
8. The bottom of the scroll: the app reserves the actions' height below the content; the reference lets the last card slide under the bar.

## idsk (reference shows the full idsk screen; the probe carries the default brand's screen)
9. **The probe holds only the default brand's gating.** `brands` is `["eudiw_v1"]`, and the nodes the default profile turns off (`switch`,
   `trustmark`) or replaces (`present`) are dropped from `screen`. For idsk the reference shows the storage switch, the trust-mark entry and the
   `Predložiť eDoklady` button; the app cannot draw them from the probe (the nodes are not in it), and the bottom bar is dropped by the idsk
   `bottom-nav` variant (library `omits`). The three parts are therefore evidenced by `parts-*.png` and the unit tests only.
   Exporter requirement: emit the unfiltered node list with per-brand `hidden` for every exported brand.
10. idsk binds no card artwork, no `icon.check-circle`, `icon.cloud`, `icon.smartphone`, `icon.add`, `icon.icon-id-card`, `icon.icon-case` (ux-assets.json): the
    cards are the document colour only (the reference draws the same flat colour) and the status chip and storage line carry no glyph where none is bound (DEC-080).
11. `trust-mark.colour` is bound for no brand in `ux-assets.json` (the library says every brand); the trust mark draws its framed placeholder.

## Parts coverage (10 parts + implicit chip): present 10 of 10, KNOWN_GAPS empty. Not drawn, declared: card `synthetic` art emblem, `mini` rows grouped into one
card (CAN-086), the disabled chip's dashed outline, `label-visible` chips peek/scroll-padding, dark modes (no export).

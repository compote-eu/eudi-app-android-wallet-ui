# Maestro test-porting status

Tracks the port of `eudi-app-ios-wallet-ui`'s Maestro test catalog (copied into `.maestro/` as
unverified reference material — see that directory's own README and the warning banner on every
copied file) to this KMP app's actual shared UI. See `wiki/maestro-testing.md` for the full
background, setup, and the cross-platform selector finding this porting effort is built on.

## Status legend

- **Not started** — reference file exists under `.maestro/<category>/`; nothing built or run yet
  against this app.
- **Ported, verified working** — a flow exists under `.maestro/kmp/<category>/`, has been run
  against this app's real screens on both platforms, and passes end to end.
- **Ported, partially verified** — a flow exists under `.maestro/kmp/<category>/` and some steps
  have been confirmed against real screens/hierarchy dumps, but it does not yet pass end to end
  (blocked, or simply not finished).
- **Ported, blocked by app bug** — partially verified, and the reason it doesn't complete is a
  real bug in this app or its backend (not a test-authoring gap) — linked below.
- **Not applicable to this app** — the original test case doesn't map to anything this app does
  (a platform-specific mechanism the KMP app doesn't have, a feature not implemented here, etc.).

## Status

| TC | Description | Status |
|---|---|---|
| TC-01 | PID issuance happy path | **Ported, blocked by app bug** — see [Known active blockers](../wiki/maestro-testing.md#known-active-blockers) in the wiki. In-app portion (PIN → Documents → Add → catalog) verified working, unmodified, on both iOS and Android: `.maestro/kmp/issuance/tc-01-pid-issuance-common.yaml`. Android continuation into the identity-proofing form reaches "Authorize" then fails at token exchange: `.maestro/kmp/issuance/tc-01-pid-issuance-android-continuation.yaml`. iOS blocked earlier (never reaches the form at all). Reference file `.maestro/issuance/tc-01-pid-issuance.yaml` also had its ids statically corrected 2026-09-28, not live-verified. |
| TC-02 | BLE proximity presentation, custom attribute selection (PID) | Not started |
| TC-03 | BLE proximity presentation, full PID | Not started |
| TC-04 | BLE proximity presentation, full PID requested but partial share | Not started |
| TC-05 | BLE disabled before attempting to share | Not started |
| TC-07 | Zero attribute selection on the consent screen | Not started |
| TC-13 | BLE connection interrupted mid-transfer | Not started |
| TC-14 | App backgrounded during BLE engagement/handshake | Not started |
| TC-17 | Deferred issuance retry-interval violation | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-18 | Cancel an in-progress issuance | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-20 | Duplicate mDL issuance | **Ported, partially verified** — Android verified live 2026-10-01: `.maestro/kmp/issuance/tc-20-mdl-duplicate-android.yaml` (a duplicate becomes a second, separate mDL card; checks exactly one before, exactly two after). Passes individually and in one clean full-chain round (TC-01 -> 22 -> 24 -> 25 -> 50 -> 26 -> 20 -> 30), not yet two. iOS not started. |
| TC-22 | Remote presentation via deep link (OpenID4VP) | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-24 | User rejects a remote presentation request | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-25 | RP requests an attribute/document the wallet doesn't have | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-26 | Present multiple documents at once (PID + mDL) | **Ported, partially verified** — Android verified live 2026-10-01: `.maestro/presentation/tc-26-multi-document.yaml`, fixed in place (incl. the verifier-side check that both claims arrived). Passes individually and in one clean full-chain round, not yet two. iOS not run. |
| TC-30 | Delete a document | **Ported, partially verified** — Android verified live 2026-10-01: `.maestro/kmp/issuance/tc-30-delete-document.yaml` (tagged "Remove from wallet" button at the bottom of Document Details, then the "Remove document?" sheet; replaces the reference's coordinate taps). Passes individually and in one clean full-chain round, not yet two. Platform-neutral steps, but iOS not run. |
| TC-32 | Reinstall wipes state (empty-state UI check) | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-34 | Transaction log completeness (History tab) | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-37 | QES / remote-qualified-signing flow (physical device only, Appium in the reference project, not Maestro) | Not started |
| TC-40 | Repeated incorrect PIN (throttling) | Not started |
| TC-42 | App backgrounded, requires PIN on return | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-43 | Change PIN code | Not started |
| TC-46 | Remote/OpenID4VP presentation: trigger + share + network-loss handling | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-47 | Force-kill mid-flow recovery | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-50 | mDL issuance (no existing PID required) | **Ported, partially verified** — Android: `.maestro/kmp/issuance/tc-50-mdl-issuance-android.yaml`, verified live 2026-10-01, passes two consecutive fresh-wipe TC-01 -> 22 -> 24 -> 25 -> 50 rounds and one full-chain round; in CI. iOS: `.maestro/kmp/issuance/tc-50-mdl-issuance-ios.yaml` passed two consecutive fresh-erase rounds locally on CI's iPhone 16 Pro Max, but is not committed or in CI yet. |
| TC-51 | Duplicate PID issuance | Not started (reference ids statically corrected 2026-09-28, not live-verified) |
| TC-57 | Issuance via a scanned/deep-linked OpenID4VCI Credential Offer | Not started (reference ids statically corrected 2026-09-28, not live-verified) |

## Notes

- TC numbers and descriptions above are taken from the reference project's own file names and
  header comments as of the copy date (2026-09-15) — they describe what the *native* app's test
  suite covers, not a confirmed claim that this app has an equivalent feature. Several will likely
  turn out to be **Not applicable to this app** once actually attempted (e.g. TC-37's physical-
  device-only QES flow, or anything relying on an iOS-specific mechanism this KMP app's Android
  side has no counterpart for) — that determination is deliberately left to whoever ports each one,
  not assumed here.
- This table is intentionally flat/manual rather than generated — update it by hand as each TC is
  actually attempted, in the same commit as the flow file(s) that change its status.
- 2026-09-28: a bulk, mechanical pass corrected 7 distinct wrong `id:` selectors (32 occurrences
  across 17 reference files) based on static cross-referencing against this app's real Compose
  testTags — no simulator/emulator run, so **none of these corrections are live-verified**. Each
  touched file's header now says so explicitly. This does not change any TC's status above from
  "Not started" to "Ported" — a corrected reference file is still just reference material until an
  actual flow is built and run under `.maestro/kmp/`. See the session history for the full
  correction table if needed; it is not reproduced here to keep this file flat.

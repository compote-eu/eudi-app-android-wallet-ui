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
| TC-01 | PID issuance happy path | **Ported, blocked by app bug** — see [Known active blockers](../wiki/maestro-testing.md#known-active-blockers) in the wiki. In-app portion (PIN → Documents → Add → catalog) verified working, unmodified, on both iOS and Android: `.maestro/kmp/issuance/tc-01-pid-issuance-common.yaml`. Android continuation into the identity-proofing form reaches "Authorize" then fails at token exchange: `.maestro/kmp/issuance/tc-01-pid-issuance-android-continuation.yaml`. iOS blocked earlier (never reaches the form at all). |
| TC-02 | BLE proximity presentation, custom attribute selection (PID) | Not started |
| TC-03 | BLE proximity presentation, full PID | Not started |
| TC-04 | BLE proximity presentation, full PID requested but partial share | Not started |
| TC-05 | BLE disabled before attempting to share | Not started |
| TC-07 | Zero attribute selection on the consent screen | Not started |
| TC-13 | BLE connection interrupted mid-transfer | Not started |
| TC-14 | App backgrounded during BLE engagement/handshake | Not started |
| TC-17 | Deferred issuance retry-interval violation | Not started |
| TC-18 | Cancel an in-progress issuance | Not started |
| TC-20 | Duplicate mDL issuance | Not started |
| TC-22 | Remote presentation via deep link (OpenID4VP) | Not started |
| TC-24 | User rejects a remote presentation request | Not started |
| TC-25 | RP requests an attribute/document the wallet doesn't have | Not started |
| TC-26 | Present multiple documents at once (PID + mDL) | Not started |
| TC-30 | Delete a document | Not started |
| TC-32 | Reinstall wipes state (empty-state UI check) | Not started |
| TC-34 | Transaction log completeness (History tab) | Not started |
| TC-37 | QES / remote-qualified-signing flow (physical device only, Appium in the reference project, not Maestro) | Not started |
| TC-40 | Repeated incorrect PIN (throttling) | Not started |
| TC-42 | App backgrounded, requires PIN on return | Not started |
| TC-43 | Change PIN code | Not started |
| TC-46 | Remote/OpenID4VP presentation: trigger + share + network-loss handling | Not started |
| TC-47 | Force-kill mid-flow recovery | Not started |
| TC-50 | mDL issuance (no existing PID required) | Not started |
| TC-51 | Duplicate PID issuance | Not started |
| TC-57 | Issuance via a scanned/deep-linked OpenID4VCI Credential Offer | Not started |

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

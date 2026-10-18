> ⚠️ **PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,**
> navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
> attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.

# Maestro flows

## Layout

```
.maestro/
├── issuance/
│   ├── README.md                            — TC-54 (pending-record TTL gap) + driver-crash findings
│   ├── tc-01-pid-issuance.yaml              — bootstrap: PIN setup + PID issuance
│   ├── tc-17-deferred-retry-timing.yaml     — triggers a deferred mDL issuance (used by tc-17-verify-interval-violation.sh)
│   ├── tc-17-verify-interval-violation.sh   — confirms the app ignores the issuer's requested retry interval (known bug, non-blocking CI job)
│   ├── tc-18-cancel-issuance.yaml           — cancel in-progress issuance (confirmed UX bug, see README.md)
│   ├── tc-20-mdl-duplicate.yaml             — duplicate mDL issuance (runFlow's TC-50 once more)
│   ├── tc-30-delete-document.yaml           — delete a document (assumes TC-01→TC-50→TC-20 already ran)
│   ├── tc-50-mdl-issuance.yaml              — mDL issuance (assumes TC-01 already ran)
│   └── tc-51-pid-duplicate.yaml             — duplicate PID issuance; NOT wired into CI, standalone verified flow (same treatment as TC-46/TC-49)
├── presentation/
│   ├── README.md                                 — TC-22/46 notes + TC-23 (Universal Link)/TC-53 (QR scan) N/A findings
│   ├── tc-22-remote-presentation.yaml            — remote OpenID4VP presentation via deep link
│   ├── tc-22-setup-presentation.js               — fetches a fresh request from the live verifier
│   ├── tc-22-verify-server-receipt.js            — confirms the verifier actually received the share
│   ├── tc-24-reject-presentation.yaml            — user rejects a presentation request
│   ├── tc-24-verify-no-receipt.js                — confirms the verifier received nothing after rejection
│   ├── tc-25-setup-photoid-request.js            — requests a document type the wallet holds none of
│   ├── tc-25-unsatisfiable-request.yaml          — unsatisfiable presentation request
│   ├── tc-25-verify-no-receipt.js                — confirms the verifier received nothing
│   ├── tc-26-multi-document.yaml                 — combined PID + mDL presentation in one transaction
│   ├── tc-26-setup-multi-document-request.js     — requests claims from both PID and mDL
│   ├── tc-26-verify-both-received.js             — confirms the verifier received both documents' claims
│   ├── tc-46-bridge-presentation-url.js          — passes the fetched presentation URL between the two tc-46 flow halves
│   ├── tc-46-finish-pin-offline.yaml             — TC-46 part 2/2: final PIN digit entered while the host is offline
│   ├── tc-46-network-loss-during-share.sh        — orchestrates the two tc-46 flows around a real host-offline window; NOT wired into CI (see its own header) — standalone regression check
│   └── tc-46-setup-and-share.yaml                — TC-46 part 1/2: trigger + Share + 5 of 6 PIN digits
├── proximity/                                    — BLE (ISO 18013-5); real-device only (physical Android verifier + iPhone + camera rig) — structurally can't run against a simulator, see README.md
│   ├── README.md                                 — why iOS here is Appium-driven, not Maestro
│   ├── SECURITY-FINDING-TC-13.md                 — unresolved: iOS can show "successfully shared" while the verifier received zero bytes
│   ├── tc-02-ble-custom-attributes.{sh,yaml}, tc-02-ios-share.py, tc-02-verify-attributes.py             — custom-attribute BLE share
│   ├── tc-03-ble-full-pid.{sh,yaml}, tc-03-ios-share.py, tc-03-verify-attributes.py                      — full-PID BLE share
│   ├── tc-04-ble-full-pid-partial-share.{sh,yaml}, tc-04-ios-partial-share.py, tc-04-verify-attributes.py — partial-attribute BLE share
│   ├── tc-05-ble-disabled.sh, tc-05-ios-ble-disabled.py                                                  — BLE-disabled behavior (no Maestro yaml — Appium/Android-script only)
│   ├── tc-07-ble-zero-selection.{sh,yaml}, tc-07-ios-zero-selection.py, tc-07-verify-no-receipt.py       — zero-attribute selection
│   ├── tc-13-ble-mid-transfer-disconnect.{sh,yaml}, tc-13-ios-interrupt-share.py, tc-13-verify-no-receipt.py — mid-transfer disconnect (see SECURITY-FINDING-TC-13.md)
│   ├── tc-14-ble-background-during-handshake.{sh,yaml}, tc-14-ios-background-during-handshake.py, tc-14-verify-state.py — app backgrounded mid-handshake
│   └── tc-13-evidence/, tc07-forensics-*/, tc14-forensics-*/                                             — one-off investigation screenshots/logs, not part of the reusable flow suite
├── qes/                                          — real-device only (Appium); no Maestro yaml exists here, and none should — see README.md
│   ├── README.md                                 — TC-37/38/39 (QES signing, physical device/Appium) + PoDoFo/malformed-PDF SIGABRT crash finding
│   ├── tc-37-check-fixture.py                    — verifies the test PDF fixture is in place before signing
│   └── tc-37-qes-signing.py                      — Appium flow: sign a document via the dev QTSP, up to the known backend signing limitation
├── resilience/
│   ├── tc-47-force-kill-recovery.sh              — orchestrates the two flows below around a force-kill/relaunch
│   ├── tc-47-force-kill-recovery.yaml            — part 1: get partway into a flow, then get force-killed
│   └── tc-47-verify-recovery.yaml                — part 2: confirm the app recovers cleanly after relaunch
├── security/
│   ├── README.md                                 — TC-21/35/36/37/38/39/41/49 findings (mostly no committed flow — real-device-only or not-automatable; see README.md)
│   ├── tc-40-pin-throttle.yaml                   — PIN lockout after repeated wrong attempts
│   ├── tc-42-background-relock.yaml              — confirms no re-lock-on-backgrounding exists (documented security-posture finding, not a conventional pass/fail check)
│   └── tc-43-change-pin.yaml                     — change PIN
├── storage/
│   ├── check-keychain-count.sh                   — TC-32 helper: queries the Simulator's Keychain row count directly via sqlite3
│   ├── tc-32-reinstall-wipe.sh                   — orchestrates uninstall/reinstall + Keychain verification around the flow below
│   └── tc-32-verify-empty-state.yaml             — the one genuinely-Maestro piece of TC-32: the post-reinstall UI check
└── tc-34-transaction-log.yaml                    — History-tab completeness check (separate
                                                     CI job, not part of the main chain — see below)
```

Flows are grouped into subdirectories by test area: `issuance/`,
`presentation/`, `proximity/`, `qes/`, `resilience/`, `security/`,
`storage/`. `proximity/` and `qes/` are real-device-only (BLE and
Appium/QES respectively) — see each directory's own README for why
they structurally can't run against a simulator.
`tc-34-transaction-log.yaml`
stays at the top level: it depends on the issuance suite's history but isn't
itself an issuance test, and it's wired into CI as its own job
(`transaction-log-check` in `.github/workflows/maestro-simulator.yml`),
deliberately `continue-on-error: true` since it documents a known,
already-confirmed app bug (see that workflow's comments, and audit Topic 19
in `docs/audit/ARF-compliance-audit-2026-07-23.md`) rather than gating on a
regression.

The main chain currently runs, in order, against one long-lived simulator
session (not fresh-installed/erased per flow — later flows assume the
state earlier ones left behind): TC-01 → TC-47 → TC-22 → TC-24 → TC-25 →
TC-50 → TC-26 → TC-20 → TC-30 → TC-40 → TC-32 → TC-18 → TC-42 → TC-43,
followed by TC-34 and TC-17 as separate non-blocking jobs. This list
itself will drift as flows are added — `.github/workflows/maestro-
simulator.yml` is the source of truth for the exact current order; read
it directly rather than trusting a description here.

## Running locally: close Maestro Studio first

If Maestro Studio is open while a `maestro test` CLI invocation runs against
the same simulator, both processes try to manage the same XCUITest driver
session — confirmed to cause the driver being killed out from under the CLI
run mid-flow (`Connection refused` / `Child process terminated with signal
15` in `device-simulator.log`), not a flow or app bug. Quit Maestro Studio
before running any flow here via the CLI:

```
ps aux | grep -i "maestro studio" | grep -v grep | grep -v crashpad
```

should print nothing before you start.

## appId and personal/local signing overrides

Every committed flow (`tc-01`, `tc-50`, `tc-20`, `tc-30`, `tc-34`, and any
future flow) declares `appId: eu.europa.ec.euidi.dev` — this must match
`PRODUCT_BUNDLE_IDENTIFIER` for the "Debug Dev" build configuration in
`EudiReferenceWallet.xcodeproj/project.pbxproj`, which is what CI actually
builds and installs (see `maestro-simulator.yml`'s "Install app" step). Do
not change a committed flow's `appId` to anything else.

If you build "EUDI Wallet Dev" / Debug Dev locally through Xcode with your
own personal `DEVELOPMENT_TEAM`/bundle id override for real-device work
(rather than the shared simulator signing setup), the installed app's
bundle id will no longer match `eu.europa.ec.euidi.dev`, and `maestro test`
will fail to find the app (it looks like a missing-element failure, not an
obviously-wrong-appId failure — the flow's first `tapOn` just times out
against whatever's actually in the foreground). `PRODUCT_BUNDLE_IDENTIFIER`,
`CODE_SIGN_IDENTITY`, `CODE_SIGN_STYLE`, `DEVELOPMENT_TEAM`, and
`PROVISIONING_PROFILE_SPECIFIER` for Debug Dev are all set directly in
`project.pbxproj`'s build settings today (not in an xcconfig), so there is
currently no gitignored-local-file mechanism to override just the bundle id
for personal builds without touching that shared, upstream-tracked file —
deliberately not restructured for this, given the merge-conflict risk on a
shared pbxproj for a benefit that real-device signing work (see below) will
likely revisit anyway.

Until then, when you need to do Maestro work against a personal-bundle-id
local build, pick one:

- Run temporary, uncommitted copies of the flow file(s) with `appId`
  changed to your personal bundle id (this is what was done to seed
  documents during the TC-22 exploration) — never commit these copies.
- Or temporarily revert Xcode's local signing settings back to the
  committed `eu.europa.ec.euidi.dev` / shared signing identity before
  running Maestro flows, then switch back for real-device work.

**Forward reference**: Fáza 4 (real-device BLE CI automation) will need a
real signing certificate + provisioning profile as GitHub Actions secrets
for physical devices, which is a different, more involved signing setup
than today's ad-hoc simulator signing. Bundle-id/signing overrides for
local vs. CI builds should be redesigned properly as part of that work
rather than patched further here.

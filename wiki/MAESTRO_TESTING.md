# Maestro end-to-end testing

> **Status:** current as of 2026-09-15.

Filename note: written as `MAESTRO_TESTING.md` (all-caps-with-underscores) to match every other
file already in this directory (`BRANCHES.md`, `GO_LIVE.md`, `HOW_TO_BUILD.md`, ...) rather than
the lowercase-hyphenated `maestro-testing.md` first suggested — flagging the naming choice
explicitly rather than silently picking one.

## 1. What Maestro is, and why this project uses it

[Maestro](https://maestro.mobile.dev) is a mobile UI-testing tool that drives an app through its
real accessibility tree — the same tree screen readers use — rather than pixel coordinates or a
platform-specific test framework. A flow is a small YAML file naming what to tap, type, and assert
by accessibility id or visible text. This project uses it because this app's UI is Compose
Multiplatform: **one shared UI codebase renders on both Android and iOS**, and Maestro is one of
the few tools that can drive both from the same tool and (as §5 below covers) very nearly the same
flow file, rather than needing one test framework per platform.

## 2. Prerequisites and installation

Don't assume any of this is already set up — install each piece explicitly.

**Maestro CLI** (requires a JDK, 11+; this machine has JDK 17 already):
```bash
curl -Ls "https://get.maestro.mobile.dev" | bash
```
This installs to `~/.maestro/bin`. Make sure it's on `PATH` (add to your shell profile if not
already there):
```bash
export PATH="$HOME/.maestro/bin:$PATH"
```
Confirm:
```bash
maestro --version
```

**iOS: Xcode + a Simulator.** Xcode's command-line tools must be installed (`xcode-select -p` to
check). List available simulator runtimes/devices:
```bash
xcrun simctl list devices available
```
If nothing suitable exists, create one in Xcode (Window → Devices and Simulators) or via
`xcrun simctl create`. Maestro talks to whichever simulator is booted — boot one explicitly before
running a flow:
```bash
xcrun simctl boot <device-udid>
open -a Simulator --args -CurrentDeviceUDID <device-udid>
```

**Android: Android Studio + an emulator.** Install Android Studio, then create an AVD either
through its Device Manager UI or via the SDK command-line tools:
```bash
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n <name> -k "<system-image-id>"
$ANDROID_HOME/emulator/emulator -avd <name>
```
`adb` (from `$ANDROID_HOME/platform-tools`) must be on `PATH`. Confirm a device/emulator is visible
and unlocked before running anything:
```bash
adb devices
```
If a **physical device** shows up here, don't assume it's usable — a locked personal phone will
silently fail auth-gated steps. Prefer an emulator for routine flow development.

## 3. Critical rule: Maestro Studio and the CLI cannot share a device session

**Close Maestro Studio (`maestro studio`) completely before running any `maestro test` or
`maestro hierarchy` CLI command against the same simulator/emulator.** Studio holds a persistent
driver session on the device; a concurrent CLI invocation contends for that same session and can
kill the CLI run mid-flow. (This exact failure mode is a confirmed, previously-diagnosed root
cause of SIGTERM failures in the sibling native-iOS EUDI wallet project's own Maestro suite —
treat it as equally applicable here, since it's a property of Maestro's driver model, not of
either app.)

Check for a lingering Studio (or any other Maestro CLI) process before starting a CLI run:
```bash
ps aux | grep -i maestro | grep -v grep
```
If anything shows up, close Studio's browser tab and/or kill that process first.

## 4. How to run tests

Two ways to invoke `maestro test`, depending on how much you want to run.

### Option A — run a single flow

```bash
maestro test .maestro/kmp/issuance/tc-01-pid-issuance-common.yaml
```
(or whichever exact filename applies — file names throughout `.maestro/` are literal, not a
placeholder pattern; check `.maestro/PORTING_STATUS.md` for which one you actually want.)

This is the right choice while a flow is still being built/debugged, or when you specifically want
to know whether one particular test case works. A one-shot accessibility-tree dump helps here —
confirm the real selector before writing a step into the flow, don't guess:
```bash
maestro hierarchy
```

**The current chain.** As of this writing there is no single passing end-to-end chain for TC-01 —
see §6/§7. The closest thing today is running the two files under `.maestro/kmp/issuance/` back to
back by hand:
```bash
maestro test .maestro/kmp/issuance/tc-01-pid-issuance-common.yaml
maestro test .maestro/kmp/issuance/tc-01-pid-issuance-android-continuation.yaml   # Android only
```
Once a flow reliably passes end to end, chain its parts with Maestro's own `runFlow:` step
(the reference catalog already does this — see `.maestro/issuance/formeu-identity-proofing-flow.yaml`
and where `tc-01-pid-issuance.yaml` calls it) rather than running files manually.

### Option B — run everything in a directory, sequentially

Point `maestro test` at a directory instead of a file, and it runs every `.yaml` file in that
folder, one after another, alphabetically:
```bash
maestro test .maestro/kmp/issuance/
```
Point it at `.maestro/` itself and it runs **everything** under it, across every subfolder:
```bash
maestro test .maestro/
```

### Important context before choosing

**Most flows under `.maestro/` right now are bulk-copied reference material from the original
native iOS project** (see §6), and every one of those files carries an explicit "unverified"
banner in its own header comment. Running Option B against the whole `.maestro/` directory today
will surface many *expected* failures from those unverified files — that is not the same thing as
a real bug in this KMP app.

**Check `.maestro/PORTING_STATUS.md` first** to see which specific flows are actually confirmed
working against this app, and prefer running just those individually (Option A) until more flows
are ported and verified. Once that list has grown, running the full directory (Option B) becomes a
genuinely meaningful regression suite rather than a wall of predictable, already-known failures.

## 5. The key finding: one flow file, two platforms

The single most valuable, non-obvious thing confirmed this week: **an `id:` selector in a Maestro
flow matches identically on iOS and Android for this app, with zero flow-file changes, because
Compose Multiplatform renders both platforms from the same semantics tree.**

Concretely: this app tags interactive elements with `Modifier.applyTestTag(tag)`, a single
expect/actual function. On Android it emits the tag prefixed as `<applicationId>:id/<tag>` (so UI
Automator reads it as a resource id); on iOS it emits the tag plainly as the accessibility
identifier. Maestro's `id:` selector matches by regex/substring, so it absorbs that prefix
difference for free — a flow author never needs to special-case either platform for this.

This was proven twice, not just asserted: first with a single-screen flow (tap a `pin_text_field_0`
field, type a PIN, assert the screen advanced) run unmodified on both a fresh iOS Simulator and a
fresh Android emulator; then again at real multi-screen scale with `tc-01-pid-issuance-common.yaml`
(PIN setup through the document-type catalog), also run unmodified on both.

**The limit of this finding**: it only holds for screens that actually carry a real `testTag`.
Roughly a third of this app's screens don't yet (see the tag-coverage audit referenced in
`.maestro/PORTING_STATUS.md`'s background) — those will only expose plain visible text to Maestro,
which is weaker to assert on and platform-identical only by coincidence of copy, not by design.
And it does *not* extend past this app's own UI: once a flow hands off to an external browser-hosted
form (as PID issuance's identity-proofing step does), that content has no relationship to this
app's tag convention at all — selectors there must be built per-platform/per-form, by hand, the
ordinary hard way (see `tc-01-pid-issuance-android-continuation.yaml`'s own header for exactly how
fragile that gets).

## 6. Current porting status

**Most of what's under `.maestro/` right now is unverified reference material ported from a
different, native-iOS EUDI wallet app (`eudi-app-ios-wallet-ui`) — not working flows for this app.**
Every one of those files carries an explicit warning banner at the top saying so. Expect real
navigation and accessibility-id differences from that other app (several are already confirmed —
see `.maestro/kmp/issuance/tc-01-pid-issuance-common.yaml`'s own header for a concrete list); don't
assume any of them run as-is.

The genuinely built-and-verified-against-this-app work lives under `.maestro/kmp/`, one
subdirectory per category, mirroring the reference tree's own layout. Full status of every test
case, tracked by hand: **[`.maestro/PORTING_STATUS.md`](../.maestro/PORTING_STATUS.md)**. Update
that table in the same commit as any flow file it describes.

For detailed, protocol-level findings from the original native iOS testing program (55+ test
cases, including the critical TC-13 BLE security finding), see
[`wiki/eudi-ios-native-test-catalog.xlsx`](eudi-ios-native-test-catalog.xlsx) — kept as reference
only, not current KMP status.

## 7. Known active blockers

**Client-attestation failure blocks PID-equivalent issuance (TC-01) end to end, on both
platforms, as of 2026-09-15.** Confirmed as a real app/backend bug, not a test-authoring gap — the
in-app portion of the flow (PIN setup through picking a document type) works cleanly on both
platforms; the failure is specifically in the wallet's client-attestation lifecycle with
`dev.wallet-provider.eudiw.dev`:

- **iOS**: the catalog-list tap into PID issuance hangs ~30s trying to reach
  `dev.wallet-provider.eudiw.dev/wallet-instance-attestation/jwk` (and the issuer's `.well-known`
  endpoint), then fails with "Error establishing authenticated channel with issuer." Device logs
  show a TLS handshake completing in ~250ms, then no HTTP response at all until timeout — a plain
  `curl` to the same endpoint from the same machine, at the same time, answers in under half a
  second, so this isn't a backend outage.
- **Android**: gets much further — a real browser-hosted identity-proofing form, filled in and
  submitted, through to "Authorize" — then fails the final token exchange:
  ```
  POST https://backend.dev.issuer.eudiw.dev/oidc/token
  {"error": "invalid_request", "error_description": "Client Attestation has expired
  (expired time is before current time minus skew)."}
  ```

Different symptom, same suspect. Worth raising with whoever owns the client-attestation
implementation before spending more time porting flows downstream of it — re-check this section
before assuming a new issuance-flow failure is your test's fault.

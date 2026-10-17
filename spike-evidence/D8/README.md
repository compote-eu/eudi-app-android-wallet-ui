# D8 regeneration drills (M8)

**Baselines.** App `spike/ux-runtime` @ `93c2b9931` (D8 baseline, ux-spec = export of ux@3d6ecb6, spec `sha256:88e2b611…02d0`).
Spec `dvm-week0` @ `874b4f9` (branch `spike-week0`, never committed for D8). ⟲ `git fetch origin feature/kmp`: still `b23f6a1f5`,
no rebase. Every mutation starts from both baselines; the four app branches and spec worktrees were deleted after the evidence
was kept.

**Pre-D8 baseline grep** (plan §3, on `93c2b9931`):
`git grep -n -i -E 'logo_url|logoUrl|idsk' 93c2b9931 -- ':!ux-spec' ':!*.md' ':!spike-evidence'` → **0 hits**. The KC1 guard
(`spike-gate.sh` step `kc1-guard`) is 0 on every D8 branch.

## Results

| n | Drill | Spec LOC (patch +/-) | Generated delta (all / Kotlin, +/-) | `git diff --stat 93c2b9931..spike/d8-n` | Gate | Behaviour | Row |
|---|---|---|---|---|---|---|---|
| 1 | logo | +5 −1 (3 files) | +204 −61 / +15 −11 | 19 files, all `ux-spec/**` | RED: 6 tests (4 element-schema, 2 pinned fixture) | **not** changed: no logo node; contract and bundle carry `logo_url` | **failed** (element capability gap + hand-written consent screen; KC1 evidence) |
| 2 | offline | +61 −0 (7 files, 1 new screen) | +441 −64 / +21 −11 | 20 files, all `ux-spec/**` | RED: 7 tests (pinned fixture 4, pinned counts 3) | changed: `offline-loading` reached by script `offline`, RETRY → `fetchRequest#2` → request | **failed** (gate red; test pins outside ux-spec) |
| 3 | brand | +1 −1 (1 file) | +411 −411 / +361 −361 | 19 files, all `ux-spec/**` | RED: 4 tests (pinned fixture) | changed: primary `#0B4199` → `#126DFF` (idsk), radii, badge | **failed** (gate red; test pin outside ux-spec) |
| 4 | copy | +8 −8 (1 file) | +59 −59 / +11 −11 | 19 files, all `ux-spec/**` | RED: 4 tests (pinned fixture) | changed: 5 strings asserted on screen / in log | **failed** (gate red; test pin outside ux-spec) |

Hand-written LOC in every drill: **0** (no file outside `ux-spec/` changed on any `spike/d8-n`). Diff-only criterion: met by all
four. Behaviour criterion: met by 2, 3, 4; not by 1. The full gate (a step of the KC1 procedure) is red on all four.

### Why the gate is red (all four)

- **Pinned TEST fixture (all four, 4 tests; on D8-1 the two `SchemaCheckTest` ones fail on `logo` first):** `InterpreterScenario.anAddedStateRendersAndTransitionsWithNoKotlinChange` and
  `SchemaCheckTest.theExportedBundleAndTheAddedStateCopyHaveNoSchemaProblems` (host + iOS) load
  `spike-evidence/M6a/FLW-pres-cross-device.bundle.offline-loading.TEST.json`, a hand-derived copy of the **baseline** export
  (spec `88e2b611…`), against the current export's spec hash: `SpecHashMismatch`. Any re-export changes the hash, so every D8
  sync breaks it. Making it green needs a re-derived fixture outside `ux-spec/` (test-only, spike side, M6a design).
- **Pinned counts (D8-2 only, 3 tests):** `HandshakeTest.h1_equalHashesAndVersion1Load` asserts 25 states (now 26);
  `FakeScriptsTest.theSixScriptsExist…` asserts exactly the six baseline script names (now + `offline`).
- **Element gap (D8-1 only, 2 tests × host/iOS; they hit `logo` before the fixture):** `SchemaCheckTest` (M5c fail-loud) reports `SCR-pres-request/rp-trusted: unknown prop
  'logo'`: the `EL-rp-identity` registry entry and `UxRpIdentity` have no `logo` (RULING-M6a-6 listed it as a GAP until this drill).

Every other gate step is green on every branch: kc1-guard, style-guard, **uxspec-lock**, gradle-ios-demo, lint (detekt +
ktlint, 0 findings on the export), ios-project, xcode-debug, xcode-release; 4279 tests executed per run.

### D8-1 classification (KC1 evidence, for independent verification)

To show the logo, two Kotlin edits outside `ux-spec/` would be needed (none was made):
1. **Element capability gap** (`:ux-elements`): `UxRpIdentity` has no image parameter and no image loader; the registry schema
   rejects `logo`. → D8-1 **failed**.
2. **Hand-written consent screen** (`ux-spike/…/PresRequestScreen.kt`, SCR-pres-request by brief §6.6): it builds `UxRpIdentity`
   from the typed `PresRequestData` and does not read `verifier.logoUrl`; even with (1) it must pass the new field. This is an
   edit in screen code for a regeneration (brief §1, §8 KC1; the M8 brief's "screen code → kill signal"). Reported as a **KC1
   kill-signal candidate**; to be re-derived independently (plan §5) before anything stops.
Also: the library declares `logo` decorative (alt empty, RULING-W1), so the M8 brief's "logo has an accessibility label"
conflicts with the spec as written.

## Reproduction

Spec side (per n; worktrees under `/Users/roman/code`, never committed). The patches apply to `874b4f9` only: `spike-week0`
moved to `445e38c` (batch 5.x, exporter 0.4.0) during M8, by another lane; D8 stayed on `874b4f9` (exporter 0.3.0).

```sh
git -C /Users/roman/code/dvm-week0 worktree add --detach /Users/roman/code/dvm-d8-$n 874b4f9
cd /Users/roman/code/dvm-d8-$n && git apply /Users/roman/code/eudi-kmp-spike/spike-evidence/D8/$n/spec.patch
make -C ux fast check test                         # log: D8/$n/spec-gate.log
make -C ux export-kmp OUT=$TMPDIR/d8/$n/ux-spec ALLOW_DIRTY=1   # lock records ux_dirty=true
(cd $TMPDIR/d8/$n && tar czf …/D8/$n/export.tar.gz ux-spec)
```

App side (per n):

```sh
cd /Users/roman/code/eudi-kmp-spike
git checkout -b spike/d8-$n 93c2b9931
rsync -a --delete $TMPDIR/d8/$n/ux-spec/ ux-spec/ && diff -r $TMPDIR/d8/$n/ux-spec ux-spec   # or: tar xzf export.tar.gz
git add -A ux-spec && git commit -m "D8-$n …"      # the only change
./spike-gate.sh                                      # logs: D8/$n/logs/gate.log, gate-gradle-tests.log (uxspec-lock is its step 3)
git diff --stat 93c2b9931..spike/d8-$n > D8/$n/diffstat.txt
```

Behaviour (iPhone 17 simulator, iOS 27.0, Light, sk; Debug app from the gate's `DerivedData`):

```sh
N=$n; source spike-evidence/D8/tools/drill-helpers.sh; install_app
launch $EV/logs/d8-$n.log --ux-spike <script>; $MA --udid $U test $M/m4-unlock-to-home.yaml
```
- **1 logo** (`slow`): `m5b-open-wait.yaml -e STATE=request`, screenshot, `maestro hierarchy` → `logs/d8-1-semantics-assert.txt`
  (rp-trusted has `.name/.trust/.service/.reason`, no logo node: assertion false). The exported `slow` script carries `logo_url`.
- **2 offline** (`offline`, exported from the SVC `fixtures:`): `maestro test --test-output-dir … -e LINK=… tools/d8-offline.yaml`
  (asserts `ux-state-offline-loading`, "Ste offline", `retry`; RETRY → connecting → request). Log: `fetchRequest#1` →
  `enter offline-loading` (`interpreted from the bundle (TPL-outcome, 3 nodes)`) → `RETRY` → `fetchRequest#2` → `enter request`.
- **3 brand** (`slow`): `m5b-open-wait.yaml -e STATE=request`, screenshot; `python3 -I tools/sample-pixel.py 200 2214
  ../M6b/shots/d6-happy-2-request.png 3/shots/d8-3-request.png` → `#0B4199` (eudiw_v1 `actionPrimaryBg`) vs `#126DFF` (idsk).
- **4 copy** (`slow`): `tools/d8-copy.yaml` asserts the new request title (hand-written screen), verification-code, sending and
  success strings and the absence of the old title; the connecting title is asserted from the log's `announce-live` line
  (connecting lasts 3 s, mostly behind the system Open dialog). `logs/d8-4.log` holds three launches (two flow fixes).

## Spec-side findings

- **E12 vs D8-1** (`make test`, export self-test, 1 failure): E12 rehearses the same four changes on top of the tree and expects
  `VerifierData#logoUrl` / `VerifierPayload#logoUrl` to be *new*; on a tree that already has `logo_url` they are not. `make
  check` is green and the exporter accepted the tree. Not weakened; E12 needs updating when this change lands in the spec.
- **E12 vs D8-2, first try**: with the event named `SYS_OFFLINE`, E12's overlay (`connecting.on.SYS_OFFLINE → connecting-offline`)
  replaced the drill's transition and made `offline-loading` unreachable (G4). The drill's event was renamed `SYS_NETWORK_LOST`
  (RULING-M8-2); then `make fast check test` is fully green. `spec-gate.log` is the green rerun.
- **G2 asset rule** on the first D8-1 try: an example URL ending in `.png` is read as an image path (DEC-014); the example
  became `https://www.example.sk/rp/logo` (as E12).

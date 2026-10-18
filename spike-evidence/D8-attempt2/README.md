# D8 regeneration drills, attempt 2 (M8b = plan M8-2)

**Baselines.** App `spike/ux-runtime` tag `d8-attempt2-baseline` = `6946d80c5` (code tree `2487ff3c3`, M8r). Spec
`dvm-week0` `874b4f9` (exporter 0.3.0): the lock's ux@`3d6ecb6` plus docs only, 77/77 lock sources identical
(RULING-M8b-1). Every drill starts from both. Attempt 1 (`spike-evidence/D8/`) stays on record as failed.

**Blindness.** Drills 1-4 are **not blind**: they reuse attempt 1's patches (`D8/<n>/spec.patch`, copied here unchanged),
and the M8r agent ran the spike-module tests once on the attempt-1 exports. Drill 5 is **unseen**: a separate agent
chose it after the baseline; `5-unseen/README.md` and `5-unseen/spec.patch` are its files, unchanged.

## Results

| n | Drill | Spec LOC (patch) | Generated delta (all / Kotlin) | `git diff --stat 6946d80c5..spike/d8b-n` | Gate | Behaviour | Row |
|---|---|---|---|---|---|---|---|
| 1 | logo | +5 −1 (3 files) | +195 −52 / +15 −11 | `a89e594bd`: 19 files, all `ux-spec/**` | GREEN 4283/0 | changed: `rp-trusted.logo` is an `Other` element with no label and no audit issue (hidden), the name moves right 58 pt and still reads 'Príkladová banka, a.s.' | **passed** |
| 2 | offline | +61 −0 (7 files, 1 new) | +434 −57 / +21 −11 | `bb82450b1`: 20 files, all `ux-spec/**` | GREEN 4283/0 | changed: offline-loading interpreted, RETRY → fetchRequest#2 → request | **passed** |
| 3 | brand | +1 −1 (1 file) | +401 −401 / +361 −361 | `ae755998e`: 19 files, all `ux-spec/**` | GREEN 4283/0 | changed: primary `#0B4199` → `#126DFF` | **passed** |
| 4 | copy | +8 −8 (1 file) | +49 −49 / +11 −11 | `236ad3417`: 19 files, all `ux-spec/**` | GREEN 4283/0 | changed: 5 strings asserted | **passed** |
| 5 | unseen | +21 −2 (3 files, 1 new) | +88 −50 / +11 −11 | `075c2be9d`: 19 files, all `ux-spec/**` | **RED** 4283/2 | changed: conditions 2-6 hold (old app fails 2+3) | **failed** (test pin) |

Hand-written LOC on every drill: **0**. Drill 5's red gate is the two targets of
`InterpreterScenario.outcomeExpiredIsDrawnFromTheBundleAndFocusesItsHeading`. That test expects every node of the
expired screen to be drawn after fetchRequest answers `Expired`, where the new rule correctly hides `verifier`. It is a
test pin (RULING-M8b-6), not a KC1 signal.

## Reproduction

Spec side, per drill `n` (`d` = `n`, or `5-unseen`):

```sh
git -C /Users/roman/code/dvm-week0 worktree add --detach /Users/roman/code/dvm-d8b-$n 874b4f9
git -C /Users/roman/code/dvm-d8b-$n apply /Users/roman/code/eudi-kmp-spike/spike-evidence/D8-attempt2/$d/spec.patch
make -C /Users/roman/code/dvm-d8b-$n/ux fast check test        # -> $d/spec-gate.log; run one at a time (RULING-M8b-4)
```

App side, per drill (`tools/drill-app.sh`, one call does all of it):

```sh
cd /Users/roman/code/eudi-kmp-spike && git checkout spike/ux-runtime
D8B_SCRATCH=<scratch dir> spike-evidence/D8-attempt2/tools/drill-app.sh $n $d <label>
#  1. commits the patch on the worktree's detached HEAD (clean export, ux_dirty false; RULING-M8b-2) -> $d/spec-commit.txt
#  2. make -C ux export-kmp OUT=<scratch>/$n/ux-spec -> $d/export.log, $d/export.tar.gz
#  3. git checkout -b spike/d8b-$n 6946d80c5; rsync the export into ux-spec/ (diff -r = verbatim); one commit of ux-spec only
#  4. git diff --stat / --numstat 6946d80c5..spike/d8b-$n -> $d/diffstat.txt, $d/numstat.txt
#  5. ./spike-gate.sh -> $d/logs/gate.log, $d/logs/gate-gradle-tests.log (uxspec-lock is its step 3)
```

Behaviour (iPhone 17 simulator 3C45E6C6, iOS 27.0, sk, Light; the Debug app from the gate's `DerivedData`):

```sh
N=$d; source spike-evidence/D8-attempt2/tools/drill-helpers.sh; install_app
launch $EV/logs/<log> --ux-spike <script>; sleep 4; unlock      # then:
```

- **baseline** (old app, before any sync; `N=baseline`): `slow` → `mflow $M/m5b-open-wait.yaml -e STATE=request`, `hier
  baseline-request`; `expired-at-sending` / `expired-at-request` → `mflow $T/d8b-open-to-expired.yaml -e SENDING=1|0`,
  `hier …`, then `assert-unseen.py shown|hidden` → `baseline/logs/baseline-assert-*.txt` (conditions 2 and 3 FAIL, as
  required by D8-unseen condition 6).
- **1 logo** (`slow`): `mflow $M/m5b-open-wait.yaml -e STATE=request`; `hier d8b-1-request`;
  `spike-evidence/tools/ios-a11y-audit.sh -o $EV/logs/d8b-1-a11y-audit-request.txt`;
  `python3 -I $T/assert-logo.py baseline/logs/baseline-request.json 1/logs/d8b-1-request.json 1/logs/d8b-1-a11y-audit-request.txt`
  (RULING-M8b-5). The avatar box is blank because the spec's example URL serves HTML.
- **2 offline** (`offline`): `maestro test --test-output-dir $EV/shots -e LINK=… spike-evidence/D8/tools/d8-offline.yaml`; log
  lines `enter offline-loading` … `fetchRequest#2` … `enter request` in `logs/d8b-2.log`.
- **3 brand** (`slow`): `mflow $M/m5b-open-wait.yaml -e STATE=request`, `shot d8b-3-request`;
  `python3 -I spike-evidence/D8/tools/sample-pixel.py 200 2214 baseline/shots/baseline-request.png 3/shots/d8b-3-request.png`.
- **4 copy** (`slow`): `maestro test --test-output-dir $EV/shots -e LINK=… spike-evidence/D8/tools/d8-copy.yaml`; the connecting
  title from the `announce-live` line in `logs/d8b-4.log`.
- **5 unseen** (`expired-at-sending`, then `expired-at-request`): `mflow $T/d8b-open-to-expired.yaml -e SENDING=1` (or `0`),
  `hier …`, `python3 -I $T/assert-unseen.py shown|hidden …` → `5-unseen/logs/d8b-5-assert-*.txt`. In the iOS tree the
  `help` link has no element of its own, on the baseline too, so the order check compares the tags that are present.

Clean-up: `git branch -D spike/d8b-1..5`, `git worktree remove --force /Users/roman/code/dvm-d8b-1..5`.

## Files

`<d>/`: `spec.patch`, `spec-gate.log` (plus `spec-gate-try1-parallel-timeout.log`), `spec-commit.txt`, `export.log`,
`export.tar.gz` (the export with its `UX_SPEC.lock`), `diffstat.txt`, `numstat.txt`, `logs/`, `shots/`, `*.mp4`.
`baseline/`: the old app's runs. `tools/`: `drill-helpers.sh`, `drill-app.sh`, `d8b-open-to-expired.yaml`,
`assert-logo.py`, `assert-unseen.py`.

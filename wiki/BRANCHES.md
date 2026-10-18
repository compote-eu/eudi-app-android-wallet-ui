# Branches, and how upstream work reaches them

This fork carries **two independent lines** of development off the EUDI reference wallet. They do not
share a base, they are maintained by different mechanisms, and merging one into the other would
silently convert it into the other. This note says which is which, and records the one convention
that is easy to apply in the wrong place.

> **Status:** current as of 2026-09-10 · every count below is measured, not estimated.
> Throughout, **`upstream`** means a remote pointing at the reference repository:
> `git remote add upstream https://github.com/eu-digital-identity-wallet/eudi-app-android-wallet-ui.git`

---

## 1. The two lines

### KMP line — Android **and** iOS from one codebase

| branch | on top of | what it is |
|---|---|---|
| `feature/kmp` | 264 commits on the fork point | The main development branch. Android and iOS ship from shared Kotlin in `:shared-ui` / `:shared-logic`, with the Xcode project generated from `iosApp/project.yml`. |
| `feature/kmp-sk` | 2 commits on `feature/kmp` | Slovak wallet — `sk` + `hu` translations and the ID SK theme, both in `commonMain`, so **both platforms** get them. |

### Pre-KMP line — the original Android-only codebase

| branch | on top of | what it is |
|---|---|---|
| `feature/local-flavor` | 4 commits on upstream `bb063c28` | A `local` build flavour pointing at a developer-machine backend, plus the `local-services` scripts. |
| `feature/sk-wallet` | 8 commits on `feature/local-flavor` | Legacy Android-only Slovak variant: `sk` product flavour, theme and translations as Android resources. **iOS gets nothing from this branch** — which is why the KMP line exists. |

⚠️ **`feature/local-flavor` branches from upstream `bb063c28`, not from `feature/kmp`.** Rebasing it
onto the KMP line would convert it to KMP and destroy what it is for. It takes upstream changes
directly instead — see §3.

📌 The app module is **`:androidApp`** on the KMP line and **`:app`** on the pre-KMP line. The
verify sets also differ; see §4.

---

## 2. How each branch's history is maintained

| branch | mechanism |
|---|---|
| `feature/kmp` | **Fast-forward only.** History is never rewritten — the sk branch and every measurement is anchored to it. |
| `feature/local-flavor` | **Fast-forward only**, for the same reason: `feature/sk-wallet` sits on it. |
| `feature/kmp-sk` | **Rebase onto `feature/kmp`, then force-push.** |
| `feature/sk-wallet` | **Rebase onto `feature/local-flavor`, then force-push.** |

⚠️ **The two Slovak branches have their history rewritten on every sync** — four rebases so far on
`feature/kmp-sk`. Do not base long-lived work on either without expecting to rebase it. A backup
branch (`backup/<branch>-pre-rebase-<date>`) is taken before each one, and the force-push uses
`--force-with-lease --force-if-includes`.

🪤 **After switching between a `kmp` and an `sk` branch, run `./gradlew generateIosProject`.** The
Xcode project is generated and git-ignored, so it keeps the app name and bundle id of whichever
branch generated it last; the identity guard then refuses to build. See `CONFIGURATION.md`.

---

## 3. Bringing upstream work in

**Upstream changes are hand-ported, never cherry-picked.** A cherry-pick carries upstream's message
and authorship into a tree whose context has diverged; the port is read in full first and committed
with a message saying what was taken, what was skipped, and why.

### On `feature/kmp`: port, then record the sync

Once a sync's commits have each been assessed, the sync is recorded with a merge that **changes no
file**:

```
git merge -s ours upstream/main -F <message-file>
```

Its tree hash is identical to its first parent's, so it alters nothing — what it does is make
already-assessed upstream commits *ancestors*, which turns "what is still unported?" into a query
instead of a memory:

```
git log --oneline upstream/main --not feature/kmp     # currently 0
```

Five syncs are recorded this way: `bb98dbcf`, `c1fe931d`, `96ee6fcb`, `4be44eb4`, `4f739db3`. Each
merge message lists every commit in that sync and its verdict — ported, skipped, or no own content.

📌 **`feature/kmp-sk` needs no record of its own**: rebasing it onto `feature/kmp` makes those merges
its ancestors too.

### ⛔ On the pre-KMP line: **do not record a sync**

`git merge -s ours` asserts that *everything behind the merge has been assessed*. On `feature/kmp`
that is true. On the pre-KMP line it is not: those branches sit on upstream `bb063c28` and are
**33 commits behind** upstream `main`, and they have never been swept — they take individual upstream
changes when a change matters to them.

So a `-s ours` merge there would claim all 33 had been assessed, and would permanently break the only
query that tells the truth about those branches:

```
git log --oneline upstream/main --not feature/local-flavor    # 33 — and must stay honest
```

**Record a port on that line by naming the upstream commits in the commit message instead.**
`e5c45026` is the worked example: it takes upstream's biometric-authentication fix
(`3e75b420` + `6f7c8d03`), says that all 32 affected files were byte-identical to upstream's
pre-image beforehand, and says which parts of that commit were deliberately not taken.

---

## 4. Verifying a change

The two lines have different module graphs, so they have different verify sets.

**KMP line** — `./gradlew test` alone is **not sufficient**: `:shared-ui` and `:shared-logic` are KMP
modules whose JVM-side tests live on the Android target as `testAndroidHostTest`, which is not wired
into the `test` lifecycle. Name them explicitly.

```
./gradlew test :shared-logic:iosSimulatorArm64Test :shared-ui:iosSimulatorArm64Test \
  :shared-ui:testAndroidHostTest :shared-logic:testAndroidHostTest :androidApp:assembleDevDebug
./gradlew :shared-logic:iosSimulatorArm64Test :shared-ui:iosSimulatorArm64Test -PappFlavor=demo
./gradlew detekt ktlintCheck
./gradlew generateIosProject
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme EudiWallet \
  -destination 'platform=iOS Simulator,name=iPhone 17' -configuration Debug build
```

**Pre-KMP line**

```
./gradlew test
./gradlew :app:assembleDevDebug :app:assembleLocalDebug      # or :app:assembleSkDebug
```

⚠️ **There is no `detekt` / `ktlintCheck` on the pre-KMP line** — the task does not exist there
(`Task 'detekt' not found`), because the code-quality convention plugin is a KMP-line addition. Its
absence is not a skipped gate.

🪤 **A fast `BUILD SUCCESSFUL` is not evidence that anything ran.** Gradle reports success for an
up-to-date task graph, so check the result XMLs under `*/build/test-results/` rather than the console
line — and never re-run a command just to see output that scrolled past, because the second run
reports everything `UP-TO-DATE` and looks exactly like a suite that never executed.

---

## 5. Translations, on either Slovak branch

The translated catalogues are separate files from the English one, so they **never conflict on a
rebase — they just go stale**. A missing key does not fail the build: both platforms fall back to the
default locale and ship English text in an otherwise translated screen.

After every rebase, diff the **key sets** against English:

| line | English | translated |
|---|---|---|
| `feature/kmp-sk` | `shared-ui/src/commonMain/composeResources/values/strings.xml` | `…/values-sk/`, `…/values-hu/` |
| `feature/sk-wallet` | `resources-logic/src/main/res/values/strings.xml` | `resources-logic/src/sk/res/values-{sk,hu}/` |

🪤 **The `git log --name-only` drift sweep is useless immediately after a rebase** — the base branch
is then an ancestor, so it is empty by construction and proves nothing. Only the key-set diff does.

🪤 **Take element bodies from the translation, never the English shape.** Slovak plurals carry four
forms where English has two; emitting the English shape would silently break them.

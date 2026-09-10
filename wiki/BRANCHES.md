# Branches, and how upstream work reaches them

This fork carries **two independent lines** of development off the EUDI reference wallet. They do not
share a base, they are maintained by different mechanisms, and merging one into the other would
silently convert it into the other.

**You are on the pre-KMP line** — the original Android-only codebase. This note says which branch is
which, how upstream work is brought in and recorded here, and which verify set applies.

> **Status:** current as of 2026-09-10 · every count below is measured, not estimated.
> Throughout, **`upstream`** means a remote pointing at the reference repository:
> `git remote add upstream https://github.com/eu-digital-identity-wallet/eudi-app-android-wallet-ui.git`

---

## 1. The two lines

### Pre-KMP line — Android only · **this branch's line**

| branch | on top of | what it is |
|---|---|---|
| `feature/local-flavor` | 4 commits on upstream `bb063c28` | A `local` build flavour pointing at a developer-machine backend, plus the `local-services` scripts. The base of this line. |
| `feature/sk-wallet` | 8 commits on `feature/local-flavor` | **This branch.** Slovak variant: the `Sk` product flavour (`applicationIdSuffix = ".sk"`, name suffix `" SK"`), the ID SK 3.1 theme in `resources-logic/src/sk`, and `sk` + `hu` as Android resources. |

⚠️ **iOS gets nothing from this line.** The theme is an Android product flavour and the translations
are Android resource directories, neither of which an iOS build can see — which is why the other line
exists.

### KMP line — Android **and** iOS from one codebase

| branch | on top of | what it is |
|---|---|---|
| `feature/kmp` | 264 commits on the fork point | The main development branch. Android and iOS ship from shared Kotlin in `:shared-ui` / `:shared-logic`, with the Xcode project generated from `iosApp/project.yml`. |
| `feature/kmp-sk` | 2 commits on `feature/kmp` | The Slovak wallet on that line — the same `sk` + `hu` catalogues and the same theme, but in `commonMain`, so **both platforms** get them. |

📌 **If you are looking at this branch to decide where Slovak work belongs, it belongs on
`feature/kmp-sk`.** This branch is kept because it still builds and installs, not because it is where
the variant is going. The two carry the **same translated strings by hand** — see §5.

⚠️ **`feature/local-flavor` branches from upstream `bb063c28`, not from `feature/kmp`.** Rebasing
this line onto the KMP line would convert it to KMP and destroy what it is for.

📌 Module names differ between the lines: the app module is **`:app`** here and `:androidApp` on the
KMP line. So do the verify sets; see §4.

---

## 2. How each branch's history is maintained

| branch | mechanism |
|---|---|
| `feature/local-flavor` | **Fast-forward only.** History is never rewritten — this branch sits on it. |
| `feature/sk-wallet` | **Rebase onto `feature/local-flavor`, then force-push.** |
| `feature/kmp` | Fast-forward only. |
| `feature/kmp-sk` | Rebase onto `feature/kmp`, then force-push. |

⚠️ **This branch's history is rewritten whenever its base moves.** Do not base long-lived work on it
without expecting to rebase. A backup branch (`backup/sk-wallet-pre-rebase-<date>`) is taken before
each one, and the force-push uses `--force-with-lease --force-if-includes`.

🪤 **After a rebase, the translated catalogues are the thing to check** — they never conflict, they
just go stale. See §5.

---

## 3. Bringing upstream work in

**Upstream changes are hand-ported, never cherry-picked.** A cherry-pick carries upstream's message
and authorship into a tree whose context has diverged; the port is read in full first and committed
with a message saying what was taken, what was skipped, and why.

### ⛔ On this line: **do not record a sync**

On the KMP line, a completed sync is recorded with a merge that changes no file
(`git merge -s ours upstream/main`). That merge asserts *everything behind it has been assessed*,
which turns "what is still unported?" into a query:

```
git log --oneline upstream/main --not feature/kmp     # 0 — every commit assessed
```

**That convention does not apply here, and applying it would be a false claim.** This line sits on
upstream `bb063c28`, is **33 commits behind** upstream `main`, and has never been swept — it takes
individual upstream changes when a change matters to it. A `-s ours` merge would claim all 33 had
been assessed and would permanently break the only query that tells the truth about this branch:

```
git log --oneline upstream/main --not feature/sk-wallet    # 33 — and must stay honest
```

**Record a port on this line by naming the upstream commits in the commit message instead.**
`e5c45026` on `feature/local-flavor` is the worked example: it takes upstream's
biometric-authentication fix (`3e75b420` + `6f7c8d03`), states that all 32 affected files were
byte-identical to upstream's pre-image beforehand, and states which parts of that commit were
deliberately not taken.

📌 **A port that lands on `feature/local-flavor` reaches this branch by rebase**, so most upstream
work arrives here without a commit of its own. What lands here directly is whatever the port makes
stale — usually translations.

---

## 4. Verifying a change

```
./gradlew test
./gradlew :app:assembleSkDebug
```

The other flavours on this line still build and are worth keeping green:

```
./gradlew :app:assembleDevDebug :app:assembleLocalDebug
```

⚠️ **There is no `detekt` / `ktlintCheck` on this line** — the task does not exist here
(`Task 'detekt' not found`), because the code-quality convention plugin is a KMP-line addition. Its
absence is not a skipped gate. Do not add the convention to "fix" it without deciding to own the
resulting lint debt.

⚠️ **The KMP line's verify set is longer, and its `./gradlew test` is not sufficient on its own.** If
you move between the lines, read that branch's copy of this note rather than reusing these commands.

🪤 **A fast `BUILD SUCCESSFUL` is not evidence that anything ran.** Gradle reports success for an
up-to-date task graph, so check the result XMLs under `*/build/test-results/` rather than the console
line — and never re-run a command just to see output that scrolled past, because the second run
reports everything `UP-TO-DATE` and looks exactly like a suite that never executed.

---

## 5. Translations

Slovak and Hungarian are **Android resource directories** on this line, separate files from the
English one — so they **never conflict on a rebase, they just go stale**. A missing key does not fail
the build: Android falls back to the default locale and ships English text in an otherwise translated
screen.

| | file |
|---|---|
| English | `resources-logic/src/main/res/values/strings.xml` |
| Slovak | `resources-logic/src/sk/res/values-sk/strings.xml` |
| Hungarian | `resources-logic/src/sk/res/values-hu/strings.xml` |

After every rebase, and after any port that touches English strings, diff the **key sets** against
English. All three currently carry **354** keys, with no missing keys and no orphans.

🪤 **The `git log --name-only` drift sweep is useless immediately after a rebase** — the base branch
is then an ancestor, so it is empty by construction and proves nothing. Only the key-set diff does.

🪤 **Do not "translate" a `@string/` alias.** Android resolves an alias per locale, so an entry whose
English value is `@string/generic_cancel` is already translated through its target. Check whether the
target is translated instead. (The KMP line is the opposite: compose-resources ships the literal
text, so there an alias must be flattened.)

🪤 **Slovak plurals carry four forms where English has two.** Take element bodies from the
translation, never the English shape, or the plural silently breaks.

📌 **The same strings exist on `feature/kmp-sk`, and the wording is deliberately identical** so the
two Slovak wallets cannot drift. When you add a string here, add the same text there — and vice
versa. Translation conventions and the ID SK glossary are in [wiki/SK_THEME.md](SK_THEME.md#localization).

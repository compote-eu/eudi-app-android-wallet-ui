# D8-unseen: name the verifier on the "expired" outcome

Do not show this to the implementing agents before the drill runs.

## The change

`spec.patch` (applies to dvm-edoklady-eudiw-analysis at `874b4f9`; its export sources match
`eudi-kmp-spike/ux-spec/UX_SPEC.lock` 77/77) changes three spec files:

| File | Change |
|---|---|
| `ux/experience/screens/SCR-pres-outcome-expired.yaml` | declares `/verifier/name` in `data_model`; adds node `verifier` (EL-notice, region body, after `hint`): `tone: info`, `title: {bind: /verifier/name}`, `text: {copy: CPY-pres-outcome-expired-verifier}`; adds ui_state `no-verifier`; adds `rules: [RUL-pres-expired-verifier-visible]` |
| `ux/product/copy/sk/BRF-001.yaml` | new key `CPY-pres-outcome-expired-verifier` = "Overenie spustite znovu na stránke tohto overovateľa." |
| `ux/product/rules/RUL-pres-expired-verifier-visible.yaml` (new) | `when: {path: /verifier/name, op: is_empty}` then `hide` node `verifier` |

On the screen where the request ran out, the verifier is named in a notice ("start the verification again on this
verifier's page") when the wallet knows it. When the request expired while loading, no name exists, so the notice is
hidden.

### Why it is realistic

Designers often add context like this to error screens after a usability round. A user returning from the phone to a
PC with several tabs open needs to know which verifier's page to restart on. It is one new node, one new copy key and
one visibility rule on an existing screen.

### Why it is fair (spec-only, nothing new at runtime)

- **Element and prop:** EL-notice with a bound `title` already appears in the exported bundle (SCR-pres-outcome-document-missing node `document`).
- **Rule:** an `is_empty` rule with a `hide` effect already appears in the bundle (`RUL-pres-url-check-visible` on SCR-pres-verification-code).
- **Context:** `/verifier/name` is already a flow context field that `fetchRequest` fills. The drill adds no Services result, call, event or state.
- **Typed seam:** SCR-pres-outcome-expired is interpreted. It is not one of the `typed_screens`, and it is not the hand-written consent screen.
- **Generated Kotlin:** the export changes `contract/` and `theme/` only in their header and `SPEC_HASH` lines. `PresCrossDeviceState/Event/Services/...` keep the same API.
- **Spec gates:** `make -C ux fast check test` passed in the worktree. The results were `uxcheck 0 error(s)`, `a11y 0 violation(s)`, `mutation 182/0 failures`, `model-check 2/0`, `export 48/0` and token-smoke OK.

## What changes in the export (`ux-spec/`)

- **`resources/values/strings.xml`:** the new line `<string name="cpy_pres_outcome_expired_verifier">Overenie spustite znovu na stránke tohto overovateľa.</string>`.
- **`resources/files/FLW-pres-cross-device.bundle.json`, `screens.SCR-pres-outcome-expired`:**
  - `regions.body` is now `hint`, `verifier`, `help-note`.
  - `rules` holds `RUL-pres-expired-verifier-visible`.
  - `ui_states` is `[default, no-verifier]`.
  - `a11y.focus_order` is `[hero, hint, verifier, help-note, help, retry, close]`.
- **`UX_SPEC.lock`, `fixtures/**` and the generated `.kt` files:** only the hash and provenance lines change.

## Expected visible behaviour

| Fake script | End state | Screen | Node `verifier` |
|---|---|---|---|
| `expired-at-sending` (fetchRequest RequestReady → … → send Expired) | `error-expired` | SCR-pres-outcome-expired | **shown**: an info notice between the new-QR hint and the help notice, with title **"Príkladová banka, a.s."** and text **"Overenie spustite znovu na stránke tohto overovateľa."** |
| `expired-at-request` (fetchRequest Expired) | `error-expired` | SCR-pres-outcome-expired | **absent**: no node, because `/verifier/name` is null. Otherwise the screen is identical to the baseline. |

Every other screen and script is unchanged.

## Pass condition (mechanical)

The drill passes only if all of the following hold:

1. **Spec sync only.** Sync the export into the app's `ux-spec/` and commit nothing else. Then
   `git diff --stat` (or `git diff --stat <before>..<after>`) lists only paths under `ux-spec/`.
2. **Shown under `expired-at-sending`.** Play the fake script `expired-at-sending` to state `error-expired` in a Compose
   UI test or the screenshot harness. On SCR-pres-outcome-expired:
   - a node with testTag `verifier` exists and is displayed;
   - `verifier.title` has the text `Príkladová banka, a.s.`;
   - `verifier.text` has the text `Overenie spustite znovu na stránke tohto overovateľa.`
3. **Order.** In the merged semantics tree, `verifier` comes after `hint` and before `help-note`, which follows the
   bundle's `focus_order`. Example check: the testTag traversal order is `hero, hint, verifier, help-note, help, retry, close`.
4. **Hidden under `expired-at-request`.** Play `expired-at-request` to `error-expired`. No node with testTag `verifier`
   exists (`onAllNodesWithTag("verifier").assertCountEquals(0)`), and the text `Overenie spustite znovu na stránke tohto overovateľa.` is
   nowhere on screen. Hidden means not composed, or at least not in the semantics tree. An invisible node that a screen reader can still
   reach fails.
5. **No regression.** The existing FakeScriptsTest expectations still hold: `expired-at-sending` ends in `error-expired`
   after exactly one `send`, and the state paths of the other scripts are unchanged.
6. **Must fail on the old app.** On the app as it was before the sync, conditions 2 and 3 fail. This proves the behaviour comes from the spec.

The tag names follow the spike's convention: EL-notice `testTag(id)`, `"$id.title"`, `"$id.text"`, in `ux-elements/.../UxNotice.kt`.

The drill **fails** if any file outside `ux-spec/` has to change: runtime, registry, templates, Kotlin screen code or
tests.

## Exporter command

Run in a checkout of dvm-edoklady-eudiw-analysis at `874b4f9` with the patch applied:

```sh
git -C <spec-checkout> apply /private/tmp/claude-501/-Users-roman-code-eudi-app-android-wallet-ui/52b55b31-de61-46cd-b3af-248467b16610/scratchpad/d8-unseen/spec.patch
make -C <spec-checkout>/ux fast check test          # must be green
# export-kmp refuses a dirty tree: commit the patch on a throwaway branch first, or pass ALLOW_DIRTY=1
make -C <spec-checkout>/ux export-kmp OUT=<app-checkout>/ux-spec
# equivalent: python3 <spec-checkout>/ux/tools/export_kmp.py --flow FLW-pres-cross-device --out <app-checkout>/ux-spec
```

Then, in the app, run `git diff --stat`, the build and the UI tests for conditions 1–6.

I ran a trial export into a scratch copy (since deleted) with `ALLOW_DIRTY=1`. It wrote 19 files and 248 a11y renders. The only
content changes were the strings.xml line and the bundle entries listed above.

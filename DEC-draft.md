# DEC draft: outcome of the KMP UI-from-spec spike (brief §10.4)

A draft for the analysis repo (`ux/records/decisions/`), in its DEC format. Not filed: the number is the next free one
at the time of writing (DEC-146 is the last in `dvm-edoklady-eudiw-analysis`), and the record stays `provisional: true`
until the owner team rules. Sources: `spike-report.md` (this folder), `spike-results.md`, the M12a independent
verification, the owner's device session of 2026-10-18, `RESKIN-PROBE.md` (option B) and `DECISION-BRIEF.md`.

```yaml
id: DEC-147   # draft; take the next free number when filing
record: decision
subjects: ["FLW-pres-cross-device", "TPL-sheet", "SCR-pres-request", "TOKENS:governance"]
decided_by: "claude (draft for the owner team — outcome not ruled; the spike's verdict is 'Incomplete: awaiting evidence', updated M12c, 2026-10-18)"
verdict: approve   # approves recording option A (Incomplete) and proposes option E for adoption; the records schema has no "defer" (approve | reject | approve-with-changes)
rationale: >-
  Outcome of the spike (brief kmp-ui-from-spec/spike-brief.md §1, §8, §10.4): **Incomplete: awaiting evidence**.
  What is proven: one hand-written flow runtime (pure-Kotlin engine, host, generic screen interpreter) runs
  FLW-pres-cross-device from the exported bundle on Android and iOS, with the app connected through the generated
  typed Services contract; seven of eight screens are drawn from the bundle and SCR-pres-request is hand-written
  against the generated contract (brief §6.6). D1, D2 (am kill, both task roots), D3 (simulator), D4, D5 and D6
  passed formally on the iOS simulator and the Android emulator; spec-only changes (a bound field, an added state and
  screen, a brand switch, copy, sheet to page) regenerated with 0 hand-written lines (D8 attempt 2, not blind; D9);
  the one blind drill (D8-unseen) behaved as specified but failed formally on a spike test that pinned the baseline
  export (fixed afterwards by spike rule T1 and the regen-smoke gate step).
  Owner device session on the iPhone Air (iOS 27.0.1, HEAD build), 2026-10-18: D7-iOS VoiceOver passed (focus on each
  new heading, sending announced once, success announced, no old-screen node reachable), so KC3 is not hit; D3-device
  passed (kill in request: Home, nothing restored; a fresh cold link starts at connecting); D10 in the DC-API extension
  rendered and completed 5 of 5 cold, first Compose view 150-171 ms against 636 ms for the baseline cold start (a
  system-log proxy), no footprint numbers (instrumentation lines missing from the unified log), and 4 jetsam
  per-process-limit kills (220 MB) on warm scene reuse in runs 1-4 while the baseline was never killed: a memory risk
  on reuse and a declared-exception candidate, not a kill.
  No kill criterion has been hit: KC1 passed with caveat and KC4 passed (both by agent rulings the owner must confirm),
  KC3 passed, KC2 unresolved (the real low-memory kill on an Android phone is missing), KC5 missing (Martin). The
  TalkBack walk on a real Android phone (D7-Android, a verdict precondition) is missing.
  Conditional outcome: real LMK and TalkBack pass, KC5 missing → go with exceptions (KC5; the extension's memory on
  reuse if declared); KC5 at most 2x the spike → go (the extension exception stays unless fixed); the real LMK needs a
  handler outside the policy in more than 1 of 3 states, or KC5 above 2x → fall back to hand-written screens with
  generated contracts and tests; a failed TalkBack walk fails a precondition without a kill criterion → the owner
  decides.
  Adoption (separate from the verdict): option B (the generated design system in the app team's existing screens,
  measured on spike/reskin-probe) reskinned Home and the consent screen with 619/308 lines and about 15-20 min of agent
  time, no change to view models, navigation or interactors; tokens, brand, styles, library icons and element looks
  regenerate with 0 Kotlin lines, copy, composition and flow logic do not. Whole-app estimate for B: 2.8-9.8 k lines,
  18-45 h of agent time (estimated). Option A (this spike) also regenerates copy, composition, rules, states and
  flows, but per flow costs a Services adapter (562 main / 468 test for this flow) on a contract the app's interactors
  fit badly. Recommendation: hybrid, B first for the whole app; A only for the presentation (and later issuance) flows,
  once the real LMK, TalkBack, KC5 at most 2x and the contract gaps are closed (DECISION-BRIEF.md).
  Findings that hold whatever the outcome: the app's interactors fit the generated Services contract badly (M11b:
  of 21 results 5 map, 10 lossy, 6 impossible; no typed Expired; no verification-code source, so a real adapter cannot
  reach sending; transactionData dropped, a security gap for signing and payment); the app re-delivers the launch
  intent after process death (F-D2-1, upstream); the quality bar of brief §6.5 was breached on branch commits by test
  pins; the touch list (T1-T5, T9) was never approved.
refs:
  - "ux/evaluation/kmp-ui-from-spec/spike-brief.md §1, §5-§8, §10"
  - "ux/evaluation/kmp-ui-from-spec/spike-report.md §2 (verdict, missing rows, conditional verdicts), §6 (constraint audit), §9 (cost model)"
  - "ux/evaluation/kmp-ui-from-spec/spike-results.md (drill rows, incl. D7-iOS, D3-device, D10-run1..5, D10-analysis of 2026-10-18); SPIKE-PLAN.md §0.5 (verdict rules)"
  - "ux/evaluation/kmp-ui-from-spec/RESKIN-PROBE.md (option B measured) and DECISION-BRIEF.md (A / B / hybrid)"
  - "spike/ux-runtime @ 04e4ba57c (code tree) and spike/reskin-probe @ 7f2d65b5c, compote-eu/eudi-app-android-wallet-ui, base feature/kmp @ b23f6a1f5 (not pushed)"
  - "DEC-064 (owner team); DEC-069..082 (week-0 defaults, owner ruling pending; DEC-076 waiver first)"
date: 2026-10-18
provisional: true
options:
  - "A (current state, recorded): Incomplete — awaiting evidence; no outcome until the real LMK and the TalkBack walk on an Android phone and KC5 exist and the owner confirms the agent KC rulings"
  - "B (if the real LMK and TalkBack pass, KC5 missing): go with exceptions — runtime interpretation for FLW-pres-cross-device; consent screens hand-written; KC5 and (if declared) the DC-API extension's memory on reuse named as exceptions"
  - "C (if the real LMK and TalkBack pass and KC5 <= 2x the spike): go (with the extension exception unless the memory risk is fixed)"
  - "D (if KC2 or KC5 is hit, confirmed by the owner): fall back — hand-written screens with generated contracts and tests"
  - "E (adoption, recommended): hybrid, B first — the generated design system in the app's existing screens now; the flow runtime (A) only for the presentation and issuance flows once C holds and the Services contract gaps are closed"
```

Open before this can be ruled: Q-D2-LMK, Q-D7-Android, Q-KC5, Q-KC-CONFIRM, Q-TOUCH, Q-DEC (`HUMAN-QUEUE.md` on the
spike branch). Closed on 2026-10-18: Q-D7-iOS, Q-003, Q-D10.

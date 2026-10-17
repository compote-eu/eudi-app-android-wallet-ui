# DEC draft: outcome of the KMP UI-from-spec spike (brief §10.4)

A draft for the analysis repo (`ux/records/decisions/`), in its DEC format. Not filed: the number is the next free one
at the time of writing (DEC-146 is the last in `dvm-edoklady-eudiw-analysis`), and the record stays `provisional: true`
until the owner team rules. Sources: `spike-report.md` (this folder), `spike-results.md`, the M12a independent
verification.

```yaml
id: DEC-147   # draft; take the next free number when filing
record: decision
subjects: ["FLW-pres-cross-device", "TPL-sheet", "SCR-pres-request", "TOKENS:governance"]
decided_by: "claude (draft for the owner team — outcome not ruled; the spike's verdict is 'Incomplete: awaiting evidence', M12, 2026-10-17)"
verdict: approve   # approves recording option A (Incomplete); the records schema has no "defer" (approve | reject | approve-with-changes)
rationale: >-
  Outcome of the spike (brief kmp-ui-from-spec/spike-brief.md §1, §8, §10.4): **Incomplete: awaiting evidence**.
  What is proven: one hand-written flow runtime (pure-Kotlin engine, host, generic screen interpreter) runs
  FLW-pres-cross-device from the exported bundle on Android and iOS, with the app connected through the generated
  typed Services contract; seven of eight screens are drawn from the bundle and SCR-pres-request is hand-written
  against the generated contract (brief §6.6). D1, D2 (am kill, both task roots), D3 (simulator), D4, D5 and D6
  passed formally on the iOS simulator and the Android emulator; spec-only changes (a bound field, an added state and
  screen, a brand switch, copy, sheet to page) regenerated with 0 hand-written lines (D8 attempt 2, not blind; D9);
  the one blind drill (D8-unseen) behaved as specified but failed formally on a spike test that pinned the baseline
  export (fixed afterwards by spike rule T1 and the regen-smoke gate step). No kill criterion has been hit:
  KC1 passed with caveat, KC4 passed (both by agent rulings that the owner must confirm), KC2 and KC3 unresolved,
  KC5 missing. Missing rows (SPIKE-PLAN §0.5): D7-iOS VoiceOver on the iPhone (KC3), D3 on the iPhone and a real
  low-memory kill on an Android phone (KC2), D7-Android TalkBack on a real phone (verdict precondition; the emulator
  row is events only), D10 runs 1-5 in the DC-API extension (track C), and the app team's KC5 estimate (Martin).
  Conditional outcomes: if every missing device row passes and KC5 is still missing → go with exceptions; with KC5 at
  most 2x the spike → go; a D10 that runs and fails → a declared exception (at most go with exceptions); D7-iOS failing
  in a way the runtime cannot fix (KC3), more than 1 of 3 hard states needing a handler outside the policy on the
  device or under a real low-memory kill (KC2), or KC5 above 2x → fall back to hand-written screens with generated
  contracts and tests; a failed TalkBack walk fails a precondition without a kill criterion → the owner decides.
  Findings that hold whatever the outcome: the app's interactors fit the generated Services contract badly (M11b:
  of 21 results 5 map, 10 lossy, 6 impossible; no typed Expired; no verification-code source, so a real adapter cannot
  reach sending; transactionData dropped, a security gap for signing and payment); the app re-delivers the launch
  intent after process death (F-D2-1, upstream); the quality bar of brief §6.5 was breached on branch commits by test
  pins; the touch list (T1-T5, T9) was never approved.
refs:
  - "ux/evaluation/kmp-ui-from-spec/spike-brief.md §1, §5-§8, §10"
  - "ux/evaluation/kmp-ui-from-spec/spike-report.md §2 (verdict, missing rows, conditional verdicts), §6 (constraint audit), §9 (cost model)"
  - "ux/evaluation/kmp-ui-from-spec/spike-results.md (drill rows); SPIKE-PLAN.md §0.5 (verdict rules)"
  - "spike/ux-runtime @ 04e4ba57c (code tree), compote-eu/eudi-app-android-wallet-ui, base feature/kmp @ b23f6a1f5 (not pushed)"
  - "DEC-064 (owner team); DEC-069..082 (week-0 defaults, owner ruling pending; DEC-076 waiver first)"
date: 2026-10-17
provisional: true
options:
  - "A (current state, recorded): Incomplete — awaiting evidence; no outcome until D7-iOS, D3-device, real LMK, D7-Android (phone), D10 x5 and KC5 exist and the owner confirms the agent KC rulings"
  - "B (if every missing row passes, KC5 missing): go with exceptions — runtime interpretation for FLW-pres-cross-device; consent screens hand-written; KC5 named as the exception"
  - "C (if every missing row passes and KC5 <= 2x the spike): go"
  - "D (if KC2, KC3 or KC5 is hit, confirmed by the owner): fall back — hand-written screens with generated contracts and tests"
```

Open before this can be ruled: Q-D7-iOS, Q-003, Q-D2-LMK, Q-D7-Android, Q-D10, Q-KC5, Q-KC-CONFIRM, Q-TOUCH, Q-DEC
(`HUMAN-QUEUE.md` on the spike branch).

> **M12 note (2026-10-17).** Use the device build at the spike HEAD of M12 (`spike/ux-runtime`, code tree = `04e4ba57c`; the M12 commits change docs and evidence only), not `93c2b9931`. The texts and timings below are unchanged at HEAD (same export ux@`b55be65` copy for these states; `slow` = 3 s / 1.5 s / 1.5 s / 3 s). Queue entry: `HUMAN-QUEUE.md` Q-D7-iOS.

# Device test 3: VoiceOver walk (Q-D7-iOS), build 93c2b9931 (the real export)

## Before
- Phone in Light mode. Do not launch the app yourself; the orchestrator sends the deep links.
- VoiceOver on/off: triple-click the side button (Accessibility Shortcut). Otherwise Settings > Accessibility > VoiceOver.
- Start a screen recording with sound (Control Center > long-press Record > Microphone on) before the orchestrator launches the app.
- Gestures only: swipe right = next element, swipe left = previous, double-tap = activate. No direct touch on items, so focus is what the app set. VoiceOver: double-tap PIN digits to unlock.
- Expected texts are PROVISIONAL sk copy (brand may show "eDoklady"). VoiceOver adds role words ("heading", "button").

## Which script
1. Slow script first (`--ux-spike slow`: fixture delays: request 3 s, auth 1.5 s, code 1.5 s, sending 3 s). Use it for steps 1 to 6 so every state is long enough to hear.
2. Then the happy script (normal timing) for one quick pass of request -> success, to confirm focus and speech also work at real speed.
The orchestrator launches each with its deep link; tell it when you are ready.

## Steps (slow script)
| # | You do | New state | Should say first (untouched) | Focus should land on |
|---|---|---|---|---|
| 1 | unlock with PIN | connecting (4 s) | "Načítava sa žiadosť overovateľa. Overuje sa totožnosť overovateľa a dostupnosť vašich eDokladov." once | not moved |
| 2 | wait | request (sheet) | "Overenie eDokladu, heading" | sheet title; swipe right: verifier, privacy link, data, attributes, Continue, Reject; app bar behind never reached |
| 3 | swipe to Continue, double-tap | auth (12 s) | "Overenie, že ste to vy, heading" | auth title |
| 4 | wait | verification-code | "Skontrolujte stránku a prepíšte overovací kód, heading" | title; swipe right reaches code 482 913, "Predložiť eDoklady" (confirm) |
| 5 | swipe to "Predložiť eDoklady", double-tap | sending (45 s) | "Prebieha odoslanie dát eDokladov" exactly once | not moved |
| 6 | wait | success | "eDoklady boli predložené, heading" | success title; swipe right: "Pokračujte na stránke overovateľa vo webovom prehliadači.", then Close |
| 7 | swipe to Close, double-tap | Home | not part of D7 | n/a |

After each change: swipe left from new focus must not reach old-screen text. Rotor > Headings: one heading per screen (request has two extra attribute group headings).
Also on request: mandatory attribute must NOT be read "dimmed"; privacy link expected as "button" (known gap, no link trait in CMP 1.12).

## Pass
Focus on new heading after states 2, 3, 4, 6; sending announced once; success announced; no focus on old-state nodes.

## Results (send the recording too)
| State | Expected focus | Expected speech | What was heard | Old-screen element reachable? | ok / not ok |
|---|---|---|---|---|---|
| connecting | not moved | Načítava sa žiadosť... (once) | | | |
| request | sheet title | Overenie eDokladu, heading | | | |
| auth | auth title | Overenie, že ste to vy, heading | | | |
| verification-code | title | Skontrolujte stránku a prepíšte overovací kód, heading | | | |
| sending | not moved | Prebieha odoslanie dát eDokladov (once) | | | |
| success | success title | eDoklady boli predložené, heading | | | |
| happy script pass (request..success) | as above | as above | | | |

## Update (real export, build 93c2b9931)
- Installed on the iPhone at 93c2b9931, the real export (not the M7 build). App data kept, not launched.
- The happy script skips connecting and sending (no delays), so use `slow` for steps 1 to 6 and the connecting/sending rows.
- Verification code is now 482 913 (spoken digit by digit or as groups; note what you hear), portal https://www.example.sk/overenie.
- Texts from ux-spec/resources/values/strings.xml: connecting title "Načítava sa žiadosť overovateľa", text "Overuje sa totožnosť overovateľa a dostupnosť vašich eDokladov."; request title "Overenie eDokladu"; buttons "Pokračovať" / "Odmietnuť"; privacy link "Zásady ochrany osobných údajov overovateľa"; optional heading "Nepovinné údaje"; mandatory tag "Povinné"; auth title "Overenie, že ste to vy"; code title "Skontrolujte stránku a prepíšte overovací kód", instruction "Prepíšte overovací kód do stránky overovateľa", confirm "Predložiť eDoklady", cancel "Zrušiť"; sending "Prebieha odoslanie dát eDokladov"; success "eDoklady boli predložené" + "Pokračujte na stránke overovateľa vo webovom prehliadači.", Close "Zatvoriť".
- Slow fixture delays are 3 s / 1.5 s / 1.5 s / 3 s, not the 12 s / 45 s above; states are shorter, so listen promptly.

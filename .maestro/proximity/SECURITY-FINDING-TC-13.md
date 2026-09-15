> ⚠️ **PORTED FROM eudi-app-ios-wallet-ui, UNVERIFIED against this KMP app -- accessibility IDs,**
> navigation, and screen structure likely differ (confirmed divergent in TC-01's initial port
> attempt: 2026-09-09). Treat as a reference/starting point, not a working flow.

# ⚠️ SECURITY / TRUST FINDING — TC-13: false "successfully shared" claim on BLE disconnect

**Status: confirmed, reproduced 2/2. Not yet fixed.**
**Severity: High (trust/safety, not just UX).** A user can be told their PID
(name, birth date, and other personal data) was shared with a relying
party — and act on that belief (e.g. proceed past a border check, an
age-gate, a KYC step) — when the relying party received **zero bytes**
of it.

This file exists separately from `README.md` deliberately - this is not
a routine test-case log entry, it is a live app defect with trust/safety
impact and needs to stay visible on its own, not be buried as one line
among TC-02/03/04.

---

## The claim vs. reality

| | What happened |
|---|---|
| **iOS's screen said** | "Data shared — You successfully shared the following information with **Verifier** — PID (MSO Mdoc)" (see `tc-13-evidence/ios-false-success-screen.png`) |
| **The verifier actually received** | **Nothing.** Zero response bytes. Its own transfer library logged an explicit error: `Peer disconnected without proper session termination`. |

The user has no way to know this from the app. The UI is unconditionally
affirmative.

---

## Reproduction steps

Automated via `tc-13-ble-mid-transfer-disconnect.sh` (physical devices:
iPhone 16 Pro Max + physical Android verifier, same BLE proximity rig as
TC-02/03/04). Manually, the equivalent steps are:

1. On the Android verifier: create a Full PID request, open the QR
   scanner.
2. On iOS: Home → Authenticate → In person → scan the QR → consent
   screen appears → tap **Share**.
3. **Immediately after tapping Share** (before entering the PIN),
   disable Bluetooth on the Android verifier device.
   - Why this timing and not literally mid-byte-transfer: a live
     baseline capture on this rig (uninterrupted TC-03 run,
     `adb logcat -v threadtime`) showed the entire GATT response write
     is six ~512-byte chunks landing in well under 55ms, roughly 6+
     seconds *after* the consent screen appears - almost all of that
     gap is PIN-entry/UI latency. A ~55ms window is not something any
     externally-triggered `adb` command can reliably hit. Disabling
     Bluetooth right after Share and holding it off through PIN entry
     guarantees the radio is already down by the time the app attempts
     to send - a strictly *harder* failure case (the link is dead for
     the whole attempt, not just grazed), and the one that actually
     matters: does the success screen require a delivered response, or
     just a local status?
4. Enter the PIN as normal (this is local wallet authorization,
   independent of the BLE link, so it proceeds without any sign of
   trouble).
5. Observe: iOS shows the normal "Data shared successfully" screen.
   Check the Android verifier's own logs: it never logged
   `ResponseReceived`.

Re-enable Bluetooth afterward (the automated script does this in a
`finally` block plus a second, independent safety-net trap).

---

## Evidence

### iOS (what the user sees)

![iOS shows success despite zero bytes received](tc-13-evidence/ios-false-success-screen.png)

### Android (ground truth — what the verifier actually received)

Full `TransferManager` log lines from the run that produced the
screenshot above (`adb logcat -v time`, unedited except trimming the
QR payload and most of the stack trace for length):

```
08-27 12:16:11.640 D/TransferManager(21725): QR Engagement Request: mdoc:owBjMS4w... [truncated]
08-27 12:16:11.664 D/TransferManager(21725): Device Engagement Received
08-27 12:16:13.489 D/TransferManager(21725): Connection Established
08-27 12:16:13.489 D/TransferManager(21725): About to send Document Request
08-27 12:16:13.494 D/TransferManager(21725): Request Doc {"version": "1.0", ...} [full PID request, 29 fields]
08-27 12:16:20.701 E/TransferManager(21725): Error: Peer disconnected without proper session termination
08-27 12:16:20.701 E/TransferManager(21725): java.lang.Error: Peer disconnected without proper session termination
08-27 12:16:20.701 E/TransferManager(21725): 	at com.android.identity.android.mdoc.deviceretrieval.VerificationHelper$connectWithDataTransport$listener$1.onDisconnected(VerificationHelper.kt:832)
08-27 12:16:20.701 E/TransferManager(21725): 	at com.android.identity.android.mdoc.transport.DataTransport.reportDisconnected$lambda$3(DataTransport.kt:212)
08-27 12:16:20.701 E/TransferManager(21725): 	... [platform frames omitted]
08-27 12:16:20.709 D/TransferManager(21725): Terminating current session
08-27 12:16:20.711 D/TransferManager(21725): Terminating current session
```

No `ResponseReceived` line appears anywhere in the capture (`grep -c
"ResponseReceived"` → `0`). The Android verifier's own library
explicitly recognized and named this failure mode
(`VerificationHelper.kt:832`, `onDisconnected`) — the far side detected
the problem correctly. iOS did not.

Reproduced identically on a second, independent run (same outcome, same
Android error, different timestamps) before this was written up.

---

## Root cause (code-grounded, verified against this checkout)

The success screen is reached purely by the app-layer state machine
observing the third-party SDK's own locally-reported status - there is
**no application-level confirmation that the verifier received
anything**.

**`Modules/logic-core/Sources/Coordinator/Model/PresentationState.swift:18-26`**
— the complete set of states the app models. Note there is no
`connecting`/`sending`/`disconnected` case, only a terminal
`responseSent` and a generic `error`:

```swift
public enum PresentationState: Sendable {
  case loading
  case prepareQr
  case qrReady(imageData: Data)
  case requestReceived(PresentationRequest)
  case responseToSend(RequestItemConvertible)
  case responseSent(URL?)
  case error(Error)
}
```

**`Modules/logic-core/Sources/Coordinator/ProximitySessionCoordinator.swift:49-73`**
— the *only* place `.responseSent` is ever produced, driven directly by
the SDK's own `$status` publisher, with no independent validation:

```swift
self.session.$status
  .sink { [weak self] status in
    guard let self else { return }
    switch status {
    ...
    case .responseSent:
      self.sendableCurrentValueSubject.setValue(.responseSent(nil))
    case .error:
      if let error = session.uiError?.errorDescription {
        self.sendableCurrentValueSubject.setValue(.error(RuntimeError.customError(error)))
      } else {
        self.sendableCurrentValueSubject.setValue(.error(WalletCoreError.unableToPresentAndShare))
      }
    default: ()
    }
  }
```

and the send call itself (lines 103-106):

```swift
public func sendResponse(response: RequestItemConvertible) async throws {
  try await session.sendResponse(userAccepted: true, itemsToSend: response.items)
  await session.waitForDisconnect()
}
```

**`Modules/feature-proximity/Sources/UI/Presentation/Loading/ProximityLoadingViewModel.swift:79-124`**
— the view model that actually navigates to the success screen. Two
things worth noting: `doWork()`'s own call to `onSendResponse()`
treats a `.sent` result as a complete no-op (line 88, `case .sent:
break`) — **all real navigation happens from a separate subscription**
to the coordinator's state stream reacting to `.responseSent`
(lines 110-116):

```swift
case .responseSent:
  await self.interactor.stopPresentation()
  self.onNavigate(type: .push(getOnSuccessRoute()))
```

**Why this produced a false positive here:** `session.sendResponse(...)`
apparently returned without throwing (the local GATT write queue was
presumably accepted/queued locally before the radio-off fully
propagated), and `session.waitForDisconnect()` returned normally too -
any disconnect, expected post-transfer teardown or an unexpected
mid-attempt link loss, resolves the same way from this code's point of
view. **The SDK's status stream never emitted `.error` for this
scenario on the iOS side**, even though the Android side's equivalent
library (`com.android.identity.android.mdoc`, likely a sibling/same
family to whatever `EudiWalletKit` wraps on iOS) explicitly detected
and named the same disconnect as an error.

**Why this can't be fully root-caused from this repo alone:** the
actual send/wait implementation lives in the third-party
`EudiWalletKit` Swift package (`@_exported import EudiWalletKit`,
`Modules/logic-core/Sources/LogicCore.swift:18`; resolved via
`Package.resolved`), whose source isn't vendored here. This app fully
delegates "was the response actually delivered" to that SDK's status
reporting and has no independent check of its own — which is exactly
the gap this finding is about, whether the ultimate bug is in this
app's blind trust of the status, in the SDK's status reporting, or
both.

Also relevant: **there is no BLE/connection-specific error case
anywhere in this app.** `PresentationSessionError`
(`Modules/logic-core/Sources/Coordinator/Model/PresentationSessionError.swift:16-22`)
has exactly five cases (`qrGeneration`, `noDocumentFoundForRequest`,
`conversionToRequestItemModel`, `invalidState`,
`failedToParseRemoteURL`) — none BLE-related. Any SDK-reported error
collapses into the single generic `WalletCoreError.unableToPresentAndShare`
string (`Modules/logic-core/Sources/Error/WalletCoreError.swift:26,43-44`)
unless the SDK supplies its own description via `RuntimeError.customError`.
Even in the cases where this code path *does* correctly detect a
failure, the user would see a generic message, not "the verifier never
received your data."

---

## Severity reasoning

This is being flagged above the level of a normal test-case result
because the failure mode is a **false positive on a security-relevant
claim**, not a crash, a UI glitch, or a missing feature:

- The user is told data disclosure to a third party **succeeded** when
  it did not.
- The scenario that triggers it - a BLE link dropping during an
  in-person presentation (walking out of range, a phone's Bluetooth
  radio being toggled, a genuine hardware/RF interruption) - is
  ordinary real-world behavior, not a contrived edge case.
- Nothing in the UI gives the user any signal to doubt the "success"
  message or retry. A user who trusted it and walked away (e.g. from a
  physical checkpoint, an age-verification counter) has no reason to
  believe anything went wrong.
- The correct verifier-side behavior already exists and is *already
  proven working* (Android detected and logged the exact failure) -
  this is not a case where "nobody can detect this," only that iOS
  doesn't ask the question.

---

## Independent verification of TC-02/TC-03/TC-04 (do they need the same retrofit?)

Checked whether the same "trust the local SDK status, never confirm
the verifier's independent receipt" gap could be silently inflating
those tests' PASS results too. It does not - confirmed directly, not
assumed:

**All three already verify Android's actual received log state, not
iOS's UI claim.** Each orchestrator (`tc-0{2,3,4}-*.sh`) gates its
overall PASS/FAIL on three independent exit codes:

```bash
if [ "$IOS_EXIT" -ne 0 ] || [ "$ANDROID_EXIT" -ne 0 ] || [ "$VERIFY_EXIT" -ne 0 ]; then
  echo "TC-0N: FAIL ..."
```

and each `tc-0{2,3,4}-verify-attributes.py` independently reconstructs
Android's own `TransferManager` logcat and requires:

- a `ResponseReceived` message to exist at all (`sys.exit(1)` if
  absent — the exact signal that was *missing* in this TC-13 run), and
- its `"status"` field to equal `0`, and
- the actual document/field content parsed from the response bytes to
  match what's expected (not just "some response arrived" - TC-02/03
  check specific fields present/absent, TC-04 checks a proper
  subset-of-request relationship).

None of the three ever declared PASS based on iOS reaching a "Done"
button or a success screen alone. **No retrofit needed for TC-02/03/04
- their PASS verdicts already independently confirm Android's actual
receipt**, which is exactly the discipline TC-13 itself was built to
apply (and which caught this bug).

---

## Suggested next steps (not yet implemented)

1. This app should not navigate to a success screen on `.responseSent`
   alone. At minimum, treat any post-send disconnect that isn't
   preceded by explicit confirmation from the SDK as ambiguous, not
   successful — surface a distinct "connection lost, delivery not
   confirmed" state rather than silently reusing the happy path.
2. File/escalate against `eudi-lib-ios-wallet-kit` (or whichever
   package resolves as `EudiWalletKit` here) to determine whether
   `sendResponse()`/`waitForDisconnect()` can report a delivery
   confirmation distinct from "the local write queue drained and *a*
   disconnect eventually happened" - if the SDK genuinely cannot
   distinguish these, that's the deeper fix needed upstream.
3. Add a BLE-specific case to `PresentationSessionError` (or
   equivalent) so a future fix has somewhere to put a real "the
   verifier may not have received this" message instead of the generic
   fallback.

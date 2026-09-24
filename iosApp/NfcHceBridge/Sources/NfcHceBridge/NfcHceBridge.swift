//
//  NfcHceBridge.swift
//  NfcHceBridge
//
//  Copyright (c) 2026 European Commission
//
//  Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
//  Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
//  except in compliance with the Licence.
//
//  You may obtain a copy of the Licence at:
//  https://joinup.ec.europa.eu/software/page/eupl
//
//  Unless required by applicable law or agreed to in writing, software distributed under
//  the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
//  ANY KIND, either express or implied. See the Licence for the specific language
//  governing permissions and limitations under the Licence.
//
//  Structural reference: pagopa/iso18013-ios's NFCCardEmulator.swift (MIT-licensed) — the
//  `CardSession.eventStream` switch below follows the same shape (readerDetected / received /
//  sessionInvalidated), which is the part of that file that isn't ours to reinvent: it is how
//  CardSession's event loop has to be driven, not a design choice. Everything this file hands the
//  received bytes to (multipaz's `NfcTransportMdoc`) is this app's own stack, not pagopa's — see
//  wiki/IOS_NFC_PLAN.md §3.1/§4. The cool-down retry (in `respond(to:with:attempt:)`) and the stop
//  delay (in `stop()`) below are ALSO adapted from that file — see the comments at each — this is
//  phase 5's scope; see wiki/IOS_NFC_PLAN.md's phase 5 notes for what was adapted and why.
//
//  `NFCPresentmentIntentAssertion.acquire()` in `startCardSession()` is NOT adapted from pagopa —
//  checked directly against that repo's `NFCCardEmulator.swift` and it never actually calls it either
//  (see that method's own doc comment). It's applied straight from Apple's own CoreNFC documentation.
//

import CoreNFC
import Foundation

/// Implemented in Kotlin (`IosNfcHceTransport.ApduDelegate`). Answers one ISO 7816 command APDU;
/// `completion` must be called exactly once, with the response APDU bytes to send back over NFC.
///
/// Must match `shared-logic/src/nativeInterop/cinterop/NfcHceBridge.def` exactly — same class,
/// protocol and selector names — or the two sides describe different symbols and nothing links.
@objc(NfcHceBridgeDelegate) public protocol NfcHceBridgeDelegate: NSObjectProtocol {
    @objc(processCommandApdu:completion:)
    func processCommandApdu(_ commandApdu: Data, completion: @escaping (Data?) -> Void)

    /// Called when the card session ended without this bridge's own `stop()` having been called first
    /// — the user cancelled the system NFC sheet, or it timed out. Never called for an expected end
    /// (`stop()` already called, e.g. after a successful handover) — see `stop()`'s own doc comment for
    /// how that distinction is tracked.
    @objc(sessionEndedUnexpectedly)
    func sessionEndedUnexpectedly()
}

/// Owns a CoreNFC `CardSession` and feeds every APDU the reader sends to `NfcHceBridgeDelegate`.
///
/// `CardSession` (iOS 17.4+) is Swift-only — no Objective-C surface, so Kotlin/Native cannot cinterop
/// it directly (verified against this project's own SDK; see `wiki/IOS_NFC_PLAN.md` §3.1). This class
/// is the Objective-C-visible seam `NfcHceBridge.def` declares, so Kotlin can drive it.
///
/// ⚠️ **The explicit `@objc(NfcHceBridge)` / `@objc(NfcHceBridgeDelegate)` names are load-bearing.**
/// Without them, Swift exports this class and protocol under its own mangled name
/// (`_OBJC_CLASS_$__TtC12NfcHceBridge12NfcHceBridge`, embedding the module name) — but
/// `NfcHceBridge.def`'s hand-written plain Objective-C header has no way to know that mangling
/// scheme, so cinterop generates a binding against the *plain* name (`_OBJC_CLASS_$_NfcHceBridge`)
/// instead. Verified directly: building this file without the explicit names and inspecting the
/// resulting archive with `nm` shows only the mangled symbol, not the plain one cinterop expects.
@objc(NfcHceBridge) public class NfcHceBridge: NSObject {

    /// Named, cited constants rather than inline numbers — see each use below for which behavior each
    /// one governs and where it came from.
    private enum Timing {
        /// `respond(to:with:attempt:)` retries a failed APDU response up to this many times before
        /// giving up — adapted unchanged from pagopa/iso18013-ios's NFCCardEmulator.swift
        /// `handleResponse`, whose `counter > 10` guard allows attempts for counter 0...10: one
        /// initial attempt plus this many retries, 11 real calls to `respond(response:)` in total.
        /// Apple's CardSession sessions run for roughly 15 seconds before a roughly 15-second
        /// cool-down, during which `respond(response:)` can transiently throw
        /// `CardSession.Error.transmissionError` — this bound is pagopa's own measured answer to
        /// that, not a value this project derived independently.
        static let maxRespondRetries = 10

        /// `stop()` waits this long before invalidating an active session — adapted unchanged from
        /// pagopa/iso18013-ios's NFCCardEmulator.swift `stop()` (`Task.sleep(for: .seconds(3))`),
        /// which gives an in-flight response time to actually reach the reader instead of being cut
        /// off mid-transfer by an immediate `invalidate()`.
        ///
        /// **Real-device finding, tried and reverted (`wiki/IOS_NFC_PLAN.md` §9): an early-invalidation
        /// path (a fast BLE-`Requesting`-triggered preempt of this delay, plus a 500ms NFC-inactivity
        /// timer) was implemented, real-device tested, and found to cause BLE peripheral connection
        /// failures — CoreBluetooth's own peripheral stack needs close to the full `stopDelay` window to
        /// stabilize before a scanning reader can reliably discover it. Reverting to this delay as the
        /// sole teardown trigger restored a reliable, real, end-to-end transfer. Do not re-attempt this
        /// specific optimization without addressing that reliability cost first.**
        static let stopDelay: Duration = .seconds(3)
    }

    /// Mirrors the plain `NSInteger` codes `NfcHceBridge.def`'s `startWithCompletion:` doc comment
    /// specifies, and that `IosNfcHceTransport.kt`'s `NfcStartResult.fromCode` decodes back — see
    /// that doc comment for why an int, not a shared enum type, crosses the seam. Kept `private`:
    /// nothing outside this file needs the case names, only the raw values `start(completion:)`
    /// sends across.
    private enum StartResult: Int {
        case started = 0
        case notSupported = 1
        case notEligible = 2
        case accessNotAccepted = 3
        case transientFailure = 4
        /// `NFCPresentmentIntentAssertion.acquire()` failed with `.systemNotAvailable` — Apple's own
        /// documented assertion-level cooldown, distinct from `transientFailure`'s catch-all so Kotlin
        /// can show a message that explains the real constraint. See `startCardSession()`'s own doc
        /// comment for the cooldown's documented shape.
        case assertionCooldown = 5
    }

    /// Real-device correlation logging (`wiki/IOS_NFC_PLAN.md` §9): matches this codebase's existing
    /// Swift `print("TAG: message")` convention (see `iosApp/iosApp/iOSApp.swift`,
    /// `DocumentRegistration.swift`) — this file had no logging of its own before, so there was no
    /// established convention specifically here to match. The embedded ISO 8601 timestamp (with
    /// fractional seconds) is deliberately explicit rather than relying on the console's own capture
    /// time alone: it lines up directly, by literal string, against multipaz's `Logger` timestamps on
    /// the Kotlin side (`IosProximityPresenter.kt`/`IosNfcHceTransport.kt`), which format the same way
    /// — letting a device console log be read as one single timeline across the Swift/Kotlin boundary,
    /// which is the whole point when testing the `NFCPresentmentIntentAssertion` 15-second-expiry
    /// hypothesis against exactly when a physical tap happened.
    private static let logTimestampFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private func log(_ message: String) {
        print("NFC-HCE: \(Self.logTimestampFormatter.string(from: Date())): \(message)")
    }

    private let delegate: NfcHceBridgeDelegate
    /// Type-erased: `CardSession` itself is `@available(iOS 17.4, *)`, and a stored property of an
    /// always-constructible class cannot be typed with an availability-gated type directly.
    private var cardSession: Any?
    /// Same type-erasure reason as `cardSession`. See `startCardSession()`'s own doc comment for why
    /// this exists and what it does and doesn't cover.
    private var presentmentIntentAssertion: Any?

    /// Whether `stop()` has been called for the currently-armed session — real-device finding
    /// (`wiki/IOS_NFC_PLAN.md` §9): the Kotlin side had no way to distinguish a session `stop()` itself
    /// ended (expected — e.g. right after a successful Option 4 handover) from one that ended on its
    /// own (unexpected — the user cancelled the system sheet, or it timed out), so it kept believing
    /// cold-tap was still armed indefinitely after an unexpected end, silently ignoring every later tap.
    /// Set `true` synchronously inside `stop()`, reset `false` at the start of each fresh
    /// `startCardSession()` call — checked in the `eventStream` loop's own `.sessionInvalidated` case to
    /// decide whether to notify `delegate.sessionEndedUnexpectedly()`.
    private var stopRequested = false

    @objc(initWithDelegate:)
    public init(delegate: NfcHceBridgeDelegate) {
        self.delegate = delegate
        super.init()
    }

    /// The synchronous subset of `startCardSession()`'s own guard: device/OS support only, not
    /// Apple's `async CardSession.isEligible` region/hardware eligibility gate. Deliberately
    /// excludes `isEligible` — `isNfcDataRetrievalAvailable()` on the Kotlin side needs to answer
    /// instantly, at view-model-init time, and an `async` call is not the "cheap, synchronous,
    /// no-side-effect" check that call site can afford. A device this reports `true` for can still
    /// fail `startWithCompletion:` later (from `isEligible`, or an entitlement not yet granted) —
    /// that later failure is exactly what `StartResult`/`completion`'s richer answer is for; see
    /// `wiki/IOS_NFC_PLAN.md`'s Findings A/B/D/E notes.
    @objc(isSupported)
    public static func isSupported() -> Bool {
        guard #available(iOS 17.4, *) else { return false }
        return NFCReaderSession.readingAvailable && CardSession.isSupported
    }

    /// `completion` answers *why* card emulation did or didn't start, not just whether it did —
    /// see `StartResult` above. Distinguishing these matters to the Kotlin side now: an
    /// unsupported device, an unreviewed/not-yet-eligible one, and a merely transient failure call
    /// for different user-facing messages (or none at all) rather than one indistinguishable
    /// `false`.
    @objc(startWithCompletion:)
    public func start(completion: @escaping (Int) -> Void) {
        guard #available(iOS 17.4, *) else {
            completion(StartResult.notSupported.rawValue)
            return
        }
        Task {
            completion(await self.startCardSession().rawValue)
        }
    }

    @objc(stop)
    public func stop() {
        log("stop: called")
        // Set synchronously, before anything else — see stopRequested's own doc comment. This is what
        // lets the eventStream loop's own .sessionInvalidated case (reached later, whether from this
        // call's own delayed invalidate() below or from the session ending entirely on its own) tell an
        // expected end from an unexpected one.
        stopRequested = true
        // Releasing the reference (rather than waiting for its own 15-second expiry, see
        // startCardSession()'s doc comment) is deliberate: we no longer want to suppress the
        // system's default contactless app once we've stopped listening ourselves.
        presentmentIntentAssertion = nil
        guard #available(iOS 17.4, *), let session = cardSession as? CardSession else {
            cardSession = nil
            return
        }
        cardSession = nil
        Task {
            // Timing.stopDelay: see its doc comment — adapted from pagopa/iso18013-ios's stop().
            try? await Task.sleep(for: Timing.stopDelay)
            await session.stopEmulation(status: .success)
            session.invalidate()
        }
    }

    /// Real-device finding, not anticipated by Phase 1-6 or `wiki/IOS_NFC_PLAN.md` §9's Stage 1-4:
    /// tapping this app's HCE surface against a reader showed iOS's own system Wallet/contactless
    /// card picker instead of this `CardSession` ever answering. Traced against Apple's own
    /// documentation, not assumed: `NFCPresentmentIntentAssertion` is exactly the piece missing here.
    /// Apple's own documented sequence is "acquire the presentment intent assertion first using
    /// `acquire()`, then create a `CardSession`" — without it, the system has no signal that this
    /// foreground app wants exclusive use of the NFC field the moment a tap is detected, so its own
    /// default-contactless-app routing decides instead, before this `CardSession` is ever consulted.
    ///
    /// **Not adapted from pagopa/iso18013-ios.** Checked directly against that repo's own
    /// `NFCCardEmulator.swift` as a sanity check: it declares a `presentmentIntent:
    /// NFCPresentmentIntentAssertion?` stored property and a comment referencing "failure to acquire
    /// NFC presentment intent assertion," but never actually calls `.acquire()` anywhere — the
    /// property is only ever assigned `nil`. That file is not a working reference for this piece; it
    /// has the same gap this app did. This is standard Apple platform API usage, applied directly
    /// from Apple's own `NFCPresentmentIntentAssertion` documentation.
    ///
    /// **Known limitation, not solved here — flagged rather than silently accepted.** Per Apple's own
    /// documentation, an acquired assertion is hard-capped at 15 seconds (it also expires early if
    /// this object deinitializes or the app backgrounds), followed by a mandatory 15-second cool-down
    /// before a new one can be acquired — so one acquisition, done here right before constructing
    /// `CardSession`, covers only the window immediately around arming and a reader's first
    /// encounter, not an entire multi-minute screen visit (`IosProximityPresenter`'s cold-tap
    /// engagement can stay armed far longer than 15 seconds, per `onScreenEntered`/`onScreenExited`).
    /// Apple's API gives no way to hold this continuously — even reacquiring immediately on expiry
    /// still has an unavoidable 15-second gap where suppression lapses. Whether that gap matters in
    /// practice (most taps likely happen well within the first 15 seconds of arming) is unverified;
    /// see `wiki/IOS_NFC_PLAN.md` §9 for this as an open, undecided item alongside Stage 3's other two.
    ///
    /// **Surfaced to the user, not just logged**: a real-device report showed this cooldown reachable
    /// through an ordinary flow — cancel the system sheet (or let it time out), then immediately
    /// re-enable "Share over NFC". `.systemNotAvailable` below returns `.assertionCooldown`, a distinct
    /// `StartResult` from the generic `.transientFailure`, so `IosProximityPresenter` can show a message
    /// naming the real constraint instead of a generic failure, and turn the switch back off — see
    /// `wiki/IOS_NFC_PLAN.md` §9 for the full writeup, including why this is a different cooldown than
    /// the `CardSession`-level one `Timing.maxRespondRetries` was tuned for.
    @available(iOS 17.4, *)
    private func startCardSession() async -> StartResult {
        // Reset for this fresh session — see stopRequested's own doc comment for why: a stale `true`
        // left over from a *previous* session's own stop() would misclassify this new session's own,
        // later, genuinely unexpected end as expected.
        stopRequested = false
        guard NFCReaderSession.readingAvailable, CardSession.isSupported else {
            log("startCardSession: not supported")
            return .notSupported
        }
        guard await CardSession.isEligible else {
            log("startCardSession: not eligible (CardSession.isEligible)")
            return .notEligible
        }

        log("startCardSession: acquiring NFCPresentmentIntentAssertion")
        let intentAssertion: NFCPresentmentIntentAssertion
        do {
            intentAssertion = try await NFCPresentmentIntentAssertion.acquire()
            log("startCardSession: NFCPresentmentIntentAssertion acquired")
        } catch NFCPresentmentIntentAssertion.Error.systemEligibilityFailed {
            log("startCardSession: NFCPresentmentIntentAssertion.acquire() failed: systemEligibilityFailed")
            return .notEligible
        } catch NFCPresentmentIntentAssertion.Error.systemNotAvailable {
            log("startCardSession: NFCPresentmentIntentAssertion.acquire() failed: systemNotAvailable (cool-down)")
            return .assertionCooldown
        } catch {
            log("startCardSession: NFCPresentmentIntentAssertion.acquire() failed: \(error)")
            return .transientFailure
        }
        presentmentIntentAssertion = intentAssertion

        log("startCardSession: constructing CardSession")
        let session: CardSession
        do {
            session = try await CardSession()
            log("startCardSession: CardSession constructed, starting emulation")
            try await session.startEmulation()
            log("startCardSession: CardSession.startEmulation() succeeded")
        } catch CardSession.Error.accessNotAccepted {
            log("startCardSession: CardSession construction/startEmulation failed: accessNotAccepted")
            presentmentIntentAssertion = nil
            return .accessNotAccepted
        } catch {
            log("startCardSession: CardSession construction/startEmulation failed: \(error)")
            presentmentIntentAssertion = nil
            return .transientFailure
        }
        cardSession = session

        Task {
            for try await event in session.eventStream {
                switch event {
                case .readerDetected:
                    log("eventStream: readerDetected")
                    if await !session.isEmulationInProgress {
                        try? await session.startEmulation()
                    }

                case .received(let cardAPDU):
                    log("eventStream: received APDU (\(cardAPDU.payload.count) bytes)")
                    delegate.processCommandApdu(cardAPDU.payload) { response in
                        guard let response else { return }
                        Task {
                            await self.respond(to: cardAPDU, with: response)
                        }
                    }

                case .sessionInvalidated(let reason):
                    log("eventStream: sessionInvalidated: \(reason)")
                    await session.stopEmulation(status: .success)
                    // See stopRequested's own doc comment — real-device finding, wiki/IOS_NFC_PLAN.md §9.
                    // Only notify for the unexpected case: an expected end (our own stop() already
                    // called, e.g. right after a successful Option 4 handover) has nothing new to tell
                    // Kotlin — its own explicit call sites already handle that teardown correctly.
                    if !self.stopRequested {
                        self.cardSession = nil
                        self.delegate.sessionEndedUnexpectedly()
                    }

                default:
                    break
                }
            }
            log("eventStream: loop ended")
        }

        log("startCardSession: started")
        return .started
    }

    /// Adapted from pagopa/iso18013-ios's NFCCardEmulator.swift `handleResponse`: `respond(response:)`
    /// can transiently throw `CardSession.Error.transmissionError` — specifically that case, during
    /// the NFC HCE cool-down window between sessions — so retry exactly that failure, bounded by
    /// `Timing.maxRespondRetries`, and give up silently on any other error or once the bound is hit.
    /// A silent give-up matches this bridge's existing contract: `NfcHceBridgeDelegate.completion`
    /// has already fired by the time this runs, there is no caller left to report a throw to, and
    /// multipaz's own `NfcTransportMdoc` already treats a response that never arrives as a dead
    /// transport (see its `failTransport` path) rather than needing this bridge to say so twice.
    @available(iOS 17.4, *)
    private func respond(to cardAPDU: CardSession.APDU, with response: Data, attempt: Int = 0) async {
        do {
            try await cardAPDU.respond(response: response)
        } catch CardSession.Error.transmissionError where attempt < Timing.maxRespondRetries {
            await respond(to: cardAPDU, with: response, attempt: attempt + 1)
        } catch {
            // Any other CardSession.Error (or the retry bound reached) — terminal, same as pagopa's
            // own `default: break` for non-transmissionError cases.
        }
    }
}

/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

import EudiRQESUi
import RqesKit
import SwiftUI
import UniformTypeIdentifiers
import class SharedKit.IosDocumentSigning
import protocol SharedKit.IosDocumentSigner
import class SharedKit.KotlinBoolean

/// The signing service the wallet offers, mirroring Android's `RQESConfigImpl`.
///
/// `documentRetrievalConfig` has no counterpart here — the iOS library has no remote-URI entry point
/// at 0.4.3 — so signing a document fetched from a QR or deep link stays Android-only. What this
/// build can do is sign a document the user picks from Files, which is the flow below.
/// The scheme the QTSP redirects the authorization result to; registered in `project.yml`.
let rqesCallbackScheme = "rqes"

struct RQESConfig: EudiRQESUiConfig {

    var rssps: [QTSPData] {
        [
            QTSPData(
                name: "Wallet-Centric",
                rsspId: "https://walletcentric.signer.dev.eudiw.dev/csc/v2",
                tsaUrl: "https://timestamp.sectigo.com/qualified",
                clientId: "wallet-client",
                clientSecret: "somesecret2",
                // The same redirect Android builds as BuildConfig.RQES_DEEPLINK, and the reason
                // `rqes` is registered as a URL scheme in project.yml: the QTSP sends the
                // authorization result back to it.
                authFlowRedirectionURI: "rqes://oauth/callback",
                hashAlgorithm: .SHA256,
                includeRevocationInfo: true
            )
        ]
    }

    var printLogs: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }
}

/// The Swift half of `rememberDocumentSignTrigger`: pick a PDF, hand it to the RQES SDK.
///
/// Registered into `IosDocumentSigning.shared.signer` at launch. Kotlin cannot do any of this
/// itself — `EudiRQESUi` is a Swift package, so none of its API is visible across the Kotlin/Native
/// bridge — which is why this seam runs Swift-side rather than through cinterop like the camera.
/// Carries a Kotlin callback across an isolation boundary it never actually crosses at runtime.
private struct OutcomeBox: @unchecked Sendable {
    let call: (KotlinBoolean, String?) -> Void
}

/// `@unchecked Sendable` is safe here and checked by hand: every stored property below is
/// `@MainActor`-isolated, so the only thing crossing threads is the reference itself.
final class WalletDocumentSigner: NSObject, IosDocumentSigner, @unchecked Sendable {

    /// One instance for the app, because the authorization redirect has to come back to the same
    /// `EudiRQESUi` that started the flow — `resume` continues a session `initiate` opened.
    static let shared = WalletDocumentSigner()

    /// Held for the lifetime of the app.
    ///
    /// `EudiRQESUi.init` stores itself in a private static and `initiate` is an instance method, so
    /// there is no public way back to it — the reference has to be ours.
    @MainActor
    private lazy var rqes = EudiRQESUi(config: RQESConfig())

    /// Retained only while a picker is on screen; `UIDocumentPickerViewController` holds its
    /// delegate weakly, the same trap the QR scanner's metadata delegate has.
    @MainActor
    private var activePicker: UIDocumentPickerViewController?
    @MainActor
    private var pending: ((KotlinBoolean, String?) -> Void)?

    /// `IosDocumentSigner` is a Kotlin protocol and therefore carries no actor isolation, while
    /// everything this does — UIKit presentation, and `EudiRQESUi`, which is `@MainActor` — must run
    /// on the main actor. Kotlin only ever calls this from Compose's main thread, so state that
    /// rather than hop and lose the ordering. (This used to say "the same `assumeIsolated` the
    /// `BGTask` handler uses"; that handler was removed on 2026-09-04.)
    nonisolated func selectAndSign(onOutcome: @escaping (KotlinBoolean, String?) -> Void) {
        // The callback comes from Kotlin, so Swift cannot see that it is safe to hand to the main
        // actor and refuses to "send" it. Boxing states what the bridge guarantees: Kotlin calls
        // this on Compose's main thread, and the box is only ever opened there.
        let box = OutcomeBox(call: onOutcome)
        MainActor.assumeIsolated { present(onOutcome: box.call) }
    }

    @MainActor
    private func present(onOutcome: @escaping (KotlinBoolean, String?) -> Void) {
        guard let presenter = Self.topViewController() else {
            onOutcome(false, "Could not find a screen to present the document picker on.")
            return
        }

        pending = onOutcome

        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf])
        picker.allowsMultipleSelection = false
        picker.delegate = self
        activePicker = picker
        presenter.present(picker, animated: true)
    }

    /// Continues a signing flow after the QTSP redirects back to `rqes://oauth/callback`.
    ///
    /// Returns false for a URL that is not ours or carries no `code`, so `AppDelegate` can go on to
    /// offer it to the other handlers.
    ///
    /// 🪤 The code and the presenter used to be conditions of ONE `guard`, and that dropped the
    /// authorization on the floor. Arriving from Safari, this runs while the scene is still coming
    /// forward, so `topViewController()` — which insists on a `.foregroundActive` scene — answers nil.
    /// The guard then failed on the *presenter* while blaming the *code*, logging "no authorization
    /// code" for a URL that plainly carried one, and returning false handed our own callback to the
    /// other handlers, which declined it. Measured on an iPhone 2026-09-08:
    ///
    ///     RQES-CALLBACK: no authorization code in rqes://oauth/callback?code=cTDST2Fb…
    ///     OPEN-URL: ignored rqes
    ///
    /// So the two questions are asked separately. A URL carrying a code **is ours**, whatever the scene
    /// is doing, so claim it and then wait for somewhere to present on.
    @discardableResult
    @MainActor
    func resume(url: URL) -> Bool {
        guard url.scheme == rqesCallbackScheme else { return false }
        guard
            let code = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?
                .first(where: { $0.name == "code" })?
                .value
        else {
            print("RQES-CALLBACK: no authorization code in \(url.absoluteString.prefix(60))")
            return false
        }

        Task { @MainActor in
            guard let presenter = await Self.awaitTopViewController() else {
                print("RQES-CALLBACK: no view controller to continue the signing flow on")
                return
            }
            do {
                try await rqes.resume(on: presenter, authorizationCode: code)
            } catch {
                print("RQES-CALLBACK: resume failed — \(error.localizedDescription)")
            }
        }
        return true
    }

    /// [topViewController] once the scene is actually forward, or nil if it never gets there.
    ///
    /// A returning app is mid-activation when its URL is delivered, so the first look can legitimately
    /// find nothing; a short poll is enough, and is the same lesson the document picker taught — resolve
    /// the presenter at the moment of use, not before. Bounded so a scene that never activates ends in a
    /// log line rather than a task that waits for ever.
    @MainActor
    private static func awaitTopViewController(
        attempts: Int = 20,
        every: Duration = .milliseconds(100)
    ) async -> UIViewController? {
        for _ in 0..<attempts {
            if let top = topViewController() { return top }
            try? await Task.sleep(for: every)
        }
        return topViewController()
    }

    /// The frontmost view controller, so the picker appears above whatever Compose is showing.
    @MainActor
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.keyWindow?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }

    @MainActor
    private func finish(cancelled: Bool, error: String?) {
        activePicker = nil
        let outcome = pending
        pending = nil
        outcome?(KotlinBoolean(bool: cancelled), error)
    }
}

extension WalletDocumentSigner: UIDocumentPickerDelegate {

    func documentPicker(
        _ controller: UIDocumentPickerViewController,
        didPickDocumentsAt urls: [URL]
    ) {
        guard let url = urls.first else {
            finish(cancelled: true, error: nil)
            return
        }

        // A file chosen from Files arrives outside the app's sandbox, so it has to be opened inside
        // a security-scoped block or the SDK cannot read it. Copied into our own temporary directory
        // rather than passed straight through: the scope ends when this call returns, while the SDK
        // reads the file later, on its own screens.
        guard let local = Self.copyIntoTemporaryDirectory(url) else {
            finish(cancelled: false, error: "That document could not be opened.")
            return
        }

        // 🪤 `controller.presentingViewController` is ALREADY nil here: iOS dismisses the document
        // picker itself before calling this delegate, so the picker is detached by the time we run.
        // The original code read `controller.presentingViewController ?? controller`, which therefore
        // always fell through to the dismissed picker — a controller with no window — and the SDK
        // returned without presenting anything, without throwing. That is why picking a document did
        // nothing at all on device, for as long as this feature has existed (found 2026-09-08, the
        // first time it was run on an iPhone).
        //
        // So resolve the presenter fresh, once the dismissal has settled, rather than deriving it
        // from the picker: `topViewController()` is the same helper that presented the picker, and
        // the dismissal's completion is what puts us after it.
        controller.dismiss(animated: true) { [weak self] in
            guard let self else { return }
            let top = Self.topViewController()
            Task { @MainActor in
                guard let presenter = top else {
                    print("DOCUMENT-SIGN: no view controller available to present the signing UI")
                    self.finish(cancelled: false, error: "Document signing could not be opened.")
                    return
                }
                do {
                    try await self.rqes.initiate(on: presenter, fileUrl: local)
                    self.finish(cancelled: false, error: nil)
                } catch {
                    self.finish(cancelled: false, error: error.localizedDescription)
                }
            }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finish(cancelled: true, error: nil)
    }

    private static func copyIntoTemporaryDirectory(_ url: URL) -> URL? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
            let file = destination.appendingPathComponent(url.lastPathComponent)
            try FileManager.default.copyItem(at: url, to: file)
            return file
        } catch {
            print("DOCUMENT-SIGN: could not copy the picked document — \(error.localizedDescription)")
            return nil
        }
    }
}

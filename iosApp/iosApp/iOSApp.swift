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

import SwiftUI
import class SharedKit.WalletEngineProbeKt
import class SharedKit.IosAuthorizationRedirects
import class SharedKit.IosDeepLinks
import class SharedKit.BackgroundReIssuanceSummary
import class SharedKit.IosBackgroundRevocationKt
import class SharedKit.BackgroundRevocationSummary
import class SharedKit.IosDocumentSigning
import class SharedKit.IosDocumentRegistration

/// Launch argument that turns the wallet probe on.
///
/// The probe is how iOS behaviour is verified — `simctl` cannot tap, so a console run is the only way
/// to see a screen's interactor work — but it is emphatically not something a launch should do by
/// itself: it seeds fixture documents, sets a PIN, issues, presents and re-issues, all against the
/// real store. Off unless asked for:
///
///     xcrun simctl launch --console-pty <device> <bundle-id> --wallet-probe
private let walletProbeArgument = "--wallet-probe"

/// Receives URLs opened on the app: the OpenID4VCI authorization redirect, credential offers, and a
/// verifier's OpenID4VP presentation request.
///
/// A `UIApplicationDelegate` rather than SwiftUI's `.onOpenURL`: the latter did not fire for a custom
/// scheme delivered to an already-running app on the simulator, and `application(_:open:options:)` is
/// the path deep links have always arrived on. Both are wired, so whichever the system chooses works.
final class AppDelegate: NSObject, UIApplicationDelegate {

    /// Launch wiring: the revocation refresh, the document-registration seam, and the RQES signer.
    ///
    /// 📌 A `BGProcessingTask` used to be registered here, because iOS requires every task identifier
    /// to be registered before this method returns. It was **removed 2026-09-04** so the wallet
    /// database can carry `NSFileProtectionComplete` — see [refreshRevocationOnLaunch].
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        refreshRevocationOnLaunch()

        // Lets Kotlin tell us when the document set changes, so a deleted document leaves the system
        // credential picker instead of lingering in it. Registered before the first reconciliation
        // below, though nothing depends on the order: the seam is only read on a later delete.
        IosDocumentRegistration.shared.registry = WalletDocumentRegistry.shared

        // Brings the OS registry in line with the wallet: adds what is missing, removes what is gone.
        // The first of the two halves of being a Digital Credentials API provider; the second — an
        // ExtensionKit extension that answers a request — does not exist yet, and this is useful and
        // observable without it. See `DocumentRegistration.swift`.
        reconcileDocumentRegistrations()

        // The Swift half of document signing. Kotlin cannot call `EudiRQESUi` itself — it is a Swift
        // package, so none of its API crosses the Kotlin/Native bridge — so the shared screen calls
        // this instead. Without this line signing reports itself unavailable rather than crashing;
        // `HomeInteractor.canSignDocuments` is what decides the card is offered at all.
        IosDocumentSigning.shared.signer = WalletDocumentSigner.shared
        print("DOCUMENT-SIGN: registered the RQES signer")
        return true
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        // Two kinds of URL reach the wallet, and each has its own destination in Kotlin: an
        // authorization redirect belongs to a flow already waiting for it, while a credential offer or
        // a presentation request starts one. Authorization is tried first because it is the narrower
        // match — `IosDeepLinks` decides between the other two by scheme.
        // The RQES authorization result belongs to a signing flow the SDK is already running, and it
        // is the narrowest match of the three, so it is offered first.
        if WalletDocumentSigner.shared.resume(url: url) {
            print("RQES-CALLBACK: delivered \(url.absoluteString.prefix(60))")
            return true
        }
        if IosAuthorizationRedirects.shared.deliver(url: url.absoluteString) {
            print("AUTHORIZATION-REDIRECT: delivered \(url.absoluteString.prefix(60))")
            return true
        }
        if IosDeepLinks.shared.deliver(url: url.absoluteString) {
            print("DEEP-LINK: delivered \(url.absoluteString.prefix(60))")
            return true
        }
        print("OPEN-URL: ignored \(url.absoluteString.prefix(60))")
        return false
    }
}

@MainActor
/// Refreshes revocation status once per launch, without blocking startup.
///
/// **The only revocation trigger on iOS, by decision (2026-09-04).** A `BGProcessingTask` used to sweep
/// re-issuance and revocation with the app closed. It was removed so the wallet database can carry
/// `NSFileProtectionComplete`: a file unreadable while the device is locked is incompatible with any
/// background work, which is exactly why that protection class had been rejected before. The wallet is
/// closer to the official iOS app for it — that app runs no background tasks either, which is what lets
/// it keep documents in the Keychain under a passcode-required class.
///
/// The cost was measured before it was accepted: iOS was never observed scheduling that task on its own
/// (61 minutes, locked, on power, Low Power Mode off), and when it was driven by hand every real refresh
/// failed because the issuer's refresh token had already expired. What was given up is a feature never
/// seen working unattended; what was gained is a protection class measured working.
///
/// ⛔ Android keeps `RevocationWorkManager` every 15 minutes. **A deliberate per-platform divergence,
/// not a gap — do not "restore parity" by adding the task back.**
///
/// Detached and unawaited on purpose: nothing on screen depends on the answer, and the Documents screen
/// re-reads the store when it appears, so a flag set a second after launch still shows. Failure is
/// already swallowed and logged inside `runBackgroundRevocation`.
///
/// Once per process rather than on every foreground, which keeps it free of any "last refreshed"
/// bookkeeping while still guaranteeing the status is no older than the session the user is looking at.
private func refreshRevocationOnLaunch() {
    Task { @MainActor in
        do {
            let summary = try await IosBackgroundRevocationKt.runBackgroundRevocation()
            print("LAUNCH-REVOCATION: \(summary)")
        } catch {
            print("LAUNCH-REVOCATION: failed — \(error)")
        }
    }
}


@main
struct iOSApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        guard ProcessInfo.processInfo.arguments.contains(walletProbeArgument) else { return }

        WalletEngineProbeKt.probeMultipazWalletEngine { line in
            print("MULTIPAZ-ENGINE: \(line)")
        }
    }

    var body: some Scene {
        WindowGroup {
            // The shared Compose UI *is* the app. It used to be one tab of two, beside a SwiftUI
            // screen that proved Swift could call Kotlin; that question was answered a long time ago
            // and the tab bar outlived it.
            WalletView()
                .ignoresSafeArea()
            // The whole of the app shell's part in OpenID4VCI authorization: hand the redirect to
            // Kotlin, which has a coroutine waiting for it. Deciding whether it is *valid* belongs to
            // multipaz's provisioning client, which minted the `state` it carries.
            .onOpenURL { url in
                // Same destinations as the app delegate above; whichever path the system picks, the
                // URL ends up in the same place.
                if WalletDocumentSigner.shared.resume(url: url) {
                    print("RQES-CALLBACK: delivered \(url.scheme ?? "?")")
                } else if IosAuthorizationRedirects.shared.deliver(url: url.absoluteString) {
                    print("AUTHORIZATION-REDIRECT: delivered \(url.scheme ?? "?")")
                } else if IosDeepLinks.shared.deliver(url: url.absoluteString) {
                    print("DEEP-LINK: delivered \(url.scheme ?? "?")")
                } else {
                    print("OPEN-URL: ignored \(url.scheme ?? "?")")
                }
            }
        }
    }
}

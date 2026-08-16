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
    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        // Two kinds of URL reach the wallet, and each has its own destination in Kotlin: an
        // authorization redirect belongs to a flow already waiting for it, while a credential offer or
        // a presentation request starts one. Authorization is tried first because it is the narrower
        // match — `IosDeepLinks` decides between the other two by scheme.
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
                if IosAuthorizationRedirects.shared.deliver(url: url.absoluteString) {
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

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

/// Receives URLs opened on the app — the OpenID4VCI authorization redirect, today.
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
        let handled = IosAuthorizationRedirects.shared.deliver(url: url.absoluteString)
        print("AUTHORIZATION-REDIRECT: \(handled ? "delivered" : "ignored") \(url.absoluteString.prefix(60))")
        return handled
    }
}

@main
struct iOSApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        // SPIKE: hand a Swift-implemented WalletEngine to Kotlin coroutine code and see whether the
        // suspend calls actually round-trip. Result goes to the console.
        WalletEngineProbeKt.probeWalletEngine(engine: SwiftWalletEngine()) { result in
            print("WALLET-ENGINE-SPIKE: \(result)")
        }

        // The real thing: the Kotlin-over-multipaz iOS document layer, reading a fixture document it
        // seeds into multipaz's DocumentStore. iOS has no issuance yet and the Documents screen is
        // not shared, so the console is the only place this is visible.
        WalletEngineProbeKt.probeMultipazWalletEngine { line in
            print("MULTIPAZ-ENGINE: \(line)")
        }
    }

    var body: some Scene {
        WindowGroup {
            // The Compose Multiplatform spike is the root while we establish that shared view-models
            // can drive real iOS UI. ContentView (the SwiftUI-calling-Kotlin spike from Phase 0) is
            // still in the target and reachable from the tab below, so both paths stay exercised.
            TabView {
                ComposeSpikeView()
                    .ignoresSafeArea()
                    .tabItem { Label("Compose MP", systemImage: "square.stack.3d.up") }

                ContentView()
                    .tabItem { Label("SwiftUI", systemImage: "swift") }
            }
            // The whole of the app shell's part in OpenID4VCI authorization: hand the redirect to
            // Kotlin, which has a coroutine waiting for it. Deciding whether it is *valid* belongs to
            // multipaz's provisioning client, which minted the `state` it carries.
            .onOpenURL { url in
                let handled = IosAuthorizationRedirects.shared.deliver(url: url.absoluteString)
                print("AUTHORIZATION-REDIRECT: \(handled ? "delivered" : "ignored") \(url.scheme ?? "?")")
            }
        }
    }
}

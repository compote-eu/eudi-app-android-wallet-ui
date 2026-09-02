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

import BackgroundTasks
import SwiftUI
import class SharedKit.WalletEngineProbeKt
import class SharedKit.IosAuthorizationRedirects
import class SharedKit.IosDeepLinks
import class SharedKit.IosBackgroundReIssuanceKt
import class SharedKit.BackgroundReIssuanceSummary
import class SharedKit.IosBackgroundRevocationKt
import class SharedKit.BackgroundRevocationSummary
import class SharedKit.IosDocumentSigning

/// Launch argument that turns the wallet probe on.
///
/// The probe is how iOS behaviour is verified — `simctl` cannot tap, so a console run is the only way
/// to see a screen's interactor work — but it is emphatically not something a launch should do by
/// itself: it seeds fixture documents, sets a PIN, issues, presents and re-issues, all against the
/// real store. Off unless asked for:
///
///     xcrun simctl launch --console-pty <device> <bundle-id> --wallet-probe
private let walletProbeArgument = "--wallet-probe"

/// The background credential top-up, matching `BGTaskSchedulerPermittedIdentifiers` in `project.yml`.
///
/// Editing one without the other is a launch-time crash, not a silent no-op: `BGTaskScheduler.register`
/// raises `NSInternalInconsistencyException` for an identifier the plist does not permit.
private let reIssuanceTaskIdentifier = "eu.europa.ec.euidi.reissuance"

/// The interval Android's `ReIssuanceWorkManager` runs at, used here as an *earliest* start.
///
/// It is a floor and nothing more. WorkManager will roughly honour a period; `BGTaskScheduler` decides
/// for itself, weighing charge, network and how much the user opens the app, and may leave a task
/// unrun for days. That is why the wallet must not depend on this having happened — every path that
/// needs credentials still tops them up in the foreground.
private let reIssuanceEarliestInterval: TimeInterval = 15 * 60

/// Receives URLs opened on the app: the OpenID4VCI authorization redirect, credential offers, and a
/// verifier's OpenID4VP presentation request.
///
/// A `UIApplicationDelegate` rather than SwiftUI's `.onOpenURL`: the latter did not fire for a custom
/// scheme delivered to an already-running app on the simulator, and `application(_:open:options:)` is
/// the path deep links have always arrived on. Both are wired, so whichever the system chooses works.
final class AppDelegate: NSObject, UIApplicationDelegate {

    /// Registers the background top-up, then asks for the first run.
    ///
    /// Registration has to happen here and nowhere later: iOS requires every task identifier to be
    /// registered before `application(_:didFinishLaunchingWithOptions:)` returns, and rejects a
    /// registration made afterwards.
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: reIssuanceTaskIdentifier,
            using: .main // the work hops straight onto a Kotlin coroutine, so the queue only dispatches
        ) { task in
            // `BGTask` is not `Sendable` and Swift 6 cannot see that `using: .main` already puts this
            // closure on the main actor, so state the isolation rather than copying the task across it.
            MainActor.assumeIsolated { handleBackgroundReIssuance(task) }
        }
        print("BACKGROUND-REISSUANCE: registered \(reIssuanceTaskIdentifier)")
        scheduleBackgroundReIssuance()
        refreshRevocationOnLaunch()

        // Tells iOS which documents this wallet holds, so they appear in the system credential
        // picker. The first of the two halves of being a Digital Credentials API provider; the
        // second — an ExtensionKit extension that answers a request — does not exist yet, and this
        // is useful and observable without it. See `DocumentRegistration.swift`.
        registerDocumentsWithSystem()

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

/// Queues the next top-up.
///
/// Submitted on every launch and again at the start of each run, because a `BGTaskScheduler` request is
/// consumed when it fires: an app that only submits at launch gets exactly one background run per
/// launch, which for a wallet that is rarely opened is close to none.
///
/// A `BGProcessingTaskRequest` rather than `BGAppRefreshTask`: this does network I/O and key
/// generation in the Secure Enclave, which is processing work, and the refresh window an app-refresh
/// task gets is measured in seconds.
private func scheduleBackgroundReIssuance() {
    let request = BGProcessingTaskRequest(identifier: reIssuanceTaskIdentifier)
    request.requiresNetworkConnectivity = true
    // Not external power: topping up credentials is cheap, and requiring a charger on a phone that is
    // never plugged in overnight would mean never running at all.
    request.requiresExternalPower = false
    request.earliestBeginDate = Date(timeIntervalSinceNow: reIssuanceEarliestInterval)

    do {
        try BGTaskScheduler.shared.submit(request)
    } catch {
        // Expected and harmless in the simulator, which has no scheduler: `BGTaskSchedulerErrorDomain`
        // code 1 (unavailable). Worth printing rather than ignoring, since the same error on a device
        // means the app is in the background-refresh-disabled state and will never be topped up.
        print("BACKGROUND-REISSUANCE: could not schedule — \(error)")
    }
}

/// Runs one sweep and reports honestly whether it finished.
///
/// `setTaskCompleted(success:)` is the signal iOS uses to decide how generously to schedule this app in
/// future, so a failed sweep says so rather than claiming success. The next request is queued *first*,
/// so a crash or an expiration in the work below cannot end the chain.
@MainActor
/// Refreshes revocation status once per launch, without blocking startup.
///
/// This is the trigger the wallet can actually depend on. The `BGProcessingTask` above is the only path
/// that runs with the app closed, but `BGTaskScheduler` may leave it unrun for days — so on its own it
/// would leave a revoked credential displaying as valid for exactly as long as the user keeps opening
/// the app without the system granting background time. Android has no such uncertainty: its
/// `RevocationWorkManager` is enqueued every 15 minutes.
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

// `@MainActor` because that is already true: the registration closure uses `using: .main` and calls
// this inside `MainActor.assumeIsolated`. Stating it lets Swift 6 see that `BGTask` — which is not
// `Sendable` — never crosses an isolation boundary. Without it, adding the second `await` below made the
// compiler treat `task` as sent into the child task.
@MainActor
private func handleBackgroundReIssuance(_ task: BGTask) {
    scheduleBackgroundReIssuance()

    let work = Task { @MainActor in
        do {
            let summary = try await IosBackgroundReIssuanceKt.runBackgroundReIssuance()
            print("BACKGROUND-REISSUANCE: \(summary)")

            // Revocation rides the same task rather than getting its own identifier. The official iOS
            // wallet pairs its two workers the same way, iOS would not grant a second task any more
            // time, and a new identifier means editing `BGTaskSchedulerPermittedIdentifiers` in
            // `project.yml` — a mismatch there is a launch-time crash. The identifier keeps its
            // re-issuance name because renaming it costs that risk for no behavioural gain.
            let revocation = try await IosBackgroundRevocationKt.runBackgroundRevocation()
            print("BACKGROUND-REVOCATION: \(revocation)")

            task.setTaskCompleted(success: true)
        } catch is CancellationError {
            print("BACKGROUND-REISSUANCE: cancelled by the system")
            task.setTaskCompleted(success: false)
        } catch {
            print("BACKGROUND-REISSUANCE: failed — \(error)")
            task.setTaskCompleted(success: false)
        }
    }

    // iOS gives a few seconds' warning before it kills the task. Cancelling the Kotlin coroutine lets
    // the sweep stop between documents rather than mid-issuance.
    task.expirationHandler = { work.cancel() }
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

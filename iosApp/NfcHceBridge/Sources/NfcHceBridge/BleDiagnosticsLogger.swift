//
//  BleDiagnosticsLogger.swift
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
//  Purely diagnostic, no behavior change — real-device finding (wiki/IOS_NFC_PLAN.md §9, "Seventh
//  real-device finding"): BLE discovery unreliability was traced to the app losing foreground focus
//  while cold-tap is armed, but that trace relied on inference (backgrounding is the only documented
//  mechanism that fits), not direct observation of app-lifecycle/Bluetooth-radio state during the
//  actual advertising window on a real device. This logs both directly, so the next real-device test
//  can show exactly what state the app/radio were in when BLE discovery succeeds or fails.
//

import CoreBluetooth
import Foundation
import UIKit

/// Logs `UIApplication` lifecycle transitions and the *system* Bluetooth radio's own power state
/// around Option 4's BLE advertising/connection window.
///
/// **Does not, and cannot, observe multipaz's own `BlePeripheralManagerIos` directly** — that class,
/// and the `CBPeripheralManager` instance it owns internally, are private to multipaz, a dependency
/// this app has no source access to (the same cross-repo boundary noted elsewhere — `wiki/IOS_NFC_PLAN.md`
/// §8). The `CBPeripheralManager` this class creates is its own, separate, diagnostic-only instance —
/// safe, and a common pattern for observing the *shared* system radio's power/authorization state
/// (`.state`, via `peripheralManagerDidUpdateState`) — but its own `isAdvertising` is always `false`
/// (this instance never advertises anything) and is deliberately not logged, since that would
/// misrepresent multipaz's own advertising as this instance's. `willRestoreState` is implemented for
/// completeness but is not expected to ever fire here: it only fires for a manager created with a
/// matching restoration identifier, which this diagnostic instance does not use, and whether
/// multipaz's own manager uses one is opaque to this app.
@objc(BleDiagnosticsLogger) public class BleDiagnosticsLogger: NSObject, CBPeripheralManagerDelegate {

    private enum Timing {
        /// Long enough to cover the start of a typical BLE discovery/connect attempt — real-device
        /// reports of this window running as long as ~40 seconds exist, but this is meant to catch an
        /// early background transition, not run for the full worst-case length, so logging doesn't run
        /// indefinitely.
        static let sampleWindow: Duration = .seconds(20)
        static let sampleInterval: Duration = .seconds(1)
    }

    private static let logTimestampFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private func log(_ message: String) {
        // Same "TAG: timestamp: message" convention as NfcHceBridge.swift's own log(_:) — a different
        // tag (BLE-DIAG, not NFC-HCE) so the two are easy to grep for separately in one device console
        // capture that has both.
        print("BLE-DIAG: \(Self.logTimestampFormatter.string(from: Date())): \(message)")
    }

    private var peripheralManager: CBPeripheralManager?
    private var sampleTask: Task<Void, Never>?
    private var lifecycleObservers: [NSObjectProtocol] = []

    @objc override public init() {
        super.init()
    }

    /// One-shot snapshot for a single known moment in the flow (arm time, handover-complete time) —
    /// `label` names which one in the log line. Safe to call from any thread; hops to the main actor
    /// itself, since `UIApplication.shared`/`CBPeripheralManager` are main-thread APIs.
    @objc(logSnapshot:)
    public func logSnapshot(_ label: String) {
        Task { @MainActor in
            self.armPeripheralManagerIfNeeded()
            self.logCurrentState(label: label)
        }
    }

    /// Starts once-per-second sampling for `Timing.sampleWindow`, plus registers `UIApplication`
    /// lifecycle notification observers for the same duration — a transition is logged the moment it
    /// happens, not just at the next poll. Auto-stops at the end of the window. A second call while
    /// already running restarts the window rather than stacking observers.
    @objc(start)
    public func start() {
        stop()
        sampleTask = Task { @MainActor in
            self.armPeripheralManagerIfNeeded()
            self.registerLifecycleObservers()
            self.logCurrentState(label: "start")
            var elapsed: Duration = .zero
            while elapsed < Timing.sampleWindow {
                try? await Task.sleep(for: Timing.sampleInterval)
                if Task.isCancelled { return }
                elapsed += Timing.sampleInterval
                self.logCurrentState(label: "sample")
            }
            self.log("sampling window ended (\(Timing.sampleWindow))")
            self.stop()
        }
    }

    /// Stops periodic sampling and removes the lifecycle observers. Idempotent — safe to call whether
    /// or not `start()` was ever called.
    @objc(stop)
    public func stop() {
        sampleTask?.cancel()
        sampleTask = nil
        lifecycleObservers.forEach { NotificationCenter.default.removeObserver($0) }
        lifecycleObservers.removeAll()
    }

    @MainActor
    private func armPeripheralManagerIfNeeded() {
        guard peripheralManager == nil else { return }
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    @MainActor
    private func registerLifecycleObservers() {
        let center = NotificationCenter.default
        let names: [(Notification.Name, String)] = [
            (UIApplication.willResignActiveNotification, "willResignActive"),
            (UIApplication.didEnterBackgroundNotification, "didEnterBackground"),
            (UIApplication.willEnterForegroundNotification, "willEnterForeground"),
            (UIApplication.didBecomeActiveNotification, "didBecomeActive"),
        ]
        lifecycleObservers = names.map { name, label in
            center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                self?.log("lifecycle: \(label)")
            }
        }
    }

    @MainActor
    private func logCurrentState(label: String) {
        let appState: String
        switch UIApplication.shared.applicationState {
        case .active: appState = "active"
        case .inactive: appState = "inactive"
        case .background: appState = "background"
        @unknown default: appState = "unknown"
        }
        let radioState = peripheralManager.map(Self.describe(state:)) ?? "no manager yet"
        log("\(label): applicationState=\(appState) systemBluetoothRadioState=\(radioState)")
    }

    private static func describe(state manager: CBPeripheralManager) -> String {
        switch manager.state {
        case .poweredOn: return "poweredOn"
        case .poweredOff: return "poweredOff"
        case .resetting: return "resetting"
        case .unauthorized: return "unauthorized"
        case .unsupported: return "unsupported"
        case .unknown: return "unknown"
        @unknown default: return "unknown(future case)"
        }
    }

    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        log("peripheralManagerDidUpdateState: \(Self.describe(state: peripheral))")
    }

    /// See this class's own doc comment for why this is not expected to ever fire here.
    public func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        log("willRestoreState: fired (unexpected for a diagnostic-only manager) — dict keys: \(dict.keys.sorted())")
    }
}

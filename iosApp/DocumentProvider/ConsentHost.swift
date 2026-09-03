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
import class SharedKit.IosPresentmentRequest
import class SharedKit.IosPresentmentDisclosure
import class SharedKit.DcApiConsentViewControllerKt

/// Hosts the wallet's **shared Compose** consent screen inside the extension.
///
/// This is the whole of the "second consent UI" that was scoped as this feature's one unavoidable
/// architectural cost. It is thirty lines, because the screen itself is the one the app already shows:
/// `DcApiConsentViewController` returns a `ComposeUIViewController`, and SwiftUI hosts a
/// `UIViewController` through `UIViewControllerRepresentable` without caring what drew it.
///
/// The same technique is in production in another Kotlin Multiplatform project on this team, which is
/// what settled that Compose renders inside an ExtensionKit extension at all — the earlier scoping had
/// assumed it does not.
struct ConsentHost: UIViewControllerRepresentable {

    let request: IosPresentmentRequest

    /// Called exactly once, with nil for a refusal. Kotlin guarantees the arity; this only forwards it.
    let onDecision: ([IosPresentmentDisclosure]?) -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        DcApiConsentViewControllerKt.DcApiConsentViewController(
            request: request,
            onDecision: { disclosures in
                // Kotlin hands back `List<IosPresentmentDisclosure>?`, which arrives as `[Any]?`.
                onDecision(disclosures as? [IosPresentmentDisclosure])
            }
        )
    }

    /// Nothing to update: the request cannot change once the extension has been handed one, and the
    /// selection state lives inside Compose rather than being driven from here.
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

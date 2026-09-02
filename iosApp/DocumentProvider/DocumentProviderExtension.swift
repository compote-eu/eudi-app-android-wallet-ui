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

import ExtensionKit
import IdentityDocumentServices
import IdentityDocumentServicesUI
import SwiftUI

/// The wallet's Digital Credentials provider: a second process iOS launches when something asks for a
/// credential this wallet registered.
///
/// The skeleton is the reference iOS wallet's, near enough verbatim, because it is pure Apple API —
/// `IdentityDocumentProvider` with one `ISO18013MobileDocumentRequestScene`. Everything inside the
/// scene is ours.
///
/// ⚠️ **Nothing here can run until the entitlement is honoured.** The app is ad-hoc signed, so the
/// registration it performs is refused with `notAuthorized` and iOS therefore has no reason to route a
/// request to this extension. What this target proves today is that it builds, links the shared Kotlin
/// framework and embeds — which is the part that can be got wrong silently. Being *invoked* needs team
/// signing and an iOS 26.2 device.
@main
struct DocumentProviderExtension: IdentityDocumentProvider {

    var body: some IdentityDocumentRequestScene {
        ISO18013MobileDocumentRequestScene { context in
            RequestAuthorizationView(context: context)
        }
    }

    /// Called when iOS wants the provider to refresh what it has registered.
    ///
    /// Empty on purpose, as in the reference wallet: registration is the *app's* job — it is the side
    /// that knows which documents exist and reconciles them at launch and on every deletion. An
    /// extension that registered as well would be a second writer to the same registry with a narrower
    /// view of the wallet.
    func performRegistrationUpdates() async {}
}

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

import IdentityDocumentServicesUI
import SwiftUI
import class SharedKit.IosDocumentProviderBridge

/// What the extension shows while a request is being decided.
///
/// ## Deliberately not the final screen
///
/// This opens the wallet through `IosDocumentProviderBridge` and reports what it found. It does **not**
/// yet run consent or send a response, and the reason is worth stating rather than leaving as a gap:
///
///  - **The consent screen should be the shared Compose one**, not a second UI written here. That is
///    reachable — a `ComposeUIViewController` inside a `UIViewControllerRepresentable` renders shared
///    Compose in an ExtensionKit extension, which an in-house project already does in production — but
///    our consent screen is bound to `RequestViewModel`, so hosting it is its own piece of work rather
///    than a line. Writing throwaway SwiftUI in the meantime would be building the thing that reuse
///    exists to avoid.
///  - **The response encoding cannot be confirmed from here.** `sendResponse` wants an
///    `ISO18013MobileDocumentResponse(responseData:)` while multipaz returns
///    `{"protocol": …, "data": {"response": <base64>}}`, and which of those bytes iOS expects is not
///    determinable without a request from the OS to answer. Guessing it would produce code that
///    compiles, looks finished, and fails the first time it runs — worse than an honest gap.
///
/// So this target's milestone is narrower than it looks, and it is the one that can go silently wrong:
/// **an ExtensionKit extension that builds, links the shared Kotlin framework and embeds correctly.**
///
/// ⚠️ Nothing here can be *invoked* yet regardless. The app is ad-hoc signed, so its registration is
/// refused with `notAuthorized` and iOS has no reason to route a request here. Team signing and an
/// iOS 26.2 device are what change that.
struct RequestAuthorizationView: View {

    let context: ISO18013MobileDocumentRequestContext

    @State private var status: String = "Opening the wallet…"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Credential request")
                .font(.headline)

            if let origin = context.requestingWebsiteOrigin {
                Text(origin.absoluteString)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Text(status)
                .font(.body)

            Spacer()

            Button("Cancel") { context.cancel() }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
        }
        .padding()
        .task {
            // Proves the extension process can reach the wallet: a separate process, its own
            // container, opening the app-group store the app writes to. If this reports a document
            // count the cross-process half of the design is working.
            do {
                _ = try await IosDocumentProviderBridge.companion.create()
                status = "Wallet opened. Consent and response are not wired yet — see the file comment."
            } catch {
                status = "Could not open the wallet: \(error.localizedDescription)"
            }
        }
    }
}

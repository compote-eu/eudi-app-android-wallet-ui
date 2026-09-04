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

import IdentityDocumentServices
import IdentityDocumentServicesUI
import SwiftUI
import class SharedKit.IosDocumentProviderBridge
import class SharedKit.IosPresentmentDisclosure
import class SharedKit.IosPresentmentRequest
import protocol SharedKit.IosDcApiConsent

/// The extension's screen: open the wallet, ask, answer.
///
/// The whole exchange runs **inside `context.sendResponse`**, which is the shape the reference iOS
/// wallet uses and the reason for it is structural rather than stylistic: the closure is handed the
/// *raw* request, and that — not `context.request` — is the Annex C JSON multipaz parses. Building a
/// response from anything else would bind a different session transcript than the one the verifier
/// will check against.
///
/// Consent is the **shared Compose screen**, hosted by `ConsentHost`. It is the same screen the app
/// shows for remote and proximity presentation, so selective disclosure behaves identically in the
/// extension and cannot drift from it.
///
/// ✅ **The response encoding is verified, from both sides, and needed no device.**
/// multipaz builds `Cbor.encode(["dcapi", {"enc": …, "cipherText": …}]).toBase64Url()` and puts that in
/// `data.response`. The reference iOS wallet's `DcApi18013AnnexC.buildAndEncryptResponse` returns
/// `Data(CBOR.array(["dcapi", ["enc": …, "cipherText": …]]).encode())` and hands it straight to
/// `ISO18013MobileDocumentResponse(responseData:)`. So base64url-decoding `data.response` yields
/// byte-for-byte what the reference passes — which is exactly what `responseBytes` below does.
///
/// What remains untested is the OS half: that iOS routes a request here and a verifier accepts the
/// result. That needs the entitlement honoured on real hardware.
struct RequestAuthorizationView: View {

    let context: ISO18013MobileDocumentRequestContext

    @State private var request: IosPresentmentRequest?
    @State private var status: String = "Opening the wallet…"
    @State private var failed: String?

    private let decision = DecisionBox()

    var body: some View {
        Group {
            if let request {
                ConsentHost(request: request) { disclosures in
                    decision.resolve(disclosures)
                }
            } else {
                VStack(spacing: 16) {
                    if let failed {
                        Text(failed).multilineTextAlignment(.center)
                    } else {
                        ProgressView()
                        Text(status).font(.footnote).foregroundStyle(.secondary)
                    }
                    Button("Cancel") { context.cancel() }.buttonStyle(.bordered)
                }
                .padding()
            }
        }
        .task { await run() }
    }

    private func run() async {
        do {
            let bridge = try await IosDocumentProviderBridge.companion.create()

            try await context.sendResponse { rawRequest in
                let result = try await bridge.present(
                    protocol: "org-iso-mdoc",
                    data: String(data: rawRequest.requestData, encoding: .utf8) ?? "",
                    origin: webOrigin(context.requestingWebsiteOrigin),
                    appId: nil,
                    consent: ConsentBridge(
                        show: { box in await MainActor.run { self.request = box.value } },
                        answer: { await decision.wait() }
                    )
                )

                guard let json = result.responseJson,
                      let responseData = responseBytes(from: json) else {
                    // A refusal and a failure both end without a response. `cancel()` is the only way
                    // to say so — `sendResponse` has no "declined" outcome — so the distinction lives
                    // in the message, not in the OS call.
                    throw ProviderError.noResponse(result.declined ? nil : result.errorMessage)
                }
                return ISO18013MobileDocumentResponse(responseData: responseData)
            }
        } catch {
            await MainActor.run {
                failed = (error as? ProviderError)?.message ?? error.localizedDescription
            }
        }
    }

}

/// Pulls the encrypted mdoc response out of multipaz's DC API result.
///
/// The bytes are the CBOR `["dcapi", {"enc", "cipherText"}]` that multipaz base64url-encodes into
/// `data.response`, and they are what `ISO18013MobileDocumentResponse` wants — see the view's doc for
/// where that was confirmed on both sides.
///
/// File scope rather than a member: a SwiftUI `View` is main-actor isolated, and this is called from
/// inside `sendResponse`'s `@Sendable` closure, which is not. It touches nothing but its argument.
private func responseBytes(from json: String) -> Data? {
    guard let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
          let data = object["data"] as? [String: Any],
          let response = data["response"] as? String
    else { return nil }
    // base64url, as multipaz writes it: pad and translate before decoding.
    var padded = response.replacingOccurrences(of: "-", with: "+")
        .replacingOccurrences(of: "_", with: "/")
    while padded.count % 4 != 0 { padded += "=" }
    return Data(base64Encoded: padded)
}

private enum ProviderError: Error {
    case noResponse(String?)

    var message: String {
        switch self {
        case .noResponse(let reason): return reason ?? "Nothing was shared."
        }
    }
}

/// Carries a Kotlin value across an isolation boundary it never actually crosses at runtime.
///
/// The same device `DocumentSigning.swift` needs and for the same reason: Kotlin/Native types are not
/// `Sendable`, so Swift 6 refuses to let one reach the main actor or leave an actor's method, even
/// though only the reference moves. Checked by hand rather than asserted — a request and a list of
/// disclosures are both immutable once Kotlin has produced them.
fileprivate struct RequestBox: @unchecked Sendable { let value: IosPresentmentRequest }
fileprivate struct DisclosureBox: @unchecked Sendable { let value: [IosPresentmentDisclosure]? }

/// Swift's half of `IosDcApiConsent`: show the request, then wait for the screen to answer.
///
/// A class implementing a Kotlin protocol rather than a closure, because Kotlin/Native exports a
/// suspend *function type* as `KotlinSuspendFunction1` which no Swift closure can satisfy. A suspend
/// method on a protocol arrives as `async`, which this implements directly.
///
/// `@unchecked Sendable` on the same terms as `WalletDocumentSigner`: both stored properties are
/// immutable closures, and everything mutable lives in the actor below.
fileprivate final class ConsentBridge: IosDcApiConsent, @unchecked Sendable {

    private let show: (RequestBox) async -> Void
    private let answer: () async -> DisclosureBox

    init(
        show: @escaping (RequestBox) async -> Void,
        answer: @escaping () async -> DisclosureBox
    ) {
        self.show = show
        self.answer = answer
    }

    func requestConsent(request: IosPresentmentRequest) async throws -> [IosPresentmentDisclosure]? {
        await show(RequestBox(value: request))
        return await answer().value
    }
}

/// Carries the user's answer from the Compose screen into the suspended Kotlin call.
///
/// An actor because two isolation domains touch it — Compose answers on the main actor, the Kotlin
/// coroutine resumes on its own — and because resuming a continuation twice is a crash rather than a
/// loggable bug. Every `resolve` after the first is ignored.
fileprivate actor DecisionBox {

    private var continuation: CheckedContinuation<DisclosureBox, Never>?
    private var settled = false
    private var pending: DisclosureBox?

    func wait() async -> DisclosureBox {
        if let pending { return pending }
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
        }
    }

    /// Boxed at the boundary rather than inside: the array itself cannot be sent into a `Task`, so the
    /// wrapping has to happen on this side of it.
    nonisolated func resolve(_ disclosures: [IosPresentmentDisclosure]?) {
        let box = DisclosureBox(value: disclosures)
        Task { await settle(box) }
    }

    private func settle(_ box: DisclosureBox) {
        guard !settled else { return }
        settled = true
        pending = box
        continuation?.resume(returning: box)
        continuation = nil
    }
}

/// The requesting site's origin, serialized the way RFC 6454 defines it — `scheme://host[:port]`,
/// with **no trailing slash**.
///
/// 🪤 `URL.absoluteString` is the obvious choice and it is wrong. Apple hands the extension a
/// `URL`, and a URL whose path is empty normalizes to one with a `/`, so a bare origin comes back
/// as `https://example.test:8443/`. That string is then bound into the ISO 18013-7 session
/// transcript, which HPKE uses as `info` — so the only symptom is that the verifier cannot decrypt
/// the response, with no indication of which side disagreed. It cost a device round trip and a
/// brute-force search over candidate transcripts to find, and it would have made every
/// spec-compliant verifier fail while a verifier that made the same mistake worked.
private func webOrigin(_ url: URL?) -> String {
    guard let url, let scheme = url.scheme, let host = url.host() else { return "" }
    if let port = url.port { return "\(scheme)://\(host):\(port)" }
    return "\(scheme)://\(host)"
}


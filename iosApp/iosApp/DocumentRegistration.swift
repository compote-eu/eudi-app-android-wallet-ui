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

import Foundation
import IdentityDocumentServices
import class SharedKit.IosDocumentRegistrationKt
import class SharedKit.IosRegistrableDocument

/// Registers this wallet's documents with iOS, so they appear in the system credential picker.
///
/// ## This file is deliberately a copy
///
/// The protocol, both implementations and the availability split below mirror the official EUDI iOS
/// wallet's `logic-core/DocumentRegistrationManager.swift` as closely as our project allows. That is a
/// choice, and an easy one to make here: **their file depends on nothing of theirs.** It imports
/// `Foundation` and `IdentityDocumentServices` and touches no Wallet Kit type, so the engine
/// substitution that explains almost every other difference between the two wallets does not reach it.
/// Where two independent wallets must talk to the same Apple registry, agreeing on the shape costs
/// nothing and makes their code readable as documentation for ours.
///
/// What is *not* copied is the caller: theirs registers from a Wallet Kit document fetch, ours from
/// Kotlin. That seam is `registerDocumentsWithSystem()` at the bottom, and it is the only part with no
/// counterpart in their tree.
///
/// ## Why not multipaz
///
/// `multipaz-dcapi` ships an iOS `defaultRegister` that does this job, but it reaches the OS through
/// `org.multipaz.SwiftBridge`, an Xcode project in multipaz's repository rather than a published
/// artifact. Vendoring a Swift target to reach a four-field Apple struct is the wrong trade — and
/// avoiding it is what lets this file stay a near-copy of the reference wallet's. The reasoning is
/// recorded once, in the Kotlin half.
protocol DocumentRegistrationManager: Sendable {

    func addRegistration(
        mobileDocumentType: String,
        supportedAuthorityKeyIdentifiers: [Data],
        documentIdentifier: String,
        invalidationDate: Date?
    ) async throws

    func removeRegistration(
        documentIdentifiers: [String]
    ) async throws
}

@available(iOS 26.0, *)
final actor DocumentRegistrationManagerImpl: DocumentRegistrationManager {

    init() {}

    private func makeStore() -> IdentityDocumentProviderRegistrationStore {
        IdentityDocumentProviderRegistrationStore()
    }

    func addRegistration(
        mobileDocumentType: String,
        supportedAuthorityKeyIdentifiers: [Data],
        documentIdentifier: String,
        invalidationDate: Date?
    ) async throws {
        let store = makeStore()
        let registration = MobileDocumentRegistration(
            mobileDocumentType: mobileDocumentType,
            supportedAuthorityKeyIdentifiers: supportedAuthorityKeyIdentifiers,
            documentIdentifier: documentIdentifier,
            invalidationDate: invalidationDate
        )
        try await store.addRegistration(registration)
    }

    func removeRegistration(
        documentIdentifiers: [String]
    ) async throws {
        let store = makeStore()
        for documentIdentifier in documentIdentifiers {
            try await store.removeRegistration(
                forDocumentIdentifier: documentIdentifier
            )
        }
    }

    /// Whether the OS will accept registrations at all — **our addition, and the diagnostic that matters.**
    ///
    /// The reference wallet never reads this, because it ships team-signed with the restricted
    /// entitlement in a real provisioning profile and the answer is always `.authorized`. This fork is
    /// **ad-hoc signed** (`CODE_SIGN_IDENTITY: "-"`), so it is not, and without this the failure looks
    /// like nothing at all: `addRegistration` throws `.notAuthorized`, the catch swallows it, and the
    /// picker is simply empty with no explanation. Reading the status once turns that into a line in
    /// the log that names the cause.
    func status() async -> String {
        switch await makeStore().status {
        case .authorized: return "authorized"
        case .notDetermined: return "notDetermined"
        case .notAuthorized: return "notAuthorized"
        case .notSupported: return "notSupported"
        @unknown default: return "unknown"
        }
    }
}

/// Below iOS 26 the framework does not exist, so the wallet keeps working and registers nothing.
///
/// Same shape as the reference wallet's `DocumentRegistrationManagerNoOp`, and the reason is the same:
/// the availability check belongs in one place — the factory — rather than at every call site.
final class DocumentRegistrationManagerNoOp: DocumentRegistrationManager, Sendable {

    init() {}

    func addRegistration(
        mobileDocumentType: String,
        supportedAuthorityKeyIdentifiers: [Data],
        documentIdentifier: String,
        invalidationDate: Date?
    ) async throws {
    }

    func removeRegistration(
        documentIdentifiers: [String]
    ) async throws {
    }
}

/// The reference wallet makes this choice in its Swinject assembly; we have no container, so it is a
/// function. The `#available` split, and the fact that it is made exactly once, are the parts worth
/// keeping identical.
func makeDocumentRegistrationManager() -> DocumentRegistrationManager {
    if #available(iOS 26.0, *) {
        return DocumentRegistrationManagerImpl()
    } else {
        return DocumentRegistrationManagerNoOp()
    }
}

/// Reconciles the OS registry with the wallet's store.
///
/// **This is the half with no counterpart in the reference wallet**, because the document list comes
/// from Kotlin rather than from a Swift controller. Theirs registers as a side effect of
/// `fetchDocuments(with:)`; ours is called once per launch, beside the revocation refresh, for the same
/// reason that one is: it is the trigger that can actually be relied on, and it keeps the OS's view no
/// staler than the session the user is looking at.
///
/// Registration is idempotent — `addRegistration` for a document identifier already present updates it
/// rather than duplicating — so re-running every launch is the intended usage, not a workaround.
///
/// Errors are logged and swallowed per document, never thrown: a wallet whose launch fails because the
/// OS declined a registration would be a strictly worse wallet than one that simply is not in the
/// picker. The status line printed first is what distinguishes "declined" from "nothing to register".
func registerDocumentsWithSystem() {
    Task {
        guard #available(iOS 26.0, *) else {
            print("DOCUMENT-REGISTRATION: iOS < 26, nothing registered")
            return
        }

        let manager = makeDocumentRegistrationManager()

        if let impl = manager as? DocumentRegistrationManagerImpl {
            print("DOCUMENT-REGISTRATION: store status \(await impl.status())")
        }

        do {
            let documents = try await IosDocumentRegistrationKt.registrableDocuments()
            guard !documents.isEmpty else {
                print("DOCUMENT-REGISTRATION: no mdoc documents to register")
                return
            }

            for document in documents {
                do {
                    try await manager.addRegistration(
                        mobileDocumentType: document.mobileDocumentType,
                        // Empty, exactly as the reference wallet passes it. See the Kotlin half for why
                        // computing this from our documents would claim a filter we do not have.
                        supportedAuthorityKeyIdentifiers: [],
                        documentIdentifier: document.documentIdentifier,
                        invalidationDate: document.invalidationDate
                    )
                    print("DOCUMENT-REGISTRATION: registered \(document)")
                } catch {
                    print("DOCUMENT-REGISTRATION: \(document) refused — \(error)")
                }
            }
        } catch {
            print("DOCUMENT-REGISTRATION: could not read documents — \(error)")
        }
    }
}

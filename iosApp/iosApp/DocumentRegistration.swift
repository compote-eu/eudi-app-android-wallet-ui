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
import protocol SharedKit.IosDocumentRegistry

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
/// Kotlin. That is `reconcileDocumentRegistrations()` at the bottom, and it is the only part with no
/// counterpart in their tree — deliberately so, because their removal path does not work.
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

    /// The document identifiers the OS currently holds for this app — **our addition, kept off the protocol.**
    ///
    /// The reference wallet's `DocumentRegistrationManager` has exactly two methods and this is not one
    /// of them, so it lives on the implementation rather than widening the mirrored type. It is what
    /// makes removal possible at all: nothing in the wallet knows what the OS still believes, and a
    /// registration whose document is gone can only be found by asking.
    func registeredDocumentIdentifiers() async throws -> Set<String> {
        Set(try await makeStore().registrations.map(\.documentIdentifier))
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

/// Brings the OS registry back in line with the wallet: adds what is missing, removes what is gone.
///
/// **This is the half with no counterpart in the reference wallet**, because the document list comes
/// from Kotlin rather than from a Swift controller — and because theirs does not really do it. Two
/// triggers call this:
///
///  - **once per launch**, beside the revocation refresh and for the same reason: it is the trigger
///    that can be relied on, and it keeps the OS's view no staler than the session the user is
///    looking at;
///  - **after a document is deleted**, through `IosDocumentRegistry` — otherwise the registration
///    outlives the document and the picker offers a credential that can no longer be presented.
///
/// ## Why a reconciliation rather than a delete callback
///
/// The delete path could have passed the identifier it just removed. Reconciling instead is what
/// multipaz's own registration does, and it is the shape that cannot drift: it fixes the removal it
/// was called for, a removal an earlier failed call missed, and a document that arrived by a path
/// nobody hooked. The extra cost is one read of a list the OS already holds.
///
/// ⚠️ **The reference wallet's version of this does nothing, and the mistake is worth knowing before
/// anyone "aligns" ours to it.** Its single-document delete never unregisters; its `clearAllDocuments`
/// deletes everything and *then* asks which ids to unregister, so the list is empty by construction.
/// Both halves of its removal path are effectively dead. The API is mirrored above; the wiring is ours.
///
/// Errors are logged and swallowed per document, never thrown: a wallet whose launch fails because the
/// OS declined a registration would be strictly worse than one that is merely absent from the picker.
/// The status line printed first is what distinguishes "declined" from "nothing to register".
func reconcileDocumentRegistrations() {
    Task {
        guard #available(iOS 26.0, *) else {
            print("DOCUMENT-REGISTRATION: iOS < 26, nothing registered")
            return
        }

        let manager = DocumentRegistrationManagerImpl()
        print("DOCUMENT-REGISTRATION: store status \(await manager.status())")

        let wanted: [IosRegistrableDocument]
        do {
            wanted = try await IosDocumentRegistrationKt.registrableDocuments()
        } catch {
            print("DOCUMENT-REGISTRATION: could not read documents — \(error)")
            return
        }
        let wantedIdentifiers = Set(wanted.map(\.documentIdentifier))

        // Removal is best-effort and deliberately does not gate the additions below. Reading what the
        // OS holds is the one step that can fail for a reason unrelated to any document — on this
        // ad-hoc-signed build it always does — and registering is the half that matters: a wallet
        // absent from the picker is a worse outcome than one that left a stale entry behind.
        //
        // Read before writing: once the additions have run, every wanted document is present and the
        // difference that identifies a stale registration is gone.
        do {
            let stale = try await manager.registeredDocumentIdentifiers()
                .subtracting(wantedIdentifiers)

            if stale.isEmpty {
                print("DOCUMENT-REGISTRATION: nothing stale to unregister")
            } else {
                try await manager.removeRegistration(documentIdentifiers: Array(stale))
                print("DOCUMENT-REGISTRATION: unregistered \(stale.count) — \(stale.sorted())")
            }
        } catch {
            print("DOCUMENT-REGISTRATION: could not reconcile removals — \(error)")
        }

        guard !wanted.isEmpty else {
            print("DOCUMENT-REGISTRATION: no mdoc documents to register")
            return
        }

        for document in wanted {
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
    }
}

/// The Swift half of `IosDocumentRegistry`, so Kotlin can say "the documents changed".
///
/// Kotlin cannot unregister anything itself — `IdentityDocumentServices` is a Swift framework and none
/// of its API crosses the Kotlin/Native bridge — so deletion has to come back across the seam. Same
/// shape as `WalletDocumentSigner` for RQES, and registered the same way at launch.
///
/// `documentsChanged()` returns immediately: the caller is a `deleteDocument` whose result the user is
/// waiting on, and it must not block on the OS registry. Nothing is retained between calls, so a
/// reconciliation started here cannot outlive the delete that caused it in any way that matters.
/// `@unchecked Sendable` for the same reason `WalletDocumentSigner` needs it — a Kotlin protocol
/// conformance carries no Sendability, and `NSObject` is not `Sendable` — but the justification here is
/// stronger than the signer's: this class has **no stored properties at all**. There is no shared
/// mutable state to protect, only the reference itself crossing threads.
final class WalletDocumentRegistry: NSObject, IosDocumentRegistry, @unchecked Sendable {

    static let shared = WalletDocumentRegistry()

    private override init() {}

    func documentsChanged() {
        reconcileDocumentRegistrations()
    }
}

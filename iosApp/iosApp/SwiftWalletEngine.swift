import Foundation
import SharedKit

/// SPIKE: can a Swift type implement the Kotlin `WalletEngine` interface across the SharedKit
/// boundary? The whole Phase-2 architecture assumes "one interface, implemented twice" — Android over
/// wallet-core, iOS over the Swift wallet kit — and this is the cheapest possible test of that claim.
/// Everything returns hardcoded data; the question is whether it compiles and can be handed to shared
/// Kotlin code at all.
class SwiftWalletEngine: WalletEngine {

    private let sample = WalletDocument(
        id: "swift-1",
        name: "PID from Swift",
        formatType: "eu.europa.ec.eudi.pid.1",
        issuanceState: .issued,
        claims: [:],
        issuedAt: nil,
        expiresAt: nil,
        isExpired: false,
        isRevoked: false,
        credentialsCount: 3,
        initialCredentialsCount: 5,
        isLowOnCredentials: false,
        issuerName: "Swift Issuer",
        issuerLogoUri: nil
    )

    // MARK: - suspend members, exported with completion handlers

    func getMainPidDocument(completionHandler: @escaping (WalletDocument?, Error?) -> Void) {
        completionHandler(sample, nil)
    }

    func getAllDocuments(completionHandler: @escaping ([WalletDocument]?, Error?) -> Void) {
        completionHandler([sample], nil)
    }

    func getRevokedDocumentIds(completionHandler: @escaping ([String]?, Error?) -> Void) {
        completionHandler([], nil)
    }

    func isDocumentRevoked(documentId: String, completionHandler: @escaping (KotlinBoolean?, Error?) -> Void) {
        completionHandler(false, nil)
    }

    func isDocumentBookmarked(documentId: String, completionHandler: @escaping (KotlinBoolean?, Error?) -> Void) {
        completionHandler(false, nil)
    }

    func storeBookmark(bookmarkId: String, completionHandler: @escaping (Error?) -> Void) {
        completionHandler(nil)
    }

    func deleteBookmark(bookmarkId: String, completionHandler: @escaping (Error?) -> Void) {
        completionHandler(nil)
    }

    func getAllDocumentsWithDetails(locale: String, completionHandler: @escaping ([WalletDocument]?, Error?) -> Void) {
        completionHandler([sample], nil)
    }
}

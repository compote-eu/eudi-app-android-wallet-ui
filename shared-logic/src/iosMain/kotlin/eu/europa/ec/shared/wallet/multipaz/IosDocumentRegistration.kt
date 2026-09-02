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

package eu.europa.ec.shared.wallet.multipaz

import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.util.Logger
import platform.Foundation.NSDate

/**
 * One document, reduced to exactly what iOS's document-provider registry stores about it.
 *
 * The field names are Apple's, not ours: they are the four members of
 * `IdentityDocumentServices.MobileDocumentRegistration`, so the Swift side can build one from this
 * without a translation step. [supportedAuthorityKeyIdentifiers] is deliberately absent — see
 * [registrableDocuments] for why.
 */
data class IosRegistrableDocument(
    val documentIdentifier: String,
    val mobileDocumentType: String,
    val invalidationDate: NSDate?,
) {
    override fun toString(): String = "$mobileDocumentType/$documentIdentifier"
}

/**
 * The documents this wallet can offer through iOS's Digital Credentials API, in registration order.
 *
 * ## What this is half of
 *
 * Being a credential provider on iOS has two halves. **This is the first**: telling the OS which
 * documents exist, so they appear in the system picker when a website or app asks for one. The second
 * is an `ExtensionKit` extension that answers the request — a separate process, a separate target, and
 * not yet built here. Registration is useful on its own and independently observable: the credentials
 * show up in the picker before anything can answer for them.
 *
 * ⚠️ **multipaz has its own version of this and we deliberately do not use it.**
 * `multipaz-dcapi`'s `defaultRegister` does the same job, but it reaches the OS through
 * `org.multipaz.SwiftBridge` — an Xcode project inside multipaz's repository that is **not part of the
 * Maven artifact**, so using it means vendoring a Swift target the way the ETSI `PKIXBridge` had to be.
 * Registration is four fields on one Apple struct; going straight there costs less than carrying that
 * dependency, and it is what lets the Swift half be a near-copy of the official iOS wallet's
 * `DocumentRegistrationManager`. Its *request* half, `defaultRequest`, would not have helped either:
 * that is the relying-party direction — the wallet asking for credentials — not the provider one.
 *
 * ## mdoc only, and why that is not a limitation here
 *
 * A document is registrable when it has a certified [MdocCredential]. The official iOS wallet applies
 * the same filter as `document.docDataFormat == .cbor`, and it is not a choice either of us makes:
 * Apple's extension point is `ISO18013MobileDocumentRequestScene` and the registry keys on
 * `mobileDocumentType`, so SD-JWT VC has nowhere to go. A wallet holding only SD-JWT credentials
 * registers nothing, correctly.
 *
 * ## The two fields that needed a decision
 *
 * **`invalidationDate`** is the latest `validUntil` across the document's certified mdoc credentials,
 * so the registration survives exactly as long as something remains presentable. Taking the *earliest*
 * would drop the document from the picker while it still worked; omitting it would leave expired
 * documents advertised forever. The official app passes its document-level `validUntil`, which is the
 * same intent expressed against a store that keeps one validity per document rather than per credential.
 *
 * **`supportedAuthorityKeyIdentifiers`** is left to the Swift side to pass empty, matching the official
 * wallet exactly. It is the reader-authority filter: a non-empty list tells iOS to offer this document
 * only to readers under those authorities. multipaz's own registration passes the document's
 * `readerIdentifiers` here, which is stricter — but our documents are issued with none, so computing it
 * would send an empty list under a different name and pretend to a filter we do not have. Empty is the
 * honest value until reader identifiers are actually populated, and it is what the reference wallet
 * ships.
 *
 * Failure is swallowed per document rather than thrown: one unreadable document must not cost the
 * registration of every other. Cancellation is rethrown — that is the caller asking for its time back.
 */
suspend fun registrableDocuments(): List<IosRegistrableDocument> =
    IosWalletEngine().store().registrableDocuments()

/**
 * The same reading, against a store the caller supplies — which is the whole reason this is split out.
 *
 * [registrableDocuments] opens the real wallet, so nothing about it can be asserted from a test. This
 * takes the store as a parameter, so `IosDocumentRegistrationTest` can seed documents and check what
 * comes back. The split costs one line and is the difference between a seam that is believed to work
 * and one that is known to.
 */
internal suspend fun MultipazWalletStore.registrableDocuments(): List<IosRegistrableDocument> {
    return documentStore.listDocuments().mapNotNull { document ->
        try {
            val mdocCredentials = document.getCertifiedCredentials()
                .filterIsInstance<MdocCredential>()

            val docType = mdocCredentials.firstOrNull()?.docType ?: return@mapNotNull null

            IosRegistrableDocument(
                documentIdentifier = document.identifier,
                mobileDocumentType = docType,
                invalidationDate = mdocCredentials
                    .maxOfOrNull { it.validUntil }
                    ?.toNSDate(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Logger.e(TAG, "skipping ${document.identifier}: not readable", error)
            null
        }
    }.also { Logger.i(TAG, "registrable documents: ${it.size} (${it.joinToString()})") }
}

/** NSDate is anchored to 2001-01-01; 978_307_200 s is the offset from the 1970 Unix epoch. */
private fun Instant.toNSDate(): NSDate =
    NSDate(timeIntervalSinceReferenceDate = (toEpochMilliseconds() / 1000.0) - 978_307_200.0)

private const val TAG = "IosDocumentRegistration"

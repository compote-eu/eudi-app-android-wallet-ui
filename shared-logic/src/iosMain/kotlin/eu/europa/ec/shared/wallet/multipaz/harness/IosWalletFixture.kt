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

package eu.europa.ec.shared.wallet.multipaz.harness

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.MultipazWalletStore

/**
 * Seeds the running app's wallet with one fixture PID, so the iOS document layer has something to
 * read. A no-op once the wallet holds a document, so repeated launches do not pile up duplicates.
 *
 * The only public entry point of the fixture, and the *only* reason it is public: it lets the
 * console probe in `:shared-ui` drive a real end-to-end read without naming a multipaz type, since
 * multipaz is an `implementation` dependency of this module. It goes away with the first real iOS
 * issuance path.
 *
 * @return the document id it seeded, or null when the wallet already had documents.
 */
suspend fun seedIosWalletFixture(): String? {
    val store = MultipazWalletStore.open()
    if (!store.isEmpty()) return null

    val docType = MDOC_PID_DOC_TYPE
    return store.seedMdocDocument(
        docType = docType,
        displayName = "PID MSO MDoc (fixture)",
        namespace = docType,
        elements = samplePidElements(),
        // A rotating batch of 3, so the credential counters have something to count and the
        // "3/3" shape of the Documents screen's counter is exercised.
        policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 3),
        issuerMetadata = sampleIssuerMetadata(docType),
    )
}

/** The mdoc PID docType; see `DocumentIdentifier.MdocPid` in `:shared-ui`. */
internal const val MDOC_PID_DOC_TYPE = "eu.europa.ec.eudi.pid.1"

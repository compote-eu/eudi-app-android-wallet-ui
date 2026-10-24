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

// Reading an mdoc's data elements for the details screen: one claim per namespace and data element, as
// Android's MsoMdocData keeps them. Keyed by the element's name alone, the same name in two namespaces
// was one claim, and which value it showed depended on the order the issuer sent them in.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.issuerNamespacesOf
import eu.europa.ec.shared.wallet.multipaz.harness.issuerSignedDataFor
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Tstr
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class MdocNamespacedClaimsTest {

    private val docType = "org.iso.18013.5.1.mDL"
    private val iso = "org.iso.18013.5.1"
    private val national = "org.example.national.1"

    @Test
    fun the_same_data_element_in_two_namespaces_is_two_claims_each_with_its_value() = runTest {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))
        val document = store.documentStore.createDocument(
            displayName = "mDL",
            metadata = EudiDocumentMetadata.create(
                documentManagerId = store.documentManagerId,
                format = StoredDocumentFormat.MsoMdoc(docType),
                credentialPolicy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 1),
            ),
        )
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = store.documentManagerId,
            secureArea = store.keySecureArea,
            docType = docType,
            createKeySettings = SoftwareCreateKeySettings.Builder().build(),
        )
        val namespaces = IssuerNamespaces(
            data = issuerNamespacesOf(iso, listOf("family_name" to Tstr("Iso")), Random.Default).data +
                issuerNamespacesOf(national, listOf("family_name" to Tstr("National")), Random.Default).data,
        )
        credential.certify(
            issuerProvidedAuthenticationData = issuerSignedDataFor(
                docType = docType,
                issuerNamespaces = namespaces,
                deviceKey = store.keySecureArea.getKeyInfo(credential.alias).publicKey,
                validFrom = Clock.System.now() - 1.days,
                validUntil = Clock.System.now() + 30.days,
            ),
        )

        val claims = document.readNamespacedClaims()

        assertEquals(
            setOf(
                StoredMdocClaim(nameSpace = iso, identifier = "family_name", value = "Iso"),
                StoredMdocClaim(nameSpace = national, identifier = "family_name", value = "National"),
            ),
            claims.toSet(),
        )
    }
}

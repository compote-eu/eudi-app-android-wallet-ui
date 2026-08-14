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

package eu.europa.ec.shared.wallet.multipaz.spike

import eu.europa.ec.shared.wallet.document.IssuerMetadata
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.EudiDocumentMetadata
import eu.europa.ec.shared.wallet.multipaz.MultipazWalletStore
import eu.europa.ec.shared.wallet.multipaz.StoredDocumentFormat
import eu.europa.ec.shared.wallet.multipaz.eudiMetadata
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.buildCborMap
import org.multipaz.cose.Cose
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.issuersigned.IssuerSignedItem
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.revocation.RevocationStatus
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureEnclaveCreateKeySettings
import org.multipaz.securearea.SecureEnclaveSecureArea
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * VERIFICATION FIXTURE — mints a synthetic but structurally real mdoc document straight into
 * multipaz's `DocumentStore`.
 *
 * It exists because iOS has **no issuance path yet**: the OpenID4VCI library is JVM-only, and under
 * the hybrid architecture wallet-kit will own that half. Without a fixture there is simply nothing in
 * the store for the document layer to read, so nothing to verify. What this produces is a genuine
 * `MdocCredential`: real Secure-Enclave (or software) device keys, real `IssuerSignedItem`s with
 * random salts, a real Mobile Security Object whose value digests match those items, and a real
 * `COSE_Sign1` over it. The only thing fake is *who signed it* — an ephemeral key standing in for an
 * issuer's document-signer certificate — which is exactly the part the read path does not check.
 *
 * Kept beside the engine rather than in a test source set so the running app can seed itself, in the
 * same spirit as the `spike` package in `:shared-ui`. Delete it once iOS can issue for real.
 */
internal suspend fun MultipazWalletStore.seedMdocDocument(
    docType: String,
    displayName: String,
    namespace: String,
    elements: List<Pair<String, DataItem>>,
    policy: WalletCredentialPolicy,
    issuerMetadata: IssuerMetadata? = null,
    secureArea: SecureArea = keySecureArea,
    validFrom: Instant = Clock.System.now() - 1.days,
    validUntil: Instant = Clock.System.now() + 30.days,
    markIssued: Boolean = true,
    random: Random = Random.Default,
    /**
     * Optional `status` element for the MSO — what a real issuer puts there to say where this
     * credential's revocation status can be looked up. Needed to exercise the revocation path at all:
     * without it a document simply has no status to check.
     */
    revocationStatus: RevocationStatus? = null,
): String {
    val metadata = EudiDocumentMetadata.create(
        documentManagerId = documentManagerId,
        format = StoredDocumentFormat.MsoMdoc(docType),
        credentialPolicy = policy,
        issuerMetadata = issuerMetadata,
    )

    val document = documentStore.createDocument(
        displayName = displayName,
        metadata = metadata,
    )

    val issuerNamespaces = issuerNamespacesOf(namespace, elements, random)

    repeat(policy.numberOfCredentials) {
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            // The credential's domain must be the document-manager id: that is what the read path
            // filters on, exactly as the Android document manager does.
            domain = documentManagerId,
            secureArea = secureArea,
            docType = docType,
            createKeySettings = createKeySettingsFor(secureArea),
        )

        credential.certify(
            issuerProvidedAuthenticationData = issuerSignedDataFor(
                docType = docType,
                issuerNamespaces = issuerNamespaces,
                deviceKey = secureArea.getKeyInfo(credential.alias).publicKey,
                validFrom = validFrom,
                validUntil = validUntil,
                revocationStatus = revocationStatus,
            ),
        )
    }

    if (markIssued) {
        // Same two-step as the Android document manager: mutate the metadata, then persist it —
        // `edit` is what writes the serialized form back to storage.
        metadata.issue()
        document.edit { this.metadata = metadata }
    }

    return document.identifier
}

/**
 * Certifies a credential someone *else* created — the issuance path's credentials, which multipaz mints
 * as pending and a real issuer would certify — with the same synthetic issuer data [seedMdocDocument]
 * uses.
 *
 * Needed because certification is not optional for anything the reader can see: `getCertifiedCredentials`
 * skips pending ones, and `MdocCredential.certify` insists on a parseable MSO to take its validity window
 * from, so there is no shortcut with placeholder bytes.
 */
internal suspend fun SecureAreaBoundCredential.certifyWithFixtureIssuer(
    docType: String,
    namespace: String = docType,
    elements: List<Pair<String, DataItem>> = samplePidElements(),
    validFrom: Instant = Clock.System.now() - 1.days,
    validUntil: Instant = Clock.System.now() + 30.days,
) = certify(
    issuerProvidedAuthenticationData = issuerSignedDataFor(
        docType = docType,
        issuerNamespaces = issuerNamespacesOf(namespace, elements, Random.Default),
        deviceKey = secureArea.getKeyInfo(alias).publicKey,
        validFrom = validFrom,
        validUntil = validUntil,
    ),
)

/**
 * A realistic PID claim set, chosen to exercise every branch of the CBOR-to-string conversion the
 * claims map goes through: text, a tagged `full-date`, a boolean, an integer and a byte string.
 */
internal fun samplePidElements(
    givenName: String = "Tester",
    familyName: String = "Kotlin",
): List<Pair<String, DataItem>> = listOf(
    "given_name" to Tstr(givenName),
    "family_name" to Tstr(familyName),
    "birth_date" to Tagged(Tagged.FULL_DATE_STRING, Tstr("1990-01-01")),
    "age_over_18" to Simple.TRUE,
    "age_in_years" to Uint(36UL),
    "portrait" to Bstr(byteArrayOf(0x01, 0x02, 0x03)),
)

/** The issuer display metadata a real PID issuer publishes, in two locales. */
internal fun sampleIssuerMetadata(docType: String) = IssuerMetadata(
    documentConfigurationIdentifier = docType,
    credentialIssuerIdentifier = "https://fixture.issuer.invalid",
    issuerDisplay = listOf(
        IssuerMetadata.IssuerDisplay(
            name = "Fixture Issuer",
            locale = "en",
            logo = IssuerMetadata.Logo(uri = "https://fixture.issuer.invalid/en.png"),
        ),
        IssuerMetadata.IssuerDisplay(
            name = "Fixture Vydavatel",
            locale = "sk",
            logo = IssuerMetadata.Logo(uri = "https://fixture.issuer.invalid/sk.png"),
        ),
    ),
)

/** True when this store holds no document of ours — i.e. the fixture still needs seeding. */
internal suspend fun MultipazWalletStore.isEmpty(): Boolean =
    documentStore.listDocuments()
        .none { it.eudiMetadata?.documentManagerId == documentManagerId }

private fun issuerNamespacesOf(
    namespace: String,
    elements: List<Pair<String, DataItem>>,
    random: Random,
): IssuerNamespaces = IssuerNamespaces(
    data = mapOf(
        namespace to elements.mapIndexed { index, (name, value) ->
            name to IssuerSignedItem.fromValues(
                digestId = index.toLong(),
                // ISO 18013-5 §9.1.2.5 requires at least 16 bytes of salt per data element.
                random = ByteString(random.nextBytes(16)),
                dataElementIdentifier = name,
                dataElementValue = value,
            )
        }.toMap(),
    ),
)

/**
 * Builds the `IssuerSigned` structure a credential is certified with: the data elements plus a
 * `COSE_Sign1` whose payload is the tagged, encoded Mobile Security Object.
 *
 * The MSO is what makes this work at all — `MdocCredential.certify` reads the credential's validity
 * window out of `mso.validFrom`/`validUntil`, so a credential cannot be certified without one.
 */
private suspend fun issuerSignedDataFor(
    docType: String,
    issuerNamespaces: IssuerNamespaces,
    deviceKey: EcPublicKey,
    validFrom: Instant,
    validUntil: Instant,
    revocationStatus: RevocationStatus? = null,
): ByteString {
    val mso = MobileSecurityObjectGenerator(
        digestAlgorithm = Algorithm.SHA256,
        docType = docType,
        deviceKey = deviceKey,
    )
        .addValueDigests(issuerNamespaces)
        .setValidityInfo(
            // validFrom must not precede `signed`, per ISO 18013-5 §9.1.2.4.
            signed = validFrom,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
        )
        .generate()
        .let { generated ->
            // `MobileSecurityObjectGenerator` has no way to set the `status` element, so it is spliced
            // in afterwards: parse what the generator produced, copy it with the status, re-encode.
            // Going through multipaz's own parser and `toDataItem` — rather than hand-writing CBOR —
            // means the value digests are never touched and the result is exactly what the read path
            // will parse back out.
            if (revocationStatus == null) generated else Cbor.encode(
                MobileSecurityObject.fromDataItem(Cbor.decode(generated))
                    .copy(revocationStatus = revocationStatus)
                    .toDataItem()
            )
        }

    // Stands in for an issuer's document-signer key. Anonymous and ephemeral: the read path never
    // verifies this signature, and minting a fake IACA/DS chain would add nothing to verify.
    val fakeIssuerKey = AsymmetricKey.AnonymousExplicit(
        privateKey = Crypto.createEcPrivateKey(EcCurve.P256),
    )

    val issuerAuth = Cose.coseSign1Sign(
        signingKey = fakeIssuerKey,
        message = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(mso))),
        includeMessageInPayload = true,
        protectedHeaders = emptyMap(),
        unprotectedHeaders = emptyMap(),
    )

    return ByteString(
        Cbor.encode(
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", issuerAuth.toDataItem())
            },
        ),
    )
}

private fun createKeySettingsFor(secureArea: SecureArea): CreateKeySettings = when (secureArea) {
    // No user-authentication requirement: the fixture must be seedable without a biometric prompt.
    is SecureEnclaveSecureArea -> SecureEnclaveCreateKeySettings.Builder().build()
    else -> SoftwareCreateKeySettings.Builder().build()
}

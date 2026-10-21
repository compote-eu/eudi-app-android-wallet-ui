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

import eu.europa.ec.shared.wallet.multipaz.harness.issuerNamespacesOf
import eu.europa.ec.shared.wallet.multipaz.harness.issuerSignedDataFor
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * A credential is stored only as its issuer signed it — wallet-core #446's certifier checks, on every
 * credential whatever the trust policy says about its issuer.
 *
 * Until 2026-10-21 iOS checked the signature of a PID alone and the digests of nothing, so an mDL, an OP
 * or an eDiploma was stored however it had been altered on the way.
 */
class CredentialIntegrityTest {

    private val mdlDocType = "org.iso.18013.5.1.mDL"
    private val pidDocType = "eu.europa.ec.eudi.pid.1"

    /** An mdoc as an issuer sends it: base64url `IssuerSigned`, signed with [signingKey] under [chain]. */
    private suspend fun mdoc(
        signer: TestSigner,
        docType: String = mdlDocType,
        givenName: String = "Valid",
        chain: X509CertChain? = signer.chain,
        signingKey: EcPrivateKey = signer.key,
    ): String = issuerSignedDataFor(
        docType = docType,
        issuerNamespaces = issuerNamespacesOf(docType, samplePidElements(givenName = givenName), Random.Default),
        deviceKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
        validFrom = Clock.System.now() - 1.days,
        validUntil = Clock.System.now() + 30.days,
        issuerCertChain = chain,
        issuerSigningKey = signingKey,
    ).toByteArray().toBase64Url()

    /** [signed]'s MSO and signature over [other]'s data elements: what an alteration on the way leaves. */
    private fun withDataElementsOf(signed: String, other: String): String {
        val original = Cbor.decode(signed.fromBase64Url())
        val altered = Cbor.decode(other.fromBase64Url())
        return Cbor.encode(
            buildCborMap {
                put("nameSpaces", altered["nameSpaces"])
                put("issuerAuth", original["issuerAuth"])
            }
        ).toBase64Url()
    }

    /** An SD-JWT VC as an issuer sends it, with [signer]'s chain in its `x5c` unless [withX5c] is false. */
    private suspend fun sdJwtVc(
        signer: TestSigner,
        signingKey: EcPrivateKey = signer.key,
        withX5c: Boolean = true,
    ): String {
        val header = buildJsonObject {
            put("alg", "ES256")
            put("typ", "dc+sd-jwt")
            if (withX5c) put("x5c", signer.chain.toX5c(excludeRoot = false))
        }
        val body = buildJsonObject {
            put("iss", "https://issuer.test")
            put("vct", "urn:eudi:op:1")
            put("_sd_alg", "sha-256")
        }
        val signingInput = listOf(header, body).joinToString(".") { it.toString().encodeToByteArray().toBase64Url() }
        val signature = Crypto.sign(signingKey, Algorithm.ES256, signingInput.encodeToByteArray())
        return "$signingInput.${signature.toCoseEncoded().toBase64Url()}~"
    }

    private suspend fun assertRefused(credential: String, expectedDocType: String?, because: String) {
        val refusal = assertFailsWith<CredentialIntegrityException> { verifyCredentialIntegrity(credential, expectedDocType) }
        assertTrue(because in refusal.message.orEmpty(), "unexpected: ${refusal.message}")
    }

    @Test
    fun an_mdoc_as_its_issuer_signed_it_is_accepted() = runTest {
        verifyCredentialIntegrity(mdoc(testSigner()), expectedDocType = mdlDocType)
    }

    @Test
    fun an_mdoc_whose_data_elements_changed_after_signing_is_refused() = runTest {
        // The signature still verifies — it is over the MSO, which nobody touched. Only the digests show it.
        val signer = testSigner()
        val altered = withDataElementsOf(mdoc(signer), mdoc(signer, givenName = "Mallory"))

        assertRefused(altered, mdlDocType, because = "does not match its digest")
    }

    @Test
    fun an_mdoc_not_signed_by_the_first_certificate_of_its_chain_is_refused() = runTest {
        val credential = mdoc(testSigner(), signingKey = Crypto.createEcPrivateKey(EcCurve.P256))

        assertRefused(credential, mdlDocType, because = "not signed by the first certificate")
    }

    @Test
    fun an_mdoc_naming_no_chain_is_refused() = runTest {
        assertRefused(mdoc(testSigner(), chain = null), mdlDocType, because = "no x5chain")
    }

    @Test
    fun an_mdoc_of_another_type_than_the_configuration_asked_for_is_refused() = runTest {
        // An mDL answering a PID configuration, signed correctly — the docType is the only thing wrong.
        assertRefused(mdoc(testSigner()), pidDocType, because = "is for $mdlDocType, not the $pidDocType")
    }

    @Test
    fun an_mdocs_type_is_not_compared_when_the_configuration_is_unknown() = runTest {
        verifyCredentialIntegrity(mdoc(testSigner()), expectedDocType = null)
    }

    @Test
    fun an_sd_jwt_vc_as_its_issuer_signed_it_is_accepted() = runTest {
        verifyCredentialIntegrity(sdJwtVc(testSigner()), expectedDocType = null)
    }

    @Test
    fun an_sd_jwt_vc_not_signed_by_the_first_certificate_of_its_chain_is_refused() = runTest {
        val credential = sdJwtVc(testSigner(), signingKey = Crypto.createEcPrivateKey(EcCurve.P256))

        assertRefused(credential, null, because = "not signed by the first certificate")
    }

    @Test
    fun an_sd_jwt_vc_naming_no_chain_is_refused() = runTest {
        assertRefused(sdJwtVc(testSigner(), withX5c = false), null, because = "no x5c")
    }

    @Test
    fun something_that_is_neither_is_refused_rather_than_stored() = runTest {
        assertFailsWith<CredentialIntegrityException> { verifyCredentialIntegrity("bm90LWEtY3JlZGVudGlhbA", null) }
        assertFailsWith<CredentialIntegrityException> { verifyCredentialIntegrity("not.a.credential~", null) }
    }
}

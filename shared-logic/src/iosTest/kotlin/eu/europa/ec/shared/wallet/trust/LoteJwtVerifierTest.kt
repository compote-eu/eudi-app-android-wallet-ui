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

package eu.europa.ec.shared.wallet.trust

import eu.europa.ec.eudi.etsi119602.consultation.VerifyJwtSignature
import eu.europa.ec.eudi.etsi1196x2.consultation.CertificationChainValidation
import eu.europa.ec.eudi.etsi1196x2.consultation.NonEmptyList
import eu.europa.ec.eudi.etsi1196x2.consultation.ValidateCertificateChain
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class LoteJwtVerifierTest {

    @Test
    fun a_list_signed_under_a_pinned_ca_is_verified() = runTest {
        val ca = certificateAuthority("CN=Trusted List CA,C=EU")
        val signer = ca.issue("CN=Trusted List Signer,C=EU")

        val outcome = verifierPinning(ca.certificate).invoke(signer.sign(LIST))

        assertIs<VerifyJwtSignature.Outcome.Verified>(outcome, "$outcome")
    }

    @Test
    fun a_list_signed_by_a_pinned_signer_itself_is_verified() = runTest {
        val signer = certificateAuthority("CN=Trusted List CA,C=EU").issue("CN=Trusted List Signer,C=EU")

        val outcome = LoteJwtVerifier(signerAnchors = anchors(signer.certificate)).invoke(signer.sign(LIST))

        assertIs<VerifyJwtSignature.Outcome.Verified>(outcome, "$outcome")
    }

    @Test
    fun a_list_signed_by_a_certificate_of_its_own_is_refused() = runTest {
        val pinned = certificateAuthority("CN=Trusted List CA,C=EU")
        val substitute = certificateAuthority("CN=Trusted List Signer,C=EU")

        val outcome = verifierPinning(pinned.certificate).invoke(substitute.sign(LIST))

        assertIs<VerifyJwtSignature.Outcome.NotVerified>(outcome, "$outcome")
    }

    @Test
    fun a_list_carrying_an_unpinned_ca_in_its_own_chain_is_refused() = runTest {
        val pinned = certificateAuthority("CN=Trusted List CA,C=EU")
        val otherCa = certificateAuthority("CN=Trusted List CA,C=EU")
        val signer = otherCa.issue("CN=Trusted List Signer,C=EU")

        val outcome = verifierPinning(pinned.certificate)
            .invoke(signer.sign(LIST, x5c = listOf(signer.certificate, otherCa.certificate)))

        assertIs<VerifyJwtSignature.Outcome.NotVerified>(outcome, "$outcome")
    }

    @Test
    fun a_list_whose_pinned_signer_has_expired_is_refused() = runTest {
        val signer = certificateAuthority("CN=Trusted List CA,C=EU")
            .issue("CN=Trusted List Signer,C=EU", validUntil = Clock.System.now() - 1.days)

        val outcome = LoteJwtVerifier(signerAnchors = anchors(signer.certificate)).invoke(signer.sign(LIST))

        assertIs<VerifyJwtSignature.Outcome.NotVerified>(outcome, "$outcome")
    }

    @Test
    fun a_list_changed_after_signing_is_refused() = runTest {
        val ca = certificateAuthority("CN=Trusted List CA,C=EU")
        val (header, _, signature) = ca.issue("CN=Trusted List Signer,C=EU").sign(LIST).split('.')

        val outcome = verifierPinning(ca.certificate)
            .invoke("$header.${segment("""{"LoTE":"another list"}""")}.$signature")

        assertIs<VerifyJwtSignature.Outcome.NotVerified>(outcome, "$outcome")
    }

    /** Real data, so it can expire: the signer is valid until 2028-05-13, the CA until 2034-07-06. */
    @Test
    fun the_pinned_eu_signer_is_issued_by_the_pinned_eu_ca() = runTest {
        val (ca, signer) = LoteSignerAnchors.EU_CERTIFICATES

        X509CertChain(listOf(signer, ca)).validate()
    }

    @Test
    fun the_bundled_eu_anchors_trust_the_eu_lists_signer() = runTest {
        val signer = LoteSignerAnchors.EU_CERTIFICATES.last()

        val validation = LoteJwtVerifier.SIGNER_VALIDATION(listOf(signer).toTrustChain(), LoteSignerAnchors.EU)

        assertIs<CertificationChainValidation.Trusted<*>>(validation, "$validation")
    }

    private class Party(val key: EcPrivateKey, val certificate: X509Cert)

    private fun anchors(vararg certificates: X509Cert) = NonEmptyList(certificates.toList().toTrustChain())

    /** A verifier that trusts a chain to one of [anchors] through [CHAINS_TO_AN_ANCHOR]. */
    private fun verifierPinning(vararg anchors: X509Cert) =
        LoteJwtVerifier(signerAnchors = anchors(*anchors), validateSigner = CHAINS_TO_AN_ANCHOR)

    private suspend fun certificateAuthority(name: String): Party {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val subject = X500Name.fromName(name)
        val certificate = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
            serialNumber = ASN1Integer(1L),
            subject = subject,
            issuer = subject,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 365.days,
        )
            .includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN, X509KeyUsage.CRL_SIGN))
            .setBasicConstraints(ca = true, pathLenConstraint = 0)
            .build()
        return Party(key, certificate)
    }

    private suspend fun Party.issue(
        name: String,
        validUntil: Instant = Clock.System.now() + 30.days,
    ): Party {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val certificate = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = this.key),
            serialNumber = ASN1Integer(2L),
            subject = X500Name.fromName(name),
            issuer = this.certificate.subject,
            validFrom = Clock.System.now() - 2.days,
            validUntil = validUntil,
        )
            .includeSubjectKeyIdentifier()
            .setAuthorityKeyIdentifierToCertificate(this.certificate)
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()
        return Party(key, certificate)
    }

    /** A compact ES256 JWS over [payload], signed with this party's key, carrying [x5c]. */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun Party.sign(payload: String, x5c: List<X509Cert> = listOf(certificate)): String {
        val chain = x5c.joinToString(",") { "\"${Base64.Default.encode(it.encoded.toByteArray())}\"" }
        val signingInput = "${segment("""{"alg":"ES256","x5c":[$chain]}""")}.${segment(payload)}"
        val signature = Crypto.sign(key, Algorithm.ES256, signingInput.encodeToByteArray())
        return "$signingInput.${URL_SAFE.encode(signature.toCoseEncoded())}"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun segment(json: String) = URL_SAFE.encode(json.encodeToByteArray())

    private companion object {
        @OptIn(ExperimentalEncodingApi::class)
        val URL_SAFE = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        const val LIST = """{"LoTE":{"ListAndSchemeInformation":{"LoTEType":"test"}}}"""

        /**
         * Stands in for Apple's PKIX, which the simulator cannot run (`OSStatus -26276` for chains a phone
         * accepts): trusted when the presented chain, with an anchor appended, passes multipaz's own chain
         * validation — signatures, names, CA flags, key usage and validity.
         */
        val CHAINS_TO_AN_ANCHOR = object : ValidateCertificateChain<List<NSData>, NSData> {
            override suspend fun invoke(
                chain: List<NSData>,
                trustAnchors: NonEmptyList<NSData>,
            ): CertificationChainValidation<NSData> {
                val presented = chain.map { it.toX509Cert() }
                val anchor = trustAnchors.list.firstOrNull { anchor ->
                    runCatching { X509CertChain(presented + anchor.toX509Cert()).validate() }.isSuccess
                }
                return anchor?.let { CertificationChainValidation.Trusted(it) }
                    ?: CertificationChainValidation.NotTrusted(IllegalStateException("the chain reaches no anchor"))
            }
        }

        @OptIn(ExperimentalEncodingApi::class)
        fun NSData.toX509Cert() = X509Cert(ByteString(Base64.Default.decode(base64EncodedStringWithOptions(0uL))))
    }
}

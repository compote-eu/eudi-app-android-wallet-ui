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
import eu.europa.ec.eudi.etsi1196x2.consultation.ValidateCertificateChainUsingDirectTrustIos
import eu.europa.ec.eudi.etsi1196x2.consultation.ValidateCertificateChainUsingPKIXIos
import eu.europa.ec.eudi.etsi1196x2.consultation.or
import eu.europa.ec.eudi.etsi1196x2.consultation.pkix.PKIXConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.X509Cert
import platform.Foundation.NSData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

/**
 * Accepts a list of trusted entities only when it is signed by a signer the wallet already trusts.
 *
 * The iOS counterpart of wallet-core's `LoteJwtVerifier` as rewritten in eudi-lib-android-wallet-core
 * #441 (`release/0.32.0`), check for check. A list is verified when:
 * - its `x5c` header carries the certificate chain of its signer;
 * - the signing certificate, `x5c[0]`, is valid now;
 * - [validateSigner] trusts that chain against [signerAnchors] — the signer is one of the anchors, or
 *   chains to one (PKIX); and
 * - the JWS signature verifies with the signing certificate's key.
 *
 * Until #441, wallet-core verified the signature against `x5c[0]` and nothing else, and this class did the
 * same for parity. That proves the list was not changed after signing, not *who* signed it: anyone able to
 * serve a list could sign one with a certificate of their own. The anchors are what close that.
 */
class LoteJwtVerifier(
    /** See [LoteSignerAnchors]; never certificates read from a list. */
    private val signerAnchors: NonEmptyList<NSData> = LoteSignerAnchors.EU,
    private val validateSigner: ValidateCertificateChain<List<NSData>, NSData> = SIGNER_VALIDATION,
) : VerifyJwtSignature {

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun invoke(jwt: String): VerifyJwtSignature.Outcome {
        val parts = jwt.trim().split('.')
        if (parts.size != 3) {
            return notVerified("not a compact JWS: ${parts.size} part(s), expected 3")
        }

        val header = runCatching {
            Json.parseToJsonElement(parts[0].decodeJoseSegment().decodeToString()).jsonObject
        }.getOrElse { return notVerified("unparseable JWS header: ${it.message}") }

        // `x5c` entries are DER in *standard*-alphabet base64 (RFC 7515 §4.1.6), unlike the JWS segments
        // themselves, which are url-safe. Getting these two the wrong way round fails as a parse error
        // rather than a signature mismatch, which is worth knowing when this reports "unparseable".
        val x5c = header["x5c"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (x5c.isEmpty()) return notVerified("no x5c in the JWS header, so nothing to verify against")
        val chain = runCatching {
            x5c.map { X509Cert(ByteString(Base64.Default.decode(it))) }
        }.getOrElse { return notVerified("x5c is not a certificate chain: ${it.message}") }
        val certificate = chain.first()

        val now = Clock.System.now()
        if (now < certificate.validityNotBefore || now > certificate.validityNotAfter) {
            return notVerified(
                "the list's signer ${certificate.subject.name} is valid only from " +
                    "${certificate.validityNotBefore} to ${certificate.validityNotAfter}",
            )
        }

        val validation = validateSigner(chain.toTrustChain(), signerAnchors)
        if (validation is CertificationChainValidation.NotTrusted) {
            return VerifyJwtSignature.Outcome.NotVerified(validation.cause)
        }

        val algorithm = runCatching {
            Algorithm.fromJoseAlgorithmIdentifier(
                header["alg"]?.jsonPrimitive?.content ?: return notVerified("no alg in the JWS header")
            )
        }.getOrElse { return notVerified("unsupported alg: ${it.message}") }

        val signature = runCatching {
            // JWS ECDSA signatures are the raw r‖s pair, which is also how COSE encodes them — so this
            // is the right reader, despite the name. A DER reader would reject them.
            EcSignature.fromCoseEncoded(parts[2].decodeJoseSegment())
        }.getOrElse { return notVerified("unreadable signature: ${it.message}") }

        return runCatching {
            Crypto.checkSignature(
                publicKey = certificate.ecPublicKey,
                message = "${parts[0]}.${parts[1]}".encodeToByteArray(),
                algorithm = algorithm,
                signature = signature,
            )
        }.fold(
            onSuccess = { VerifyJwtSignature.Outcome.Verified(jwt) },
            onFailure = { VerifyJwtSignature.Outcome.NotVerified(it) },
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.decodeJoseSegment(): ByteArray =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(this)

    private fun notVerified(reason: String) =
        VerifyJwtSignature.Outcome.NotVerified(IllegalArgumentException(reason))

    internal companion object {
        /**
         * A pinned signer, or a chain to a pinned CA — wallet-core's `DirectTrust or pkix`. Revocation is
         * off, as for every other chain this wallet validates (Android's `relaxPkixRevocation()`).
         */
        @OptIn(ExperimentalForeignApi::class)
        val SIGNER_VALIDATION: ValidateCertificateChain<List<NSData>, NSData> =
            ValidateCertificateChainUsingDirectTrustIos or
                ValidateCertificateChainUsingPKIXIos(PKIXConfiguration(isRevocationEnabled = false))
    }
}

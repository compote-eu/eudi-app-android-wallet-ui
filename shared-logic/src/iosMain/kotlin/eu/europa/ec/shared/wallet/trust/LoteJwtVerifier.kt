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
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.X509Cert
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Checks a list-of-trusted-entities JWS against the certificate carried in its own `x5c` header.
 *
 * The iOS counterpart of wallet-core's `eu.europa.ec.eudi.wallet.trust.LoteJwtVerifier`, and
 * deliberately the *same* check rather than a better one, so the two platforms agree. Read from that
 * class's bytecode: parse the JWS, take `x5c[0]` from the header, build a verifier from that
 * certificate's public key, and verify. It references no `TrustAnchor`, `KeyStore`, `CertPathValidator`
 * or `PKIXParameters` — `CertificateFactory` is the only certificate machinery it touches, and only to
 * parse.
 *
 * ⚠️ **So this establishes integrity, not authenticity, on both platforms.** A party able to serve a
 * substituted list can embed its own certificate and sign with it, and this check passes; what actually
 * ties the list to the EU is TLS to the trusted-list host. Bundling a scheme-operator anchor and
 * chaining to it would be stronger — and would be a deliberate *divergence* from Android, not parity,
 * so it is not done here. See the ledger.
 */
class LoteJwtVerifier : VerifyJwtSignature {

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
        val leaf = header["x5c"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            ?: return notVerified("no x5c in the JWS header, so nothing to verify against")
        val certificate = runCatching {
            X509Cert(ByteString(Base64.Default.decode(leaf)))
        }.getOrElse { return notVerified("x5c[0] is not a certificate: ${it.message}") }

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
}

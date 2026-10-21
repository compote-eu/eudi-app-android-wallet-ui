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

import kotlinx.coroutines.CancellationException
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.sdjwt.SdJwt
import org.multipaz.util.fromBase64Url

/**
 * A credential refused because it is not what its issuer signed. Not a trust verdict: who signed it is a
 * separate question, so the screen shows an ordinary failure, as Android does when a certifier refuses one.
 */
internal class CredentialIntegrityException(message: String) : IllegalStateException(message)

/**
 * Refuses [credential] unless it is exactly what its issuer signed — the checks wallet-core's certifiers
 * make before storing any credential since #446 (`release/0.32.0`, 2026-10-21), on every credential, under
 * every trust policy and with no trust configured at all:
 *
 * - **mdoc** (`MsoMdocCredentialCertifier`): `issuerAuth` carries an `x5chain` and verifies with its first
 *   certificate (ISO/IEC 18013-5 9.1.2.4); the MSO's `docType` is [expectedDocType]; every
 *   `IssuerSignedItemBytes` digests to the MSO's value for its `digestID` (9.1.2.5).
 * - **SD-JWT VC** (`SdJwtVcCredentialCertifier`): the issuer-signed JWT carries an `x5c` and verifies with
 *   its first certificate (OpenID4VC HAIP 1.0 §6.1) — multipaz's own `SdJwt.verify`, the call Android makes.
 *
 * multipaz's `certify` checks none of this. Until this, iOS checked the signature of a PID only
 * ([enforcePidSigners]) and the digests of nothing, so a document of any other kind — an mDL, Plaut's OP,
 * an eDiploma — was stored however it had been altered on the way. Whether its signer is *trusted* stays
 * the trust policy's question: [enforcePidSigners] for a PID, nothing else for everything else, as Android.
 *
 * @param credential as the issuer sent it: an mdoc as base64url `IssuerSigned`, an SD-JWT VC compact.
 * @param expectedDocType the `docType` of the configuration that was asked for, or null when it is not
 *   known. Compared for an mdoc only, as Android compares it.
 * @throws CredentialIntegrityException when any check fails, or the credential cannot be read.
 */
internal suspend fun verifyCredentialIntegrity(credential: String, expectedDocType: String?) {
    // Told apart as [signerOf] does: a JWS contains `.`, the base64url alphabet an mdoc is sent in does not.
    if ('.' in credential) verifySdJwtVc(credential) else verifyMdoc(credential, expectedDocType)
}

private suspend fun verifyMdoc(credential: String, expectedDocType: String?) {
    val issuerSigned = readOrRefuse("an mdoc that will not parse") { Cbor.decode(credential.fromBase64Url()) }
    val issuerAuth = readOrRefuse("an mdoc without a readable issuerAuth") { issuerSigned["issuerAuth"].asCoseSign1 }
    verifyIssuerAuth(issuerAuth)

    val mso = readOrRefuse("an mdoc whose MSO will not parse") {
        MobileSecurityObject.fromDataItem(Cbor.decode(Cbor.decode(issuerAuth.payload!!).asTagged.asBstr))
    }
    if (expectedDocType != null && mso.docType != expectedDocType) {
        throw CredentialIntegrityException("an mdoc's MSO is for ${mso.docType}, not the $expectedDocType asked for")
    }
    issuerSigned.getOrNull("nameSpaces")?.let { verifyValueDigests(it, mso) }
}

/** The MSO's signature, with the document signer's key — the first certificate of the `x5chain`. */
private suspend fun verifyIssuerAuth(issuerAuth: CoseSign1) {
    val chain = issuerAuth.unprotectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]?.asX509CertChain
        ?: throw CredentialIntegrityException("an mdoc names no x5chain in its issuerAuth")
    val algorithm = issuerAuth.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_ALG)]?.asNumber?.toInt()
        ?: throw CredentialIntegrityException("an mdoc's issuerAuth names no algorithm")
    val signedByChain = isSignedBy(chain.certificates.first()) { key ->
        Cose.coseSign1Check(
            publicKey = key,
            detachedData = null,
            signature = issuerAuth,
            signatureAlgorithm = Algorithm.fromCoseAlgorithmIdentifier(algorithm),
        )
    }
    if (!signedByChain) {
        throw CredentialIntegrityException("an mdoc's MSO is not signed by the first certificate of its x5chain")
    }
}

/**
 * Every data element against its digest in the MSO. The digest is over the element's
 * `IssuerSignedItemBytes` — the tag-24 byte string as sent — so it is taken over that, re-encoded, as
 * Android takes it.
 */
private suspend fun verifyValueDigests(nameSpaces: DataItem, mso: MobileSecurityObject) {
    for ((nameSpaceKey, items) in readOrRefuse("an mdoc whose nameSpaces will not parse") { nameSpaces.asMap }) {
        val nameSpace = nameSpaceKey.asTstr
        val digests = mso.valueDigests[nameSpace]
            ?: throw CredentialIntegrityException("an mdoc's MSO has no digests for namespace $nameSpace")
        for (itemBytes in items.asArray) {
            val item = readOrRefuse("an mdoc data element that will not parse") { Cbor.decode(itemBytes.asTagged.asBstr) }
            val digestId = item["digestID"].asNumber
            val expected = digests[digestId]
                ?: throw CredentialIntegrityException("an mdoc's MSO has no digest $digestId in namespace $nameSpace")
            val actual = Crypto.digest(mso.digestAlgorithm, Cbor.encode(itemBytes))
            if (!actual.contentEquals(expected.toByteArray())) {
                throw CredentialIntegrityException(
                    "an mdoc's ${item["elementIdentifier"].asTstr} in $nameSpace does not match its digest in the MSO",
                )
            }
        }
    }
}

private suspend fun verifySdJwtVc(credential: String) {
    val sdJwt = readOrRefuse("an SD-JWT VC that will not parse") { SdJwt.fromCompactSerialization(credential) }
    val chain = sdJwt.x5c ?: throw CredentialIntegrityException("an SD-JWT VC names no x5c in its header")
    val signedByChain = isSignedBy(chain.certificates.first()) { key -> sdJwt.verify(issuerKey = key) }
    if (!signedByChain) {
        throw CredentialIntegrityException("an SD-JWT VC is not signed by the first certificate of its x5c")
    }
}

/** [read]'s value; anything it throws but a cancellation becomes the refusal [what] describes. */
private inline fun <T> readOrRefuse(what: String, read: () -> T): T =
    try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        throw CredentialIntegrityException("$what: ${t.message}")
    }

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
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.sdjwt.SdJwt
import org.multipaz.util.fromBase64Url

/**
 * A credential refused because it is not what its issuer signed, or not bound to the key it was asked for —
 * or, before it is asked for, a key attestation that would bind it to another one ([verifyAttestedKeys]).
 * Not a trust verdict: who signed it is a separate question, so the screen shows an ordinary failure, as
 * Android does when a certifier refuses one.
 */
internal class CredentialIntegrityException(message: String) : IllegalStateException(message)

/**
 * Refuses [credential] unless it is exactly what its issuer signed — the checks wallet-core's certifiers
 * make before storing any credential since #446 (`release/0.32.0`, 2026-10-21), on every credential, under
 * every trust policy and with no trust configured at all:
 *
 * - **mdoc** (`MsoMdocCredentialCertifier`): `issuerAuth` carries an `x5chain` and verifies with its first
 *   certificate (ISO/IEC 18013-5 9.1.2.4); the MSO's `docType` is [expectedDocType]; every
 *   `IssuerSignedItemBytes` digests to the MSO's value for its `digestID` (9.1.2.5). Beyond Android, as the
 *   official iOS wallet's data-model library now refuses them: no data element named twice in a namespace,
 *   and none whose bytes do not re-encode as sent (PAR_ISO_DATA_MODEL_3 and _15).
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
    // Bounded before anything decodes it, multipaz's provisioning after this included.
    val issuerSigned = readOrRefuse("an mdoc that will not parse") {
        Cbor.decode(credential.fromBase64Url().requireCborWithinDepth())
    }
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
        val named = mutableSetOf<String>()
        for (itemBytes in items.asArray) {
            val sent = readOrRefuse("an mdoc data element that is not an embedded item") { itemBytes.asTagged.asBstr }
            val item = readOrRefuse("an mdoc data element that will not parse") { Cbor.decode(sent) }
            val identifier = readOrRefuse("an mdoc data element without an identifier") { item["elementIdentifier"].asTstr }
            // Which of two same-named elements counts would depend on who reads them.
            if (!named.add(identifier)) {
                throw CredentialIntegrityException("an mdoc names $identifier twice in $nameSpace")
            }
            val digestId = item["digestID"].asNumber
            val expected = digests[digestId]
                ?: throw CredentialIntegrityException("an mdoc's MSO has no digest $digestId in namespace $nameSpace")
            val actual = Crypto.digest(mso.digestAlgorithm, Cbor.encode(itemBytes))
            if (!actual.contentEquals(expected.toByteArray())) {
                throw CredentialIntegrityException(
                    "an mdoc's $identifier in $nameSpace does not match its digest in the MSO",
                )
            }
            // multipaz presents an element by encoding it again, and a verifier digests what it is sent, so
            // bytes that do not come back the same — a map key given twice, an integer longer than it needs —
            // could never be presented as the issuer signed them.
            if (!Cbor.encode(item).contentEquals(sent)) {
                throw CredentialIntegrityException(
                    "an mdoc's $identifier in $nameSpace is encoded in a way this wallet cannot present as signed",
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

/**
 * Refuses [credentials] unless there are as many as the keys [request]'s proofs carry, and each is bound to
 * the key at its own position — the checks wallet-core makes before storing any credential: as many
 * credentials as keys (`DocumentManagerImpl.storeIssuedDocument`), and an mdoc's MSO `deviceKey`
 * (`MsoMdocCredentialCertifier`) or an SD-JWT VC's `cnf` key (`SdJwtVcCredentialCertifier`) the key it is
 * stored under.
 *
 * The position is the pairing. multipaz's client and [IosOpenID4VciProvisioningClient] both hand the n-th
 * credential of the response to the pending credential behind the n-th key of the request, and multipaz
 * certifies it there without looking at the key it is bound to — so one bound to any other key would be
 * stored on a key that cannot present it.
 *
 * A request without proofs asks for credentials bound to no key, and is not checked.
 *
 * @param request the credential request as the wallet wrote it, before any encryption.
 * @param credentials in the issuer's order: an mdoc as base64url `IssuerSigned`, an SD-JWT VC compact.
 * @throws CredentialIntegrityException when the counts differ, a credential is bound to another key or to
 *   none, or the request's keys cannot be read.
 */
internal suspend fun verifyKeyBinding(request: JsonObject, credentials: List<String>) {
    val keys = proofKeysOf(request) ?: return
    if (credentials.size != keys.size) {
        throw CredentialIntegrityException("the issuer sent ${credentials.size} credential(s) for ${keys.size} key(s)")
    }
    credentials.zip(keys).forEachIndexed { index, (credential, key) ->
        val position = "credential ${index + 1} of ${keys.size}"
        val bound = readOrRefuse("$position will not parse") { boundKeyOf(credential) }
            ?: throw CredentialIntegrityException("$position names no key it is bound to (no cnf)")
        if (bound != key) {
            throw CredentialIntegrityException("$position is bound to another key than the one asked for there")
        }
    }
}

/**
 * The keys [request]'s proofs carry, in the order the wallet sent them; null when it carries no proofs.
 *
 * A `jwt` proof names its key in its header's `jwk`, as multipaz signs it. An `attestation` names the keys it
 * attests in `attested_keys`, which [verifyAttestedKeys] holds to the keys `IosOpenID4VciBackend` sent the
 * wallet provider, in the order of the pending credentials they belong to.
 */
internal fun proofKeysOf(request: JsonObject): List<EcPublicKey>? {
    val proofs = request["proofs"] as? JsonObject ?: return null
    return readOrRefuse("a credential request whose proofs name no key this wallet can read") {
        val jwts = proofs["jwt"] as? JsonArray
        val attestations = proofs["attestation"] as? JsonArray
        when {
            jwts != null -> jwts.map { jwt ->
                EcPublicKey.fromJwk(jwtPart(jwt.jsonPrimitive.content, 0)["jwk"]!!.jsonObject)
            }

            attestations != null -> attestations.flatMap { attestation ->
                jwtPart(attestation.jsonPrimitive.content, 1)["attested_keys"]!!.jsonArray
                    .map { EcPublicKey.fromJwk(it.jsonObject) }
            }

            else -> error("no proof type this wallet sends: ${proofs.keys}")
        }
    }
}

/**
 * Refuses [keyAttestation] unless its `attested_keys` are exactly the keys [sent], in the order sent.
 *
 * The issuer binds its credentials to the keys in the order `attested_keys` lists them, and that list is the
 * only one [verifyKeyBinding] can read from the request; multipaz stores them under the pending credentials
 * in the order the keys were sent. A wallet provider that reordered the keys would make the two disagree
 * without either check noticing, and credentials would be stored under keys that cannot present them.
 */
internal fun verifyAttestedKeys(keyAttestation: String, sent: List<EcPublicKey>) {
    val attested = readOrRefuse("a key attestation whose attested_keys this wallet cannot read") {
        jwtPart(keyAttestation, 1)["attested_keys"]!!.jsonArray.map { EcPublicKey.fromJwk(it.jsonObject) }
    }
    val refusal = when {
        attested == sent -> return
        attested.size == sent.size && attested.toSet() == sent.toSet() ->
            "the wallet provider attested the wallet's keys in another order than they were sent"
        else -> "the wallet provider attested ${attested.size} key(s), not the ${sent.size} the wallet sent"
    }
    throw CredentialIntegrityException(refusal)
}

/** The JSON of a compact JWT's header (0) or payload (1). */
private fun jwtPart(jwt: String, index: Int): JsonObject =
    Json.parseToJsonElement(jwt.split('.')[index].fromBase64Url().decodeToString()).jsonObject

/** The key [credential], as its issuer sent it, is bound to; null for an SD-JWT VC without a `cnf` key. */
private suspend fun boundKeyOf(credential: String): EcPublicKey? =
    // Told apart as [verifyCredentialIntegrity] tells them apart.
    if ('.' in credential) boundKeyOf(credential.encodeToByteString(), isMdoc = false)
    else boundKeyOf(ByteString(credential.fromBase64Url()), isMdoc = true)

/** The public key [issuerData] is bound to: an mdoc's MSO `deviceKey`, an SD-JWT VC's `cnf` key. */
internal suspend fun boundKeyOf(issuerData: ByteString, isMdoc: Boolean): EcPublicKey? = if (isMdoc) {
    val issuerAuth = Cbor.decode(issuerData.toByteArray())["issuerAuth"].asCoseSign1
    MobileSecurityObject.fromDataItem(Cbor.decode(Cbor.decode(issuerAuth.payload!!).asTagged.asBstr)).deviceKey
} else {
    SdJwt.fromCompactSerialization(issuerData.toByteArray().decodeToString()).kbKey
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

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

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.Hpke
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The verifier's half of a DC API exchange, so the wallet's half can be driven without iOS.
 *
 * This exists because the one thing the simulator cannot do is *deliver* a request: authorization for
 * a document provider is unreachable there, and the OS only ever hands a provider ISO 18013-7 mdoc
 * requests, which the EUDI verifier does not speak (it pins `openid4vp-v1-signed`). So there is no
 * verifier — real or simulated — that can drive our extension's branch. Standing in for one is the
 * only way to exercise it against the live store before hardware exists.
 *
 * Deliberately in `iosMain` rather than test sources: the wallet probe is a launched app, not a test
 * binary, so it cannot reach `iosTest`. The identical construction in `IosDcApiPresenterTest` stays
 * where it is — a test that quietly depended on shipped harness code would be weaker for it.
 *
 * ⚠️ Multipaz's own types are `implementation`-scoped here, so everything crossing back out is a plain
 * Kotlin type. That is what lets `:shared-ui`'s probe use this at all.
 */
class DcApiProbeRequest internal constructor(
    /** The `{"deviceRequest":…,"encryptionInfo":…}` JSON an ISO 18013-7 scene would hand the provider. */
    val json: String,
    private val recipientKey: EcPrivateKey,
    private val encryptionInfoBase64: String,
) {

    /**
     * Decrypts what the wallet sent back, which is the only way to see what it actually disclosed.
     *
     * Two results in one: the plaintext says which claims left the wallet, and the fact that it
     * decrypts at all proves the session transcript. HPKE binds the transcript as `info`, so a
     * transcript built differently from multipaz's fails here rather than misleading.
     */
    suspend fun decryptResponse(responseJson: String, origin: String): ByteArray {
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val data = requireNotNull(response["data"]) { "no data object in the wallet's response" }
        val encoded = data.jsonObject["response"]!!.jsonPrimitive.content
        val envelope = Cbor.decode(encoded.fromBase64Url())
        require(envelope.asArray[0].asTstr == "dcapi") { "not a dcapi response envelope" }
        val parts = envelope.asArray[1].asMap

        val digest = Crypto.digest(
            Algorithm.SHA256,
            Cbor.encode(
                buildCborArray {
                    add(encryptionInfoBase64)
                    add(origin)
                }
            ),
        )
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(
                buildCborArray {
                    add("dcapi")
                    add(digest)
                }
            )
        }

        return Hpke.getDecrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = AsymmetricKey.AnonymousExplicit(privateKey = recipientKey),
            encapsulatedKey = parts[Tstr("enc")]!!.asBstr,
            info = Cbor.encode(sessionTranscript),
        ).decrypt(ciphertext = parts[Tstr("cipherText")]!!.asBstr, aad = ByteArray(0))
    }
}

/**
 * A well-formed `org-iso-mdoc` request, exactly as iOS's ISO 18013 scene delivers one.
 *
 * `encryptionInfo` is `["dcapi", {"recipientPublicKey": <COSE key>}]`; multipaz reads the key out of it
 * and HPKE-encrypts the response to it, so the key has to be real. The device request carries no reader
 * authentication — the ordinary case for the dev verifiers, and what the wallet must still answer.
 *
 * [elements] maps element name to `intentToRetain`, and its keys are what the verifier is asking for.
 */
suspend fun buildDcApiProbeRequest(
    docType: String = MDOC_PID_DOC_TYPE,
    elements: Map<String, Boolean> = mapOf("family_name" to false, "given_name" to false),
): DcApiProbeRequest {
    val deviceRequest = DeviceRequestGenerator(encodedSessionTranscript = Cbor.encode(Simple.NULL))
        .addDocumentRequest(
            docType = docType,
            itemsToRequest = mapOf(docType to elements),
            requestInfo = null,
            readerKey = null,
            signatureAlgorithm = Algorithm.UNSET,
            readerKeyCertificateChain = null,
        )
        .generate()

    val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
    val encryptionInfoBase64 = Cbor.encode(
        buildCborArray {
            add("dcapi")
            add(
                buildCborMap {
                    put("recipientPublicKey", recipientKey.publicKey.toCoseKey().toDataItem())
                }
            )
        }
    ).toBase64Url()

    return DcApiProbeRequest(
        json = """{"deviceRequest":"${deviceRequest.toBase64Url()}",""" +
            """"encryptionInfo":"$encryptionInfoBase64"}""",
        recipientKey = recipientKey,
        encryptionInfoBase64 = encryptionInfoBase64,
    )
}

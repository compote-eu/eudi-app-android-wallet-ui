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

// The bound on how deep CBOR from outside may nest, with the official iOS wallet's own cases
// (eudi-lib-ios-iso18013-data-model PR #154): multipaz's decoder overflows the stack on deep nesting, and
// Kotlin/Native cannot catch that, so every case here is decided without decoding.
package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.multipaz.harness.issuerNamespacesOf
import eu.europa.ec.shared.wallet.multipaz.harness.issuerSignedDataFor
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class CborDepthTest {

    private fun nested(levels: Int, opener: Int = 0x81) = ByteArray(levels + 1) { if (it < levels) opener.toByte() else 0 }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun assertRefused(cbor: ByteArray) {
        assertFailsWith<IllegalArgumentException> { cbor.requireCborWithinDepth() }
    }

    @Test
    fun nesting_up_to_the_bound_is_accepted() {
        nested(CBOR_MAXIMUM_DEPTH).requireCborWithinDepth()
        nested(CBOR_MAXIMUM_DEPTH, opener = 0xc0).requireCborWithinDepth()
    }

    @Test
    fun nesting_past_the_bound_is_refused_arrays_and_tags_alike() {
        assertRefused(nested(CBOR_MAXIMUM_DEPTH + 1))
        assertRefused(nested(CBOR_MAXIMUM_DEPTH + 2, opener = 0xc0))
    }

    @Test
    fun the_nesting_that_crashed_the_app_is_refused() {
        // 10 KB: multipaz's decoder overflows the stack on it.
        assertRefused(nested(10_000))
    }

    @Test
    fun a_byte_string_holding_cbor_is_bounded_the_same_way() {
        assertRefused(Cbor.encode(Bstr(nested(CBOR_MAXIMUM_DEPTH + 2))))
    }

    @Test
    fun a_declared_length_longer_than_the_bytes_left_is_refused() {
        assertRefused(bytes(0x9b, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff))
        assertRefused(bytes(0xbb, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff))
        assertRefused(bytes(0x5a, 0x00, 0x00, 0x10, 0x00, 0x01))
    }

    @Test
    fun truncated_or_trailing_bytes_are_refused() {
        assertRefused(bytes(0x9f, 0x81))
        assertRefused(bytes(0x82, 0x00))
        assertRefused(bytes(0x00, 0x00))
        assertRefused(ByteArray(0))
    }

    @Test
    fun ordinary_items_are_accepted() {
        bytes(0x82, 0x00, 0x01).requireCborWithinDepth()
        bytes(0x9f, 0x00, 0x01, 0xff).requireCborWithinDepth()
        // An indefinite text string, in chunks.
        bytes(0x7f, 0x61, 0x61, 0xff).requireCborWithinDepth()
        // A tag number too large for an Int is still a tag.
        bytes(0xdb, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00).requireCborWithinDepth()
    }

    @Test
    fun a_byte_string_that_is_not_cbor_is_left_as_bytes() {
        // The start of a JPEG, and a byte string that would be a truncated array: neither is looked into further.
        Cbor.encode(Bstr(bytes(0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10))).requireCborWithinDepth()
        Cbor.encode(Bstr(bytes(0x9f, 0x81))).requireCborWithinDepth()
    }

    @Test
    fun an_issuer_signed_mdoc_is_accepted() = runTest {
        val docType = "eu.europa.ec.eudi.pid.1"
        val issuerSigned = issuerSignedDataFor(
            docType = docType,
            issuerNamespaces = issuerNamespacesOf(docType, samplePidElements(), Random.Default),
            deviceKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        )

        issuerSigned.toByteArray().requireCborWithinDepth()
    }

    @Test
    fun a_readers_request_is_accepted() = runTest {
        val deviceRequest = DeviceRequestGenerator(encodedSessionTranscript = Cbor.encode(buildCborArray { add(0) }))
            .addDocumentRequest(
                docType = "eu.europa.ec.eudi.pid.1",
                itemsToRequest = mapOf("eu.europa.ec.eudi.pid.1" to mapOf("given_name" to false, "portrait" to true)),
                requestInfo = null,
                readerKey = null,
                signatureAlgorithm = Algorithm.UNSET,
                readerKeyCertificateChain = null,
            )
            .generate()

        deviceRequest.requireCborWithinDepth()
    }

    @Test
    fun a_dc_api_request_nested_past_the_bound_is_refused_before_it_is_decoded() = runTest {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(storage = storage, secureAreas = listOf(SoftwareSecureArea.create(storage)))
        val source = walletPresentmentSource(
            store = store,
            credentialDomain = store.documentManagerId,
            readerTrust = null,
            offersSdJwt = false,
        ) { _, _, _ -> null }
        val encryptionInfo = Cbor.encode(
            buildCborArray {
                add("dcapi")
                addCborMap {
                    put("nonce", Bstr(Random.nextBytes(16)))
                    put("recipientPublicKey", Crypto.createEcPrivateKey(EcCurve.P256).publicKey.toCoseKey().toDataItem())
                }
            }
        )
        val deep = nested(10_000).toBase64Url()

        for ((deviceRequest, info) in listOf(deep to encryptionInfo.toBase64Url(), "" to deep)) {
            val refused = assertFailsWith<IllegalArgumentException> {
                iosDigitalCredentialsPresentment(
                    protocol = "org-iso-mdoc",
                    data = buildJsonObject {
                        put("deviceRequest", deviceRequest)
                        put("encryptionInfo", info)
                    }.toString(),
                    appId = null,
                    origin = "https://verifier.example",
                    source = source,
                )
            }
            assertTrue("nested deeper" in refused.message.orEmpty(), "unexpected: ${refused.message}")
        }
    }
}

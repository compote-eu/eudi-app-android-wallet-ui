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

// A reader's registration certificate in an ISO 18013-5 request, read as Android's DeviceRequestProcessor
// reads it: from every ItemsRequest's `requestInfo`, identical copies collapsing, two different ones failing.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Algorithm
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class IosReaderRegistrationTest {

    private val pid = "eu.europa.ec.eudi.pid.1"
    private val mdl = "org.iso.18013.5.1.mDL"
    private val jwt = "eyJhbGciOiJFUzI1NiJ9.eyJuYW1lIjoiUmVhZGVyIn0.c2lnbmF0dXJl"

    /** One ItemsRequest per entry of [certificates], each carrying its certificate (or none) as `euWrprc`. */
    private suspend fun request(vararg certificates: ByteArray?): DeviceRequest {
        val generator = DeviceRequestGenerator(encodedSessionTranscript = Cbor.encode(Simple.NULL))
        certificates.forEachIndexed { index, certificate ->
            val docType = if (index == 0) pid else mdl
            generator.addDocumentRequest(
                docType = docType,
                itemsToRequest = mapOf(docType to mapOf("family_name" to false)),
                requestInfo = certificate?.let { mapOf(EU_WRPRC_REQUEST_INFO_KEY to Cbor.encode(Bstr(it))) },
                readerKey = null,
                signatureAlgorithm = Algorithm.UNSET,
                readerKeyCertificateChain = null,
            )
        }
        return DeviceRequest.fromDataItem(Cbor.decode(generator.generate()))
    }

    @Test
    fun a_request_without_one_carries_no_certificate() = runTest {
        // Every reader today: no EU verifier app sends `euWrprc`.
        assertEquals(ReaderRegistrationCertificate.Absent, request(null).readerRegistrationCertificate())
    }

    @Test
    fun the_copy_repeated_in_every_items_request_is_one_certificate() = runTest {
        val certificate = request(jwt.encodeToByteArray(), jwt.encodeToByteArray()).readerRegistrationCertificate()

        assertEquals(ReaderRegistrationCertificate.Jwt(jwt), certificate)
    }

    @Test
    fun two_different_certificates_fail_the_request_with_androids_words() = runTest {
        val request = request(jwt.encodeToByteArray(), "eyJ.b.c".encodeToByteArray())

        val failure = assertFailsWith<IllegalArgumentException> { request.readerRegistrationCertificate() }
        assertEquals("Device Request carries multiple relying party registration certificates", failure.message)
    }

    @Test
    fun anything_that_is_not_a_compact_jws_is_taken_for_a_cwt() = runTest {
        // A COSE_Sign1 starts with a CBOR array header, never with base64url text.
        val certificate = request(byteArrayOf(0x84.toByte(), 0x43, 0xa1.toByte(), 0x01, 0x26)).readerRegistrationCertificate()

        assertEquals(ReaderRegistrationCertificate.Cwt, certificate)
    }

    @Test
    fun every_requested_element_is_keyed_by_its_namespace_and_document_type() = runTest {
        assertEquals(
            listOf(
                OverAskedClaim(format = "mso_mdoc", path = listOf(pid, "family_name"), doctype = pid),
                OverAskedClaim(format = "mso_mdoc", path = listOf(mdl, "family_name"), doctype = mdl),
            ),
            request(null, null).requestedClaims(),
        )
    }

    @Test
    fun an_absent_certificate_is_a_failed_registration_and_a_cwt_an_unverified_one() = runTest {
        val validator = IosRelyingPartyRegistrationValidator(isChainTrusted = { true }, checkRevocation = { error("not asked") })

        val absent = validator.evaluateReader(ReaderRegistrationCertificate.Absent, emptyList(), reader = null)
        assertEquals(IssuerRegistrationFailure.CERTIFICATE_ABSENT, assertIs<RelyingPartyRegistrationOutcome.Failed>(absent).reason)

        val cwt = validator.evaluateReader(ReaderRegistrationCertificate.Cwt, emptyList(), reader = null)
        assertEquals(IssuerRegistrationFailure.MALFORMED, assertIs<RelyingPartyRegistrationOutcome.Failed>(cwt).reason)
    }
}

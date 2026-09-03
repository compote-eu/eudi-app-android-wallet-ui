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

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import eu.europa.ec.shared.wallet.multipaz.harness.sampleIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.Hpke
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The Digital Credentials API responder, against a real `DocumentStore`.
 *
 * ## What this covers, and what it deliberately does not
 *
 * It covers **our wiring**: that the request reaches multipaz, that each of its outcomes is mapped to
 * the right [IosDcApiOutcome], and that consent is not invoked for a request that never parses — which
 * is the property that matters most here, because reaching consent is what releases documents.
 *
 * It also covers a **full exchange**: a well-formed `org-iso-mdoc` request built the way iOS's
 * ISO 18013 scene delivers one — `{"deviceRequest": <base64url CBOR>, "encryptionInfo": <base64url
 * CBOR>}`, the latter `["dcapi", {"recipientPublicKey": <COSE key>}]` — carried through consent to an
 * HPKE-encrypted response. The success case asserts the response really is one: `protocol` comes back
 * as `org-iso-mdoc` and `data.response` is a substantial ciphertext, not an empty envelope.
 *
 * ⚠️ **This is the only coverage that branch has anywhere.** multipaz's own
 * `digitalCredentialsPresentmentTest` exercises `openid4vp`, `openid4vp-v1-signed` and
 * `openid4vp-v1-unsigned` only — while multipaz's own iOS registration advertises **`org-iso-mdoc`**,
 * which is what iOS actually drives. Treat these tests as load-bearing rather than as a formality, and
 * do not assume the branch beneath them is exercised elsewhere.
 *
 * What is still unproven is everything *outside* Kotlin: that iOS routes a real request here at all,
 * and that a verifier accepts the response. Both need the entitlement and a device.
 */
class IosDcApiPresenterTest {

    private val verifierOrigin = "https://verifier.example"

    private suspend fun store(
        storage: Storage = EphemeralStorage(),
    ): MultipazWalletStore = MultipazWalletStore.build(
        storage = storage,
        secureAreas = listOf(SoftwareSecureArea.create(storage)),
        documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
    )

    private suspend fun MultipazWalletStore.seedPid(): String = seedMdocDocument(
        docType = MDOC_PID_DOC_TYPE,
        displayName = "PID MSO MDoc",
        namespace = MDOC_PID_DOC_TYPE,
        elements = samplePidElements(givenName = "Tester"),
        policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 3),
        issuerMetadata = sampleIssuerMetadata(MDOC_PID_DOC_TYPE),
        validFrom = Clock.System.now() - 1.days,
        validUntil = Clock.System.now() + 30.days,
    )

    /** Fails the test if consent is reached; every case here should end before it. */
    private val refuseToBeAsked: suspend (
        org.multipaz.request.Requester,
        org.multipaz.trustmanagement.TrustMetadata?,
        org.multipaz.presentment.CredentialPresentmentData,
    ) -> org.multipaz.presentment.CredentialPresentmentSelection? = { _, _, _ ->
        error("consent must not be reached for a request that cannot be answered")
    }

    @Test
    fun an_unsupported_protocol_is_reported_as_a_failure() = runTest {
        val store = store()
        store.seedPid()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "not-a-protocol",
            data = "{}",
            origin = "https://verifier.example",
            onConsent = refuseToBeAsked,
        )

        assertIs<IosDcApiOutcome.Failed>(outcome)
    }

    /**
     * The `org-iso-mdoc` branch reads `deviceRequest` and `encryptionInfo` with `!!`, so an object
     * without them throws before anything is selected. What matters is that the throw becomes a
     * [IosDcApiOutcome.Failed] rather than escaping into the extension, where it would be an OS-level
     * crash of a process the user did not launch.
     */
    @Test
    fun a_request_missing_its_fields_fails_rather_than_throwing() = runTest {
        val store = store()
        store.seedPid()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "org-iso-mdoc",
            data = """{"somethingElse": true}""",
            origin = "https://verifier.example",
            onConsent = refuseToBeAsked,
        )

        assertIs<IosDcApiOutcome.Failed>(outcome)
    }

    @Test
    fun a_request_whose_encryption_info_is_not_dcapi_fails() = runTest {
        val store = store()
        store.seedPid()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "org-iso-mdoc",
            // Well-formed base64url of CBOR that is simply not what the branch expects.
            data = """{"deviceRequest": "oA", "encryptionInfo": "oA"}""",
            origin = "https://verifier.example",
            onConsent = refuseToBeAsked,
        )

        assertIs<IosDcApiOutcome.Failed>(outcome)
    }

    /**
     * A failure message is shown to a person, so it must never be Kotlin's bare `check(...)` string or
     * an empty one. The same rule [IosRemotePresenter] applies, for the same reason.
     */
    @Test
    fun a_failure_always_carries_a_message_worth_showing() = runTest {
        val store = store()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "not-a-protocol",
            data = "{}",
            origin = "https://verifier.example",
            onConsent = refuseToBeAsked,
        )

        val message = assertIs<IosDcApiOutcome.Failed>(outcome).message
        assertTrue(message.isNotBlank())
        assertFalse(message == "Check failed.")
    }

    /**
     * An empty wallet must not reach consent either: there is nothing to consent to, and asking would
     * put a dialog in front of a user for a request that could never be answered.
     */
    @Test
    fun an_empty_wallet_never_reaches_consent() = runTest {
        var asked = false

        val outcome = IosDcApiPresenter(store()).present(
            protocol = "org-iso-mdoc",
            data = """{"deviceRequest": "oA", "encryptionInfo": "oA"}""",
            origin = "https://verifier.example",
            onConsent = { _, _, _ -> asked = true; null },
        )

        assertFalse(asked)
        assertIs<IosDcApiOutcome.Failed>(outcome)
    }

    /** The presenter offers only the wallet's own credential domain; the default is the store's. */
    @Test
    fun the_credential_domain_defaults_to_the_document_manager_id() = runTest {
        val store = store()
        store.seedPid()

        // Constructing with the default must behave as constructing with the id explicitly: the
        // assertion is that neither throws and both map the same request the same way.
        val withDefault = IosDcApiPresenter(store)
            .present("not-a-protocol", "{}", "https://v.example", onConsent = refuseToBeAsked)
        val withExplicit = IosDcApiPresenter(store, MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID)
            .present("not-a-protocol", "{}", "https://v.example", onConsent = refuseToBeAsked)

        assertEquals(
            assertIs<IosDcApiOutcome.Failed>(withDefault).message,
            assertIs<IosDcApiOutcome.Failed>(withExplicit).message,
        )
    }

    //region a full exchange — the branch nothing upstream covers

    /**
     * A well-formed `org-iso-mdoc` request, exactly as iOS's ISO 18013 scene delivers one.
     *
     * `encryptionInfo` is `["dcapi", {"recipientPublicKey": <COSE key>}]`; multipaz reads the key out of
     * it and HPKE-encrypts the response to it, so the key has to be real even though this test never
     * decrypts. The device request itself carries no reader authentication — the ordinary case for the
     * dev verifiers, and what the wallet must still answer.
     */
    /** A request plus the two things a test needs to decrypt what comes back. */
    private data class BuiltRequest(
        val json: String,
        val recipientKey: EcPrivateKey,
        val encryptionInfoBase64: String,
    )

    private suspend fun mdocApiRequest(
        docType: String = MDOC_PID_DOC_TYPE,
        elements: Map<String, Boolean> = mapOf("family_name" to false, "given_name" to false),
    ): String = buildRequest(docType, elements).json

    private suspend fun buildRequest(
        docType: String = MDOC_PID_DOC_TYPE,
        elements: Map<String, Boolean> = mapOf("family_name" to false, "given_name" to false),
    ): BuiltRequest {
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
        val recipient = buildCborMap {
            put("recipientPublicKey", recipientKey.publicKey.toCoseKey().toDataItem())
        }
        val encryptionInfo = Cbor.encode(
            buildCborArray {
                add("dcapi")
                add(recipient)
            }
        )
        val encryptionInfoBase64 = encryptionInfo.toBase64Url()

        return BuiltRequest(
            json = """{"deviceRequest":"${deviceRequest.toBase64Url()}",""" +
                """"encryptionInfo":"$encryptionInfoBase64"}""",
            recipientKey = recipientKey,
            encryptionInfoBase64 = encryptionInfoBase64,
        )
    }

    /** Everything the request matched — what a user who unchecks nothing agrees to. */
    private val acceptEverything: suspend (
        org.multipaz.request.Requester,
        org.multipaz.trustmanagement.TrustMetadata?,
        org.multipaz.presentment.CredentialPresentmentData,
    ) -> CredentialPresentmentSelection? = { _, _, data ->
        CredentialPresentmentSelection(
            matches = data.credentialSets
                .flatMap { it.options }
                .flatMap { it.members }
                .mapNotNull { it.matches.firstOrNull() },
        )
    }

    @Test
    fun a_matching_request_is_answered_and_names_what_was_shared() = runTest {
        val store = store()
        store.seedPid()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "org-iso-mdoc",
            data = mdocApiRequest(),
            origin = "https://verifier.example",
            onConsent = acceptEverything,
        )

        val sent = assertIs<IosDcApiOutcome.Sent>(outcome)
        assertEquals(listOf("PID MSO MDoc"), sent.sharedDocuments)
        // The DC API result is a JSON object carrying `protocol` and `data`; `data` holds the
        // HPKE-encrypted response, so a non-trivial value there is what says a response was actually
        // built rather than an empty envelope returned.
        val result = Json.parseToJsonElement(sent.responseJson).jsonObject
        assertEquals("org-iso-mdoc", result["protocol"]?.jsonPrimitive?.content)
        val encrypted = assertNotNull(result["data"]).jsonObject["response"]?.jsonPrimitive?.content
        assertTrue(assertNotNull(encrypted).length > 100, "encrypted response looks empty")
    }

    /** Declining releases nothing, and is an answer rather than a failure. */
    @Test
    fun declining_a_matching_request_shares_nothing() = runTest {
        val store = store()
        store.seedPid()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "org-iso-mdoc",
            data = mdocApiRequest(),
            origin = "https://verifier.example",
            onConsent = { _, _, _ -> null },
        )

        assertIs<IosDcApiOutcome.Declined>(outcome)
    }

    /** A doctype the wallet does not hold is "nothing to share", not an error. */
    @Test
    fun a_request_for_a_doctype_the_wallet_lacks_shares_nothing() = runTest {
        val store = store()
        store.seedPid()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "org-iso-mdoc",
            data = mdocApiRequest(docType = "org.iso.18013.5.1.mDL"),
            origin = "https://verifier.example",
            onConsent = acceptEverything,
        )

        assertIs<IosDcApiOutcome.NothingToShare>(outcome)
    }


    /**
     * Decrypts what the wallet sent back, so the response can be inspected rather than counted.
     *
     * Rebuilds the session transcript exactly as multipaz does — `["dcapi", sha256(["encryptionInfo",
     * origin])]` under two nulls — because HPKE binds it as `info`, so getting it wrong fails to
     * decrypt rather than yielding wrong plaintext. That is also what makes this a real check of the
     * transcript, not only of the claims.
     */
    private suspend fun decryptResponse(
        responseJson: String,
        request: BuiltRequest,
        origin: String,
    ): ByteArray {
        val response = Json.parseToJsonElement(responseJson).jsonObject
        val encoded = assertNotNull(response["data"]).jsonObject["response"]!!.jsonPrimitive.content
        val envelope = Cbor.decode(encoded.fromBase64Url())
        assertEquals("dcapi", envelope.asArray[0].asTstr, "not a dcapi response envelope")
        val parts = envelope.asArray[1].asMap

        val dcapiInfo = buildCborArray {
            add(request.encryptionInfoBase64)
            add(origin)
        }
        val digest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
        val handover = buildCborArray {
            add("dcapi")
            add(digest)
        }
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(handover)
        }

        return Hpke.getDecrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = AsymmetricKey.AnonymousExplicit(privateKey = request.recipientKey),
            encapsulatedKey = parts[Tstr("enc")]!!.asBstr,
            info = Cbor.encode(sessionTranscript),
        ).decrypt(
            ciphertext = parts[Tstr("cipherText")]!!.asBstr,
            aad = ByteArray(0),
        )
    }

    /**
     * **Selective disclosure, proven rather than assumed.**
     *
     * The verifier asks for two elements and the user keeps one. Every earlier test could only show
     * that *a* response came back; this decrypts it and reads what is inside, which is the only way to
     * tell "the wallet honoured the selection" from "the wallet sent everything and the UI hid the
     * rest". The second would be a privacy failure invisible to every other assertion here.
     *
     * Decryption succeeding is itself a second result: HPKE binds the session transcript as `info`, so
     * a transcript built differently from multipaz's would fail to decrypt rather than mislead.
     */
    @Test
    fun the_response_carries_only_the_claims_the_user_kept() = runTest {
        val store = store()
        store.seedPid()
        val request = buildRequest()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "org-iso-mdoc",
            data = request.json,
            origin = verifierOrigin,
            onConsent = { _, _, data ->
                CredentialPresentmentSelection(
                    matches = data.credentialSets
                        .flatMap { it.options }
                        .flatMap { it.members }
                        .mapNotNull { member ->
                            val match = member.matches.firstOrNull() ?: return@mapNotNull null
                            val kept = match.claims.filterKeys {
                                it is MdocRequestedClaim && it.dataElementName == "family_name"
                            }
                            if (kept.isEmpty()) null else match.copy(claims = kept)
                        },
                )
            },
        )

        val sent = assertIs<IosDcApiOutcome.Sent>(outcome)
        val plaintext = decryptResponse(sent.responseJson, request, verifierOrigin).decodeToString(
            throwOnInvalidSequence = false,
        )

        assertTrue("family_name" in plaintext, "the kept claim is missing from the response")
        assertFalse("given_name" in plaintext, "a claim the user dropped was sent anyway")
    }

    //endregion
}

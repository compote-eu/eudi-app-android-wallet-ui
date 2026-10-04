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

// multipaz 0.101's ISO mdoc presentment — the consent step `mdocPresentmentObtainConsent`, the `Iso18013Presentment`
// loop and the `org-iso-mdoc` branch of `digitalCredentialsPresentment` — copied with ONE change: how a reader's
// request is matched.
//
// multipaz drops a credential that lacks any one requested element (`DeviceRequest.findBestMatchingClaims`),
// so a reader asking for 29 PID elements of which the PID holds 26 got "nothing to share". Android matches
// softly instead: wallet-core's `DeviceRequestProcessor` offers a credential that holds at least one of the
// requested elements and leaves the rest out, which is ISO 18013-5's partial response. The official iOS wallet
// answers the same way. The strict check runs inside `DeviceRequest.execute`, which the consent step calls and
// the other two reach through it, so there was no hook short of copying them. The response itself is built by
// multipaz's public `mdocPresentmentGenerateResponse`, which takes the selection, so that part is not copied.
//
// Everything else is multipaz's code, trimmed of what this wallet never supplies: preselected documents,
// the focus and waiting callbacks, and the loop's NFC paths — waiting for a re-tap, re-deriving the session
// after one, and re-using the consent given before it. This wallet engages over a QR code and Bluetooth only,
// so each request is asked about, as it was before 0.101.
//
// ⛔ Delete this file once multipaz matches partially, or once a multipaz upgrade changes the functions it
// copies — re-copy rather than patch, and keep the one change.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.claim.Claim
import org.multipaz.claim.findMatchingClaim
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.Hpke
import org.multipaz.eventlogger.EventPresentmentDigitalCredentialsMdocApi
import org.multipaz.eventlogger.EventPresentmentIso18013Proximity
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DocRequest
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.EReaderKey
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.presentment.CredentialMatchSourceIso18013
import org.multipaz.presentment.ConsentData
import org.multipaz.presentment.CredentialPresentmentSet
import org.multipaz.presentment.CredentialPresentmentSetOption
import org.multipaz.presentment.CredentialPresentmentSetOptionMember
import org.multipaz.presentment.CredentialPresentmentSetOptionMemberMatch
import org.multipaz.presentment.CredentialQueryResult
import org.multipaz.presentment.CredentialSelection
import org.multipaz.presentment.Iso18013PresentmentTimeoutException
import org.multipaz.presentment.Iso18013Response
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.digitalCredentialsPresentment
import org.multipaz.presentment.mdocPresentmentGenerateResponse
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Matches the reader's request the way Android's wallet does: a credential is offered when it holds **at
 * least one** requested element, and the elements it lacks are left out of the match, so they are neither
 * shown nor sent.
 *
 * A port of wallet-core's `DocRequest.toCredentialPresentmentSet`, with two things kept from multipaz:
 * candidates are listed and ordered as `DeviceRequest.execute` lists them, and the credential itself comes
 * from [PresentmentSource.selectCredential], which is what confines an offer to the wallet's own domain.
 * Like Android, every document request gets a set of its own — multipaz reads only the first of an
 * ISO 18013-5:2021 request — and transaction data and alternative elements are not considered.
 *
 * @throws PresentmentCannotSatisfyRequestException when no credential holds any requested element.
 */
internal suspend fun DeviceRequest.matchHeldElements(
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
): CredentialQueryResult {
    val credentialSets = docRequests.mapNotNull { it.matchHeldElements(source, keyAgreementPossible) }
    if (credentialSets.isEmpty()) {
        throw PresentmentCannotSatisfyRequestException(
            "No credential holds any element the reader asked for",
            Iso18015ResponseException("No matching credentials"),
        )
    }
    return CredentialQueryResult(credentialSets)
}

private suspend fun DocRequest.matchHeldElements(
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
): CredentialPresentmentSet? {
    val requestedClaims = nameSpaces.flatMap { (namespace, elements) ->
        elements.map { (element, intentToRetain) ->
            MdocRequestedClaim(
                docType = docType,
                namespaceName = namespace,
                dataElementName = element,
                intentToRetain = intentToRetain,
            )
        }
    }

    val matches = candidatesFor(docType, source).mapNotNull { candidate ->
        val held = candidate.getClaims(documentTypeRepository = source.documentTypeRepository)
        val matched: Map<RequestedClaim, Claim> = requestedClaims
            .mapNotNull { requested -> held.findMatchingClaim(requested)?.let { requested to it } }
            .toMap()
        if (matched.isEmpty()) return@mapNotNull null

        val credential = source.selectCredential(
            document = candidate.document,
            requestedClaims = matched.keys.toList(),
            keyAgreementPossible = keyAgreementPossible,
        ) as? MdocCredential ?: return@mapNotNull null

        CredentialPresentmentSetOptionMemberMatch(
            credential = credential,
            claims = matched,
            source = CredentialMatchSourceIso18013(docRequest = this),
            transactionData = emptyList(),
        )
    }
    if (matches.isEmpty()) return null

    return CredentialPresentmentSet(
        optional = false,
        options = listOf(
            CredentialPresentmentSetOption(
                members = listOf(CredentialPresentmentSetOptionMember(matches = matches)),
            )
        ),
    )
}

/** One mdoc credential per document of [docType], in `DeviceRequest.execute`'s order. */
private suspend fun candidatesFor(docType: String, source: PresentmentSource): List<MdocCredential> =
    source.documentStore.listDocumentIds()
        .mapNotNull { id ->
            source.documentStore.lookupDocument(id)?.getCertifiedCredentials()
                ?.firstOrNull { it is MdocCredential && it.docType == docType } as? MdocCredential
        }
        .sortedBy { it.document.displayName }

/**
 * multipaz's `mdocPresentment`: consent asked with [iosMdocPresentmentObtainConsent], the response built by
 * multipaz's own `mdocPresentmentGenerateResponse`.
 *
 * @throws PresentmentCanceledException if the user declined.
 * @throws PresentmentCannotSatisfyRequestException if no credential holds anything the reader asked for.
 */
internal suspend fun iosMdocPresentment(
    deviceRequest: DeviceRequest,
    eReaderKey: EcPublicKey?,
    sessionTranscript: DataItem,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
    requesterAppId: String?,
    requesterOrigin: String?,
): Iso18013Response {
    val selection = iosMdocPresentmentObtainConsent(
        deviceRequest = deviceRequest,
        source = source,
        keyAgreementPossible = keyAgreementPossible,
        requesterAppId = requesterAppId,
        requesterOrigin = requesterOrigin,
    )
    return mdocPresentmentGenerateResponse(
        selection = selection,
        deviceRequest = deviceRequest,
        eReaderKey = eReaderKey,
        sessionTranscript = sessionTranscript,
        source = source,
        requesterAppId = requesterAppId,
        requesterOrigin = requesterOrigin,
    )
}

/**
 * multipaz's `mdocPresentmentObtainConsent`, matching with [matchHeldElements].
 *
 * @throws PresentmentCanceledException if the user declined.
 * @throws PresentmentCannotSatisfyRequestException if no credential holds anything the reader asked for.
 */
private suspend fun iosMdocPresentmentObtainConsent(
    deviceRequest: DeviceRequest,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
    requesterAppId: String?,
    requesterOrigin: String?,
): CredentialSelection {
    val credentialQueryResult = deviceRequest.matchHeldElements(source, keyAgreementPossible)
    val requester = Requester(
        requesterIdentities = deviceRequest.getRequesterIdentities(),
        appId = requesterAppId,
        origin = requesterOrigin,
    )
    return source.showConsentPrompt(
        requester = requester,
        trustedRequesterIdentity = source.resolveTrust(requester),
        consentData = ConsentData.fromCredentialQueryResult(
            credentialQueryResult = credentialQueryResult,
            source = source,
        ),
        preselectedDocuments = emptyList(),
        onDocumentsInFocus = {},
    ) ?: throw PresentmentCanceledException("User canceled consent prompt")
}

/**
 * multipaz's `Iso18013Presentment`, answering each request with [iosMdocPresentment]. It serves requests
 * until the reader closes the connection.
 *
 * One change beyond the matching: sending is bounded by [sendTimeout] (see [sendResponse]), because a
 * reader that goes away mid-response leaves multipaz's iOS BLE send waiting for ever.
 *
 * @throws Iso18013PresentmentTimeoutException if the reader sends nothing within [timeout], or the response
 *   is not taken within [sendTimeout].
 * @throws PresentmentCanceledException if the user declined.
 * @throws PresentmentCannotSatisfyRequestException if no credential holds anything the reader asked for.
 */
internal suspend fun iosIso18013Presentment(
    transport: MdocTransport,
    eDeviceKey: EcPrivateKey,
    deviceEngagement: DataItem,
    handover: DataItem,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
    timeout: Duration? = 15.seconds,
    timeoutSubsequentRequests: Duration? = 30.seconds,
    sendTimeout: Duration = 30.seconds,
    onSendingResponse: () -> Unit = {},
    /**
     * Each request as it arrives, after its reader authentication has been checked and before consent is
     * asked — what the consent step reads the reader's registration certificate from, since multipaz's
     * consent callback is handed only the matches.
     */
    onDeviceRequest: (DeviceRequest) -> Unit = {},
) {
    // Wait until state changes to CONNECTED, FAILED, or CLOSED
    transport.state.first {
        it == MdocTransport.State.CONNECTED ||
            it == MdocTransport.State.FAILED ||
            it == MdocTransport.State.CLOSED
    }
    if (transport.state.value != MdocTransport.State.CONNECTED) {
        throw IllegalStateException("Expected state CONNECTED but found ${transport.state.value}")
    }
    var numRequestsServed = 0
    var sendSessionTermination = true
    try {
        var sessionEncryption: SessionEncryption? = null
        lateinit var eReaderKey: EReaderKey
        lateinit var sessionTranscript: DataItem
        while (true) {
            Logger.i(TAG, "Waiting for message from reader...")
            val timeoutToUse = if (numRequestsServed == 0) timeout else timeoutSubsequentRequests
            val sessionData = if (timeoutToUse == null) {
                transport.waitForMessage()
            } else {
                try {
                    withTimeout(timeoutToUse) {
                        transport.waitForMessage()
                    }
                } catch (e: TimeoutCancellationException) {
                    throw Iso18013PresentmentTimeoutException("Timed out waiting for message from remote reader", e)
                }
            }
            if (sessionData.isEmpty()) {
                Logger.i(TAG, "Received transport-specific session termination message from reader")
                sendSessionTermination = false
                break
            }

            if (sessionEncryption == null) {
                eReaderKey = SessionEncryption.getEReaderKey(sessionData)
                sessionTranscript = buildCborArray {
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(deviceEngagement))))
                    add(Tagged(Tagged.ENCODED_CBOR, Bstr(eReaderKey.encodedCoseKey)))
                    add(handover)
                }
                sessionEncryption = SessionEncryption(
                    MdocRole.MDOC,
                    eDeviceKey,
                    eReaderKey.publicKey,
                    Cbor.encode(sessionTranscript),
                )
            }
            val (encodedDeviceRequest, status) = sessionEncryption.decryptMessage(sessionData)

            if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                Logger.i(TAG, "Received session termination message from reader")
                sendSessionTermination = false
                break
            }

            if (encodedDeviceRequest == null) {
                throw IllegalStateException("No data in message from reader")
            }

            val deviceRequest = DeviceRequest.fromDataItem(Cbor.decode(encodedDeviceRequest))
            deviceRequest.verifyReaderAuthentication(sessionTranscript)
            onDeviceRequest(deviceRequest)
            val responseObject = iosMdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = eReaderKey.publicKey,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = keyAgreementPossible,
                requesterAppId = null,
                requesterOrigin = null,
            )
            onSendingResponse()
            sendResponse(
                transport = transport,
                message = sessionEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(responseObject.deviceResponse.toDataItem()),
                    statusCode = null,
                ),
                timeout = sendTimeout,
            )
            numRequestsServed += 1

            source.eventLogger?.addEventAsync(
                EventPresentmentIso18013Proximity(
                    presentmentData = responseObject.eventData,
                    request = deviceRequest.toDataItem(),
                    response = responseObject.deviceResponse.toDataItem(),
                    sessionTranscript = sessionTranscript,
                )
            )

            Logger.i(TAG, "Response sent, keeping connection open")
        }
    } finally {
        if (sendSessionTermination) {
            terminateSession(transport)
        }
        Logger.i(TAG, "Closing transport")
        transport.close()
    }
}

/**
 * Sends [message], or fails if the reader has not taken it within [timeout].
 *
 * multipaz's iOS BLE peripheral cannot tell that the reader went away. CoreBluetooth reports a central's
 * departure only through `didUnsubscribeFrom`, which multipaz does not handle, in 0.101 as in 0.99. Neither
 * does it read the reader's "end" on the State characteristic while it is sending. So a reader that leaves
 * mid-response leaves the send waiting for a "ready to write" that never comes, and the screen showing
 * "sharing" for ever. Ending the send here cancels it, and multipaz then fails the transport, so the
 * cleanup that follows cannot hang on it too.
 *
 * @throws Iso18013PresentmentTimeoutException if [timeout] passes first.
 */
internal suspend fun sendResponse(transport: MdocTransport, message: ByteArray, timeout: Duration) {
    withTimeoutOrNull(timeout) { transport.sendMessage(message) }
        ?: throw Iso18013PresentmentTimeoutException("The reader stopped taking the response.")
}

/**
 * The end of [iosIso18013Presentment]'s `finally`, apart so cancellation is rethrown outside it.
 *
 * Bounded by [timeout] for the same reason as [sendResponse]: a reader that is gone takes nothing, and a
 * cleanup that waits for it would keep the exchange from ending.
 */
internal suspend fun terminateSession(transport: MdocTransport, timeout: Duration = TERMINATION_TIMEOUT) {
    Logger.i(TAG, "Sending session-termination")
    try {
        withTimeoutOrNull(timeout) {
            transport.sendMessage(
                SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
            )
        } ?: Logger.w(TAG, "The reader did not take the session-termination; closing anyway")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "Caught error while sending session-termination", e)
    }
}

private val TERMINATION_TIMEOUT = 5.seconds

/**
 * multipaz's `digitalCredentialsPresentment` with its `org-iso-mdoc` branch answered by
 * [iosMdocPresentment]. OpenID4VP requests go to multipaz unchanged: DCQL says for itself which claims
 * are required.
 */
internal suspend fun iosDigitalCredentialsPresentment(
    protocol: String,
    data: String,
    appId: String?,
    origin: String,
    source: PresentmentSource,
    /** The `org-iso-mdoc` request once its reader authentication is checked; see [iosIso18013Presentment]. */
    onDeviceRequest: (DeviceRequest) -> Unit = {},
): String = when (protocol) {
    "org.iso.mdoc", "org-iso-mdoc" -> Json.encodeToString(
        mdocDcApiPresentment(
            protocol = protocol,
            data = Json.decodeFromString<JsonObject>(data),
            appId = appId,
            origin = origin,
            source = source,
            onDeviceRequest = onDeviceRequest,
        )
    )

    else -> digitalCredentialsPresentment(
        protocol = protocol,
        data = data,
        appId = appId,
        origin = origin,
        preselectedDocuments = emptyList(),
        source = source,
    )
}

/** multipaz's private `digitalCredentialsMdocApiProtocol`, answering with [iosMdocPresentment]. */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun mdocDcApiPresentment(
    protocol: String,
    data: JsonObject,
    appId: String?,
    origin: String,
    source: PresentmentSource,
    onDeviceRequest: (DeviceRequest) -> Unit,
): JsonObject {
    val deviceRequestBase64 = data["deviceRequest"]!!.jsonPrimitive.content
    val encryptionInfoBase64 = data["encryptionInfo"]!!.jsonPrimitive.content

    val encryptionInfo = Cbor.decode(encryptionInfoBase64.fromBase64Url())
    require(encryptionInfo.asArray[0].asTstr == "dcapi") { "Malformed EncryptionInfo" }
    val recipientPublicKey = encryptionInfo.asArray[1].asMap[Tstr("recipientPublicKey")]!!
        .asCoseKey.ecPublicKey

    val dcapiInfo = buildCborArray {
        add(encryptionInfoBase64)
        add(origin)
    }
    val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
    val sessionTranscript = buildCborArray {
        add(Simple.NULL) // DeviceEngagementBytes
        add(Simple.NULL) // EReaderKeyBytes
        addCborArray {
            add("dcapi")
            add(dcapiInfoDigest)
        }
    }

    val deviceRequest = DeviceRequest.fromDataItem(Cbor.decode(deviceRequestBase64.fromBase64Url()))
    deviceRequest.verifyReaderAuthentication(sessionTranscript)
    onDeviceRequest(deviceRequest)
    val responseObject = iosMdocPresentment(
        deviceRequest = deviceRequest,
        eReaderKey = null,
        sessionTranscript = sessionTranscript,
        source = source,
        keyAgreementPossible = emptyList(),
        requesterAppId = appId,
        requesterOrigin = origin,
    )

    val encrypter = Hpke.getEncrypter(
        cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
        receiverPublicKey = recipientPublicKey,
        info = Cbor.encode(sessionTranscript),
    )
    val ciphertext = encrypter.encrypt(
        plaintext = Cbor.encode(responseObject.deviceResponse.toDataItem()),
        aad = ByteArray(0),
    )
    val encryptedResponse = Cbor.encode(
        buildCborArray {
            add("dcapi")
            addCborMap {
                put("enc", encrypter.encapsulatedKey.toByteArray())
                put("cipherText", ciphertext)
            }
        }
    )

    val responseData = buildJsonObject {
        put("response", encryptedResponse.toBase64Url())
    }

    source.eventLogger?.addEventAsync(
        EventPresentmentDigitalCredentialsMdocApi(
            presentmentData = responseObject.eventData,
            appId = appId,
            origin = origin,
            protocol = protocol,
            requestJson = Json.encodeToString(data),
            responseJson = Json.encodeToString(responseData),
            deviceResponse = responseObject.deviceResponse.toDataItem(),
        )
    )

    return buildJsonObject {
        put("protocol", protocol)
        put("data", responseData)
    }
}

private const val TAG = "IosMdocPresentment"

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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.claim.Claim
import org.multipaz.claim.MdocClaim
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.engagement.EngagementGenerator
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.advertise
import org.multipaz.mdoc.transport.waitForConnection
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.CredentialPresentmentSetOption
import org.multipaz.presentment.CredentialPresentmentSetOptionMemberMatch
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toBase64Url
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** multipaz's name for "one credential that could answer one part of the request", used a lot below. */
private typealias MemberMatch = CredentialPresentmentSetOptionMemberMatch

/** Where a proximity presentation has got to, as the screens need to see it. */
sealed interface IosProximityState {

    data object Idle : IosProximityState

    /** Engagement is advertised; [qrPayload] is the `mdoc:` URI the reader scans. */
    data class Engaging(val qrPayload: String) : IosProximityState

    /** A reader has asked for something and the user has not answered yet. */
    data class Requesting(val request: IosProximityRequest) : IosProximityState

    data object Sending : IosProximityState

    /** The response went out. [sharedDocuments] names what was actually released. */
    data class Sent(val sharedDocuments: List<String>) : IosProximityState

    data class Failed(val message: String) : IosProximityState
}

/**
 * What a reader asked for, as a consent screen needs to show it.
 *
 * [combinations] are the *alternative* ways to satisfy the request — a different document for the same
 * requirement, or a different credential of the same document. Usually there is exactly one; a wallet
 * holding two PIDs when a reader asks for one produces two, and the screen lets the user pick.
 */
data class IosProximityRequest(
    /** The reader's name if its certificate is trusted, null when it is unknown — as it usually is. */
    val requesterName: String?,
    val requesterIsTrusted: Boolean,
    val combinations: List<Combination>,
) {
    data class Combination(val documents: List<RequestedDocument>)

    /**
     * [documentId] and [credentialId] together name the credential that would answer, and are what
     * [IosProximityDisclosure] hands back — so the app never has to hold a multipaz object to say yes.
     */
    data class RequestedDocument(
        val documentId: String,
        val credentialId: String,
        val documentName: String,
        val docType: String,
        val claims: List<RequestedClaimInfo>,
    )

    data class RequestedClaimInfo(
        val claim: IosProximityClaimRef,
        val displayName: String,
        /** The stored value, so consent shows what would actually leave the wallet. */
        val value: String,
        /** What the reader says it will *keep* — shown to the user, never a reason to force-share. */
        val intentToRetain: Boolean,
    )
}

/** One requested data element, named the way both sides of the consent step can agree on. */
data class IosProximityClaimRef(
    val namespace: String?,
    val identifier: String,
)

/**
 * What the user agreed to release: for one credential, the claims kept.
 *
 * Anything absent stays home — dropping a document from the list refuses it entirely, and dropping a
 * claim from [claims] narrows the response to the rest, because multipaz builds the mdoc response from
 * exactly the claims the selection carries.
 */
data class IosProximityDisclosure(
    val documentId: String,
    val credentialId: String,
    val claims: Set<IosProximityClaimRef>,
)

/**
 * ISO 18013-5 proximity presentation on iOS: QR engagement, BLE, and the mdoc response.
 *
 * multipaz owns the protocol — `Iso18013Presentment` runs the exchange and `SimplePresentmentSource`
 * matches the reader's request against the wallet's documents. What this adds is the two things multipaz
 * deliberately leaves to the app: which credentials may be offered, and a consent step that waits for a
 * *person* rather than answering itself. [state] is what a screen renders; [accept] and [decline] are what
 * a screen calls back.
 *
 * **The BLE half is written but unproven.** multipaz ships `BlePeripheralManagerIos`, so the transport is
 * there, but the iOS Simulator has no Bluetooth radio (nor NFC), and multipaz has no other mdoc transport —
 * so nothing between [startQrEngagement] and a connected reader can be exercised without a device and a
 * verifier. What *is* covered by tests is everything after the request arrives: matching, consent and the
 * response, which `mdocPresentment` performs with no transport involved. When a device is available, the
 * thing to watch is the connection, not the CBOR.
 */
class IosProximityPresenter(
    private val walletEngine: IosWalletEngine,
    /** Where the wallet's own credentials live; anything else in the store is not offered. */
    private val credentialDomain: String = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {

    private val mutableState = MutableStateFlow<IosProximityState>(IosProximityState.Idle)
    val state: StateFlow<IosProximityState> = mutableState.asStateFlow()

    private var presentmentJob: Job? = null
    private var transport: MdocTransport? = null
    private var pendingConsent: CompletableDeferred<CredentialPresentmentSelection?>? = null

    /** The request being consented to, kept so [accept] can turn the app's answer back into matches. */
    private var pendingData: CredentialPresentmentData? = null

    /** What the user agreed to share, remembered so the success state can name it. */
    private var sharedDocuments: List<String> = emptyList()

    /**
     * Advertises this wallet over BLE and publishes the QR the reader scans.
     *
     * Returns as soon as the QR is available; the exchange continues in the background and shows up in
     * [state].
     */
    suspend fun startQrEngagement() {
        cancel()

        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val connectionMethod = bleConnectionMethod()
        val engagement = deviceEngagement(eDeviceKey.publicKey, connectionMethod)

        try {
            // `advertise` is the extension on *connection methods*: it creates the transports and starts
            // them advertising in one step, which is also what keeps their states consistent.
            //
            // Bounded, because it does not fail when Bluetooth is unavailable — it waits. Observed on
            // the simulator, which has no radio: the call simply never returns, so the QR screen would
            // show its spinner for ever with nothing to explain it. The same wait is what a user who
            // denies the system Bluetooth prompt would get on a real phone. The timeout is long enough
            // to answer that prompt and short enough to end in a message instead of a hang.
            val transports = withTimeoutOrNull(ADVERTISE_TIMEOUT) {
                listOf(connectionMethod).advertise(
                    role = MdocRole.MDOC,
                    transportFactory = MdocTransportFactory.Default,
                    options = MdocTransportOptions(bleUseL2CAP = true),
                )
            }
            if (transports == null) {
                mutableState.value = IosProximityState.Failed(message = BLUETOOTH_UNAVAILABLE)
                return
            }

            mutableState.value = IosProximityState.Engaging(qrPayload = engagement.toQrPayload())

            presentmentJob = scope.launch {
                runPresentment(transports, eDeviceKey, engagement)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /**
     * Peripheral-server mode: the wallet advertises and the reader connects to it, which is the mode an
     * iOS app can actually offer — CoreBluetooth lets an app be a peripheral, and scanning as a central is
     * the reader's job anyway.
     */
    internal fun bleConnectionMethod() = MdocConnectionMethodBle(
        supportsPeripheralServerMode = true,
        supportsCentralClientMode = false,
        peripheralServerModeUuid = UUID.randomUUID(),
        centralClientModeUuid = null,
    )

    /** The device engagement a reader scans, as raw CBOR. `internal` so a test can parse it back. */
    internal fun deviceEngagement(
        eSenderKey: org.multipaz.crypto.EcPublicKey,
        connectionMethod: MdocConnectionMethodBle,
    ): ByteArray = EngagementGenerator(
        eSenderKey = eSenderKey,
        version = ENGAGEMENT_VERSION,
    ).addConnectionMethods(listOf(connectionMethod)).generate()

    /**
     * Answers the consent step with what the user chose to share.
     *
     * Releasing nothing — an empty list, or a list whose every claim was unchecked — is a refusal
     * rather than an empty response, which is also how multipaz reads a null selection.
     */
    fun accept(disclosures: List<IosProximityDisclosure>) {
        val selection = pendingData?.toSelection(disclosures)
        if (selection == null || selection.matches.isEmpty()) {
            decline()
            return
        }

        sharedDocuments = selection.matches.map { match ->
            match.credential.document.displayName ?: match.credential.document.identifier
        }.distinct()
        mutableState.value = IosProximityState.Sending
        pendingConsent?.complete(selection)
        pendingConsent = null
    }

    /** Answers the consent step with a refusal; the reader is told nothing was shared. */
    fun decline() {
        pendingConsent?.complete(null)
        pendingConsent = null
        pendingData = null
    }

    /** Stops advertising and closes any connection — the back button, and every terminal state. */
    fun cancel() {
        pendingConsent?.complete(null)
        pendingConsent = null
        pendingData = null
        presentmentJob?.cancel()
        presentmentJob = null
        scope.launch { runCatching { transport?.close() } }
        transport = null
        mutableState.value = IosProximityState.Idle
    }

    private suspend fun runPresentment(
        transports: List<MdocTransport>,
        eDeviceKey: EcPrivateKey,
        engagement: ByteArray,
    ) {
        try {
            val connected = transports.waitForConnection(eSenderKey = eDeviceKey.publicKey)
            transport = connected

            Iso18013Presentment(
                transport = connected,
                eDeviceKey = eDeviceKey,
                deviceEngagement = ByteString(engagement).toDataItem(),
                handover = Simple.NULL,
                source = presentmentSource(),
                keyAgreementPossible = listOf(EcCurve.P256),
                timeout = ENGAGEMENT_TIMEOUT,
                onSendingResponse = { mutableState.value = IosProximityState.Sending },
            )
            mutableState.value = IosProximityState.Sent(sharedDocuments = sharedDocuments)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Two of multipaz's outcomes are answers rather than errors, and the screens show them
            // differently — both learned from the presentment tests rather than from the docs.
            when (t) {
                is PresentmentCanceledException -> {
                    // The user declined. Nothing was shared and nothing went wrong.
                    mutableState.value = IosProximityState.Idle
                }

                is PresentmentCannotSatisfyRequestException -> {
                    mutableState.value = IosProximityState.Failed(
                        message = "This wallet holds nothing the reader asked for."
                    )
                }

                else -> fail(t)
            }
        }
    }

    /**
     * The wallet's answer to "what may be presented, and does the user agree".
     *
     * `domainsMdocSignature` is the wallet's own credential domain, so a document some other component put
     * in the same store is never offered — the same scoping the reader applies when listing documents.
     */
    private suspend fun presentmentSource() = SimplePresentmentSource(
        documentStore = walletEngine.store().documentStore,
        documentTypeRepository = documentTypeRepository,
        domainsMdocSignature = listOf(credentialDomain),
        showConsentPromptFn = { requester, trustMetadata, data, _, _ ->
            awaitConsent(
                requester = requester,
                trustMetadata = trustMetadata,
                data = data,
            )
        },
    )

    /**
     * Publishes the request and suspends until a screen answers.
     *
     * This is the whole reason the app supplies a presentment source at all: multipaz's default answers
     * immediately, which would release documents without asking anyone.
     */
    private suspend fun awaitConsent(
        requester: Requester,
        trustMetadata: TrustMetadata?,
        data: CredentialPresentmentData,
    ): CredentialPresentmentSelection? {
        val consent = CompletableDeferred<CredentialPresentmentSelection?>()
        pendingConsent = consent
        pendingData = data
        mutableState.value = IosProximityState.Requesting(
            request = data.toProximityRequest(
                // A name without trust behind it is still worth showing — but only the trust decision
                // marks it verified, and over BLE there is usually neither.
                requesterName = trustMetadata?.displayName ?: requester.appId,
                requesterIsTrusted = trustMetadata != null,
            ),
        )

        // Bounded: a reader that is handed nothing eventually times out anyway, and leaving the BLE
        // connection open forever is worse than telling it no.
        val selection = withTimeoutOrNull(CONSENT_TIMEOUT) { consent.await() }
        if (selection != null) {
            mutableState.value = IosProximityState.Sending
        }
        return selection
    }

    private fun fail(cause: Throwable) {
        Logger.w(TAG, "proximity presentation failed: ${cause.message}")
        mutableState.value = IosProximityState.Failed(
            message = cause.message ?: cause::class.simpleName ?: "Sharing failed."
        )
    }

    private companion object {
        const val TAG = "IosProximityPresenter"

        /** ISO 18013-5 device engagement version, as multipaz's own samples use. */
        const val ENGAGEMENT_VERSION = "1.0"

        val ENGAGEMENT_TIMEOUT = 1.minutes
        val CONSENT_TIMEOUT = 2.minutes

        /** Long enough for the user to answer iOS's Bluetooth prompt, short enough not to read as a hang. */
        val ADVERTISE_TIMEOUT = 20.seconds

        const val BLUETOOTH_UNAVAILABLE =
            "Could not start sharing over Bluetooth. Check that Bluetooth is on and that this app is " +
                    "allowed to use it."

        /**
         * Empty on purpose. Localized claim names live in multipaz's separate `multipaz-doctypes`
         * artifact; without it a claim shows its data-element identifier, which is exactly what iOS
         * already does on the documents and details screens. Adding the artifact for this one screen
         * would make proximity the only place iOS speaks in display names — the fix is to give the whole
         * app localized claim names at once, not to special-case consent.
         */
        val documentTypeRepository = DocumentTypeRepository()
    }
}

/** The `mdoc:` URI a reader scans, per ISO 18013-5 §8.2.2.3. */
internal fun ByteArray.toQrPayload(): String = "mdoc:" + toBase64Url()

private fun ByteString.toDataItem(): DataItem =
    org.multipaz.cbor.Cbor.decode(this.toByteArray())

// The consent step's two translations, deliberately outside the presenter: neither touches its state,
// and out here they can be exercised without a Bluetooth radio — which is the only way anything about
// proximity gets tested on this platform.

/** multipaz's view of a request, as a consent screen needs to see it. */
internal fun CredentialPresentmentData.toProximityRequest(
    requesterName: String?,
    requesterIsTrusted: Boolean,
): IosProximityRequest = IosProximityRequest(
    requesterName = requesterName,
    requesterIsTrusted = requesterIsTrusted,
    combinations = combinationsOfMatches().map { matches ->
        IosProximityRequest.Combination(documents = matches.map { it.toRequestedDocument() })
    },
)

/**
 * The app's answer turned back into multipaz's matches, narrowed to the claims the user kept.
 *
 * `mdocPresentment` builds the response from `match.claims.keys`, so dropping entries here is what makes
 * selective disclosure real rather than cosmetic. A credential nobody chose, or one whose every claim
 * was unchecked, is left out entirely.
 */
internal fun CredentialPresentmentData.toSelection(
    disclosures: List<IosProximityDisclosure>,
): CredentialPresentmentSelection {
    val wanted = disclosures.associateBy { it.documentId to it.credentialId }

    val matches = combinationsOfMatches().flatten()
        .distinctBy { it.credential.document.identifier to it.credential.identifier }
        .mapNotNull { match ->
            val disclosure = wanted[
                match.credential.document.identifier to match.credential.identifier
            ] ?: return@mapNotNull null

            val keptClaims = match.claims.filter { (requested, claim) ->
                claimRef(requested, claim) in disclosure.claims
            }
            if (keptClaims.isEmpty()) null else match.copy(claims = keptClaims)
        }

    return CredentialPresentmentSelection(matches = matches)
}

private fun MemberMatch.toRequestedDocument(): IosProximityRequest.RequestedDocument {
    val document = credential.document
    return IosProximityRequest.RequestedDocument(
        documentId = document.identifier,
        credentialId = credential.identifier,
        documentName = document.displayName ?: document.identifier,
        docType = document.eudiMetadata?.format?.identifier.orEmpty(),
        claims = claims.map { (requested, claim) -> toClaimInfo(requested, claim) },
    )
}

private fun toClaimInfo(
    requested: RequestedClaim,
    claim: Claim,
) = IosProximityRequest.RequestedClaimInfo(
    claim = claimRef(requested, claim),
    displayName = claim.displayName,
    value = (claim as? MdocClaim)?.value?.toClaimString() ?: claim.render(),
    intentToRetain = (requested as? MdocRequestedClaim)?.intentToRetain == true,
)

/**
 * Every way this request could be answered.
 *
 * Each credential set must be satisfied by one of its options, each option's members must each be
 * satisfied by one of their matches, and an optional set may be skipped — so the alternatives are a
 * cross product. For an ISO 18013-5 request over one document type it collapses to a single
 * combination; two PIDs in the wallet make two, which is the case worth getting right, because picking
 * one for the user would silently share the wrong document.
 */
private fun CredentialPresentmentData.combinationsOfMatches(): List<List<MemberMatch>> {
    var combinations = listOf(emptyList<MemberMatch>())

    for (credentialSet in credentialSets) {
        val alternatives = buildList {
            credentialSet.options.forEach { addAll(it.memberCombinations()) }
            // An optional set the wallet need not answer: leaving it out is an answer too.
            if (credentialSet.optional) add(emptyList())
        }
        if (alternatives.isEmpty()) continue

        combinations = combinations
            .flatMap { chosen -> alternatives.map { chosen + it } }
            .take(MAX_COMBINATIONS)
    }

    return combinations.filter { it.isNotEmpty() }
}

/** The same cross product one level down: each member contributes exactly one of its matches. */
private fun CredentialPresentmentSetOption.memberCombinations(): List<List<MemberMatch>> {
    var combinations = listOf(emptyList<MemberMatch>())

    for (member in members) {
        if (member.matches.isEmpty()) continue
        combinations = combinations
            .flatMap { chosen -> member.matches.map { chosen + it } }
            .take(MAX_COMBINATIONS)
    }

    return combinations
}

/**
 * How a claim is named across the seam. Namespace *and* data element, because the same identifier under
 * two namespaces is two different claims.
 */
private fun claimRef(requested: RequestedClaim, claim: Claim) = IosProximityClaimRef(
    namespace = (requested as? MdocRequestedClaim)?.namespaceName,
    identifier = (requested as? MdocRequestedClaim)?.dataElementName ?: claim.displayName,
)

/**
 * A ceiling on the alternatives offered. The cross product is one combination for an ordinary request;
 * this only bites on a pathological one, where a screen listing hundreds of choices would be useless.
 */
private const val MAX_COMBINATIONS = 16

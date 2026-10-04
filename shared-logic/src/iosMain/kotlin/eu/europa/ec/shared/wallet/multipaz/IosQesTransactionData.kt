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

import eu.europa.ec.corelogic.model.DocumentChecksumDomain
import eu.europa.ec.corelogic.model.PresentationTransactionDataDomain
import eu.europa.ec.corelogic.model.QesDocumentDigestDomain
import eu.europa.ec.corelogic.model.QesSignatureRequestDomain
import eu.europa.ec.corelogic.model.SigningAttributeDomain
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.util.fromBase64Url
import kotlin.io.encoding.Base64

/*
 * OpenID4VP `transaction_data` on iOS: the two Cloud Signature Consortium types Android enables through
 * wallet-core 0.31.0 (`withTransactionDataTypes(QES_APPROVAL, QES)`, upstream 2428c55d), as multipaz
 * transaction types. Without them multipaz cannot even parse a request that carries transaction data —
 * it refuses it as an unknown transaction type before consent — which is what a signing service now
 * sends with its second presentation.
 *
 * The payload rules are wallet-core's (`QesTransactionData.kt` and `QesTransactionTypes.kt` in 0.31.0):
 * the same JSON names, unknown fields refused, and the same constraints. A payload that breaks them makes
 * its type inapplicable, so multipaz reports the request as one this wallet cannot satisfy. Where wallet-core
 * answers `invalid_transaction_data` instead, that is the difference. That is why the payload multipaz holds
 * is the decoded JSON rather than a parsed object: parsing it there would fail the whole request instead.
 */

/** The transaction types this wallet answers, for the presentment source's repository. */
internal val walletTransactionTypes: List<TransactionType<*>> =
    listOf(IosQesApprovalTransactionType, IosQesRequestTransactionType)

/** What [this] asks to authorise, for the consent screen; [PresentationTransactionDataDomain.Unavailable] when unreadable. */
internal fun TransactionData<*>.toPresentationTransactionDataDomain(): PresentationTransactionDataDomain {
    val json = payload as? String ?: return PresentationTransactionDataDomain.Unavailable
    return runCatching {
        when (type.identifier) {
            QES_APPROVAL_TYPE -> qesJson.decodeFromString(QesApprovalPayload.serializer(), json)
                .toDomain(displayName = type.displayName)

            QES_REQUEST_TYPE -> qesJson.decodeFromString(QesRequestPayload.serializer(), json)
                .toDomain(displayName = type.displayName)

            else -> PresentationTransactionDataDomain.Unavailable
        }
    }.getOrDefault(PresentationTransactionDataDomain.Unavailable)
}

/**
 * A CSC transaction type, holding its transaction data as the decoded JSON (see the note at the top).
 *
 * `transaction_data_hashes_alg` is read here because multipaz leaves it to the type: the first algorithm
 * this wallet supports is the one `transaction_data_hashes` are computed with, SHA-256 when it is absent,
 * and a list naming none the wallet supports is refused, as multipaz 0.99 refused it.
 */
internal abstract class IosQesTransactionType(
    displayName: String,
    identifier: String,
    kbJwtResponseClaimName: String = identifier,
    openId4VpMdocResponseNamespace: String = identifier,
) : TransactionType<String>(
    displayName = displayName,
    identifier = identifier,
    kbJwtResponseClaimName = kbJwtResponseClaimName,
    openId4VpMdocResponseNamespace = openId4VpMdocResponseNamespace,
) {
    override fun parseOpenId4VpRequest(jsonString: String): String = jsonString

    override fun parseJson(serialized: ByteString): TransactionData<String> {
        val json = serialized.decodeToString().fromBase64Url().decodeToString()
        return TransactionData(
            type = this,
            payload = json,
            protocol = TransactionProtocol.OPENID4VP,
            rawBytes = serialized,
            hashAlgorithms = hashAlgorithmsOf(json),
        )
    }
}

/**
 * A relying party's request to approve a signature it has already prepared, answered with a
 * `qesApproval` digest — CSC Data Model Bindings clause 7.2.1.
 *
 * ⚠️ In an SD-JWT VC's Key Binding JWT the approval is `org.cloudsignatureconsortium.dm.1` → `qesApproval`,
 * where wallet-core sends one flat claim, `org.cloudsignatureconsortium.dm.1.qesApproval`. multipaz puts
 * whatever a type answers under a single claim of the type's own, so the flat form cannot be produced
 * through it. No EUDI service reads either form: the verifier checks `transaction_data_hashes`, which
 * multipaz computes as before.
 */
internal object IosQesApprovalTransactionType : IosQesTransactionType(
    displayName = "QES approval",
    identifier = QES_APPROVAL_TYPE,
    kbJwtResponseClaimName = QES_APPROVAL_NAMESPACE,
    openId4VpMdocResponseNamespace = QES_APPROVAL_NAMESPACE,
) {
    /** An SD-JWT VC answers with a Key Binding JWT claim, an mdoc with a device-signed element; both need a valid payload. */
    override suspend fun isApplicable(transactionData: TransactionData<String>, credential: Credential): Boolean =
        (credential is SdJwtVcCredential || credential is MdocCredential) &&
                approvalOrNull(transactionData.payload) != null

    /**
     * Clause 7.2.1.1: SHA-256 over the base64url-decoded transaction data, as the digest itself, under
     * [QES_APPROVAL_NAMESPACE]. multipaz adds its `transactionDataHash` beside it; wallet-core does not.
     */
    override suspend fun generateMdocResponseElements(
        transactionData: TransactionData<String>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?,
    ): Map<String, DataItem> = buildMap {
        putAll(super.generateMdocResponseElements(transactionData, credential, userInput, docRequestId))
        val decoded = transactionData.rawBytes.decodeToString().fromBase64Url()
        put(QES_APPROVAL_ELEMENT, Bstr(Crypto.digest(Algorithm.SHA256, decoded)))
    }

    /**
     * Clause 7.2.1.2: the digest of the transaction data string exactly as it was received (not decoded),
     * with the algorithm the request names in `hashAlgorithmOID`, base64 with padding (CSC Data Model 5.2).
     */
    override suspend fun generateSdJwtResponseClaims(
        transactionData: TransactionData<String>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?,
    ): Map<String, JsonElement> = buildMap {
        putAll(super.generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId))
        val approval = approvalOrNull(transactionData.payload)
            ?: throw IllegalArgumentException("Transaction data of type '$identifier' does not hold a valid approval")
        val digest = Crypto.digest(hashAlgorithmOf(approval.hashAlgorithmOid), transactionData.rawBytes.toByteArray())
        put(QES_APPROVAL_ELEMENT, JsonPrimitive(Base64.Default.encode(digest)))
    }
}

/** A relying party's request to have documents signed. Nothing is returned for it but multipaz's hashes. */
internal object IosQesRequestTransactionType : IosQesTransactionType(
    displayName = "QES request",
    identifier = QES_REQUEST_TYPE,
) {
    /** SD-JWT VC only, as wallet-core applies it: it has no mdoc binding. */
    override suspend fun isApplicable(transactionData: TransactionData<String>, credential: Credential): Boolean =
        credential is SdJwtVcCredential &&
                runCatching {
                    qesJson.decodeFromString(QesRequestPayload.serializer(), transactionData.payload)
                }.isSuccess
}

private fun approvalOrNull(json: String): QesApprovalPayload? =
    runCatching { qesJson.decodeFromString(QesApprovalPayload.serializer(), json) }.getOrNull()

private fun hashAlgorithmsOf(json: String): List<Algorithm>? {
    val named = qesJson.parseToJsonElement(json).jsonObject["transaction_data_hashes_alg"] as? JsonArray
        ?: return null
    val supported = named.mapNotNull { name ->
        (name as? JsonPrimitive)?.let { runCatching { Algorithm.fromHashAlgorithmIdentifier(it.content) }.getOrNull() }
    }
    require(supported.isNotEmpty()) { "No supported algorithms in transaction_data_hashes_alg" }
    return supported
}

private fun hashAlgorithmOf(oid: String): Algorithm = when (oid) {
    "2.16.840.1.101.3.4.2.1" -> Algorithm.SHA256
    "2.16.840.1.101.3.4.2.2" -> Algorithm.SHA384
    "2.16.840.1.101.3.4.2.3" -> Algorithm.SHA512
    else -> throw IllegalArgumentException("Unsupported 'hashAlgorithmOID' '$oid' for a QES approval")
}

internal const val QES_APPROVAL_TYPE = "https://cloudsignatureconsortium.org/2025/qes-approval"
internal const val QES_REQUEST_TYPE = "https://cloudsignatureconsortium.org/2025/qes"
private const val QES_APPROVAL_NAMESPACE = "org.cloudsignatureconsortium.dm.1"
private const val QES_APPROVAL_ELEMENT = "qesApproval"

/** OpenID4VP treats a known type with an unknown field as invalid, so unknown fields are refused. */
private val qesJson = Json { ignoreUnknownKeys = false }

// The payloads, field for field as wallet-core 0.31.0 reads them, including its constraints.

@Serializable
private data class QesApprovalPayload(
    @SerialName("type") val type: String,
    @SerialName("credential_ids") val credentialIds: List<String>,
    @SerialName("transaction_data_hashes_alg") val hashAlgorithms: List<String>? = null,
    @SerialName("locations") val locations: List<String>? = null,
    @SerialName("credentialID") val credentialId: String? = null,
    @SerialName("signatureQualifier") val signatureQualifier: String? = null,
    @SerialName("numSignatures") val numSignatures: Int,
    @SerialName("documentDigests") val documentDigests: List<DocumentDigestPayload>,
    @SerialName("hashAlgorithmOID") val hashAlgorithmOid: String,
) {
    init {
        require(type == QES_APPROVAL_TYPE)
        require(credentialIds.isNotEmpty())
        require(hashAlgorithms == null || hashAlgorithms.isNotEmpty())
        require(locations == null || locations.isNotEmpty())
        require(credentialId != null || signatureQualifier != null)
        require(numSignatures > 0)
        require(documentDigests.isNotEmpty())
        require(hashAlgorithmOid.isNotBlank())
    }

    fun toDomain(displayName: String?) = PresentationTransactionDataDomain.QesApproval(
        displayName = displayName,
        credentialIds = credentialIds,
        credentialId = credentialId,
        signatureQualifier = signatureQualifier,
        numSignatures = numSignatures,
        hashAlgorithmOid = hashAlgorithmOid,
        documentDigests = documentDigests.map { it.toDomain() },
    )
}

@Serializable
private data class QesRequestPayload(
    @SerialName("type") val type: String,
    @SerialName("credential_ids") val credentialIds: List<String>,
    @SerialName("transaction_data_hashes_alg") val hashAlgorithms: List<String>? = null,
    @SerialName("signatureRequests") val signatureRequests: List<SignatureRequestPayload>,
) {
    init {
        require(type == QES_REQUEST_TYPE)
        require(credentialIds.isNotEmpty())
        require(hashAlgorithms == null || hashAlgorithms.isNotEmpty())
        require(signatureRequests.isNotEmpty())
    }

    fun toDomain(displayName: String?) = PresentationTransactionDataDomain.Qes(
        displayName = displayName,
        credentialIds = credentialIds,
        signatureRequests = signatureRequests.map { it.toDomain() },
    )
}

@Serializable
private data class DocumentDigestPayload(
    @SerialName("label") val label: String? = null,
    @SerialName("hash") val hash: String,
    @SerialName("hashType") val hashType: String = "dtbsr",
    @SerialName("signed_props") val signedProperties: List<AttributePayload>? = null,
    @SerialName("circumstantialData") val circumstantialData: String? = null,
    @SerialName("href") val href: String? = null,
    @SerialName("checksum") val checksum: ChecksumPayload? = null,
    @SerialName("access") val access: AccessPayload? = null,
) {
    init {
        require(label == null || label.isNotBlank())
        require(hash.isNotBlank())
        require(hashType in setOf("sdr", "dtbsr", "sodr"))
        require(signedProperties == null || signedProperties.isNotEmpty())
        require(href == null || href.isNotBlank())
    }

    fun toDomain() = QesDocumentDigestDomain(
        label = label,
        hash = hash,
        hashType = hashType,
        signedProperties = signedProperties?.map { it.toDomain() },
        href = href,
        checksum = checksum?.toDomain(),
        oneTimePassword = access?.oneTimePassword,
    )
}

@Serializable(with = SignatureRequestPayloadSerializer::class)
private sealed interface SignatureRequestPayload {
    val label: String?
    val signatureQualifier: String
    val responseUri: String?
    val signatureFormat: String?
    val conformanceLevel: String?
    val signedEnvelopeProperty: String?
    val signedProperties: List<AttributePayload>?
    val signAlgo: String

    fun toDomain(): QesSignatureRequestDomain {
        val reference = this as? WithDocumentReference
        return QesSignatureRequestDomain(
            label = label,
            signatureQualifier = signatureQualifier,
            responseUri = responseUri,
            signatureFormat = signatureFormat,
            conformanceLevel = conformanceLevel,
            signedProperties = signedProperties?.map { it.toDomain() },
            href = reference?.href,
            checksum = reference?.checksum?.toDomain(),
            oneTimePassword = reference?.access?.oneTimePassword,
        )
    }

    @Serializable
    data class WithDocument(
        @SerialName("signatureQualifier") override val signatureQualifier: String,
        @SerialName("responseURI") override val responseUri: String? = null,
        @SerialName("signature_format") override val signatureFormat: String? = null,
        @SerialName("conformance_level") override val conformanceLevel: String? = null,
        @SerialName("signed_envelope_property") override val signedEnvelopeProperty: String? = null,
        @SerialName("signed_props") override val signedProperties: List<AttributePayload>? = null,
        @SerialName("referenceUri") val referenceUri: String? = null,
        @SerialName("label") override val label: String? = null,
        @SerialName("document") val document: String,
        @SerialName("documentType") val documentType: String = "sod",
        @SerialName("circumstantialData") val circumstantialData: String? = null,
        @SerialName("signAlgo") override val signAlgo: String,
        @SerialName("signAlgoParams") val signAlgoParams: String? = null,
    ) : SignatureRequestPayload {
        init {
            validate()
            require(document.isNotBlank())
            require(documentType in setOf("sod", "sfd"))
        }
    }

    @Serializable
    data class WithDocumentReference(
        @SerialName("signatureQualifier") override val signatureQualifier: String,
        @SerialName("responseURI") override val responseUri: String? = null,
        @SerialName("signature_format") override val signatureFormat: String? = null,
        @SerialName("conformance_level") override val conformanceLevel: String? = null,
        @SerialName("signed_envelope_property") override val signedEnvelopeProperty: String? = null,
        @SerialName("signed_props") override val signedProperties: List<AttributePayload>? = null,
        @SerialName("referenceUri") val referenceUri: String? = null,
        @SerialName("label") override val label: String? = null,
        @SerialName("access") val access: AccessPayload? = null,
        @SerialName("href") val href: String,
        @SerialName("checksum") val checksum: ChecksumPayload? = null,
        @SerialName("circumstantialData") val circumstantialData: String? = null,
        @SerialName("signAlgo") override val signAlgo: String,
        @SerialName("signAlgoParams") val signAlgoParams: String? = null,
    ) : SignatureRequestPayload {
        init {
            validate()
            require(href.isNotBlank())
        }
    }
}

private fun SignatureRequestPayload.validate() {
    require(label == null || label!!.isNotBlank())
    require(signatureQualifier.isNotBlank())
    require(responseUri == null || responseUri!!.isNotBlank())
    require(signatureFormat == null || signatureFormat in setOf("C", "X", "P", "J"))
    require(
        conformanceLevel == null || conformanceLevel in setOf(
            "AdES-B-B", "AdES-B-T", "AdES-B-LT", "AdES-B-LTA", "AdES-B", "AdES-T", "AdES-LT", "AdES-LTA",
        )
    )
    require(
        signedEnvelopeProperty == null || signedEnvelopeProperty in setOf(
            "Detached", "Attached", "Parallel", "Certification", "Revision", "Enveloped", "Enveloping",
        )
    )
    require(signedProperties == null || signedProperties!!.isNotEmpty())
    require(signAlgo.isNotBlank())
}

/** Exactly one of `document` and `href` decides the variant, as in wallet-core. */
private object SignatureRequestPayloadSerializer :
    JsonContentPolymorphicSerializer<SignatureRequestPayload>(SignatureRequestPayload::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out SignatureRequestPayload> {
        val fields = element.jsonObject
        val hasDocument = "document" in fields
        val hasHref = "href" in fields
        require(hasDocument xor hasHref)
        return if (hasDocument) {
            SignatureRequestPayload.WithDocument.serializer()
        } else {
            SignatureRequestPayload.WithDocumentReference.serializer()
        }
    }
}

@Serializable
private data class ChecksumPayload(
    @SerialName("value") val value: String,
    @SerialName("algorithmOID") val algorithmOid: String,
) {
    init {
        require(value.isNotBlank())
        require(algorithmOid.isNotBlank())
    }

    fun toDomain() = DocumentChecksumDomain(value = value, algorithmOid = algorithmOid)
}

@Serializable
private data class AccessPayload(
    @SerialName("type") val accessMode: String,
    @SerialName("oneTimePassword") val oneTimePassword: String? = null,
) {
    init {
        require(accessMode.isNotBlank())
        require(accessMode != "OTP" || oneTimePassword != null)
    }
}

@Serializable
private data class AttributePayload(
    @SerialName("attribute_name") val name: String,
    @SerialName("attribute_value") val value: String? = null,
) {
    init {
        require(name.isNotBlank())
    }

    fun toDomain() = SigningAttributeDomain(name = name, value = value)
}

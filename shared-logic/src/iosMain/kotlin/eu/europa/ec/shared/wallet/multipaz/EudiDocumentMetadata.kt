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

import eu.europa.ec.shared.wallet.document.IssuerMetadata
import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.document.AbstractDocumentMetadata
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The application metadata the iOS wallet attaches to every multipaz `Document` — the KMP
 * counterpart of the Android document manager's `internal ApplicationMetadata`.
 *
 * multipaz stores this as an opaque CBOR blob and never reads it, so the app owns the schema. The
 * field names match the Android original so the two are easy to compare side by side, but the blobs
 * are **not interchangeable**, for one reason: Android tags the credential policy with
 * `javaClass.name`, which has no Kotlin/Native equivalent, so this writes stable literal tags
 * instead. That is harmless — a wallet's documents are bound to the device's secure area and can
 * never be read by the other platform anyway.
 *
 * Deliberately narrower than the Android original in two ways:
 *  - **no `issuerProvidedData` copy.** Android caches the first credential's issuer data here and
 *    re-parses it for `IssuedDocument.data`; on iOS the claims are read straight off the
 *    `MdocCredential`, so duplicating the blob would only be a second thing to keep in sync.
 *  - **no `deferredRelatedData` / `keyAttestation`.** Both belong to issuance, which wallet-kit owns
 *    on iOS for now. [issuedAt] is what distinguishes an issued document from a pending one, exactly
 *    as `ApplicationMetadata.issue()` sets it on Android.
 */
internal class EudiDocumentMetadata private constructor(
    private var data: Data,
) : AbstractDocumentMetadata {

    override fun serialize(): ByteString = data.toCbor()

    val documentManagerId: String get() = data.documentManagerId
    val format: StoredDocumentFormat get() = data.format
    val credentialPolicy: WalletCredentialPolicy get() = data.credentialPolicy
    val issuerMetadata: IssuerMetadata? get() = data.issuerMetadata
    val issuedAt: Instant? get() = data.issuedAt

    /**
     * Records the issuer's per-claim display names on a document that was provisioned before they were
     * captured. Same contract as [issue]: the caller persists via `Document.edit`.
     *
     * **An empty list is meaningful and is stored deliberately.** `claims == null` means "never looked",
     * an empty list means "looked, and this issuer publishes none" — so one successful fetch settles a
     * document forever, instead of re-fetching on every view. A *failed* fetch must therefore not call
     * this, or the document would be marked settled on the strength of a dropped connection.
     */
    fun rememberClaimNames(claims: List<IssuerMetadata.Claim>) {
        val issuer = data.issuerMetadata ?: return
        data = data.copy(issuerMetadata = issuer.copy(claims = claims))
    }

    /**
     * Marks the document issued, stamping [issuedAt]. Mirrors `ApplicationMetadata.issue`, including
     * its contract: the caller is responsible for persisting the change via `Document.edit`.
     */
    fun issue(at: Instant = Clock.System.now()) {
        data = data.copy(issuedAt = at)
    }

    internal data class Data(
        val documentManagerId: String,
        val format: StoredDocumentFormat,
        val credentialPolicy: WalletCredentialPolicy,
        val issuerMetadata: IssuerMetadata? = null,
        val issuedAt: Instant? = null,
    ) {

        fun toCbor(): ByteString = ByteString(
            Cbor.encode(
                buildCborMap {
                    put("documentManagerId", documentManagerId)
                    put("format", format.toDataItem())
                    put("credentialPolicy", credentialPolicy.toDataItem())
                    issuerMetadata?.let { put("issuerMetadata", it.toJson()) }
                    // Seconds since the epoch rather than the Android original's CBOR date-time
                    // string: it round-trips without a formatter, and whole seconds are all an
                    // issuance date is ever displayed at.
                    issuedAt?.let { put("issuedAtEpochSeconds", it.epochSeconds) }
                },
            ),
        )

        companion object {

            fun fromCbor(cbor: ByteString): Data {
                val item = Cbor.decode(cbor.toByteArray())
                return Data(
                    documentManagerId = item["documentManagerId"].asTstr,
                    format = storedDocumentFormatFromDataItem(item["format"]),
                    credentialPolicy = credentialPolicyFromDataItem(item["credentialPolicy"]),
                    issuerMetadata = item.optional("issuerMetadata") {
                        IssuerMetadata.fromJson(it.asTstr).getOrNull()
                    },
                    issuedAt = item.optional("issuedAtEpochSeconds") {
                        Instant.fromEpochSeconds(it.asNumber)
                    },
                )
            }
        }
    }

    companion object {

        /**
         * Builds the metadata for a document being created. The result is handed to
         * `DocumentStore.createDocument`, which serializes it.
         */
        fun create(
            documentManagerId: String,
            format: StoredDocumentFormat,
            credentialPolicy: WalletCredentialPolicy,
            issuerMetadata: IssuerMetadata? = null,
        ): EudiDocumentMetadata = EudiDocumentMetadata(
            Data(
                documentManagerId = documentManagerId,
                format = format,
                credentialPolicy = credentialPolicy,
                issuerMetadata = issuerMetadata,
            ),
        )

        /**
         * The factory `DocumentStore.Builder.setDocumentMetadataFactory` needs: rebuilds the
         * metadata of a document already in the store from its serialized form.
         */
        fun restore(
            @Suppress("UNUSED_PARAMETER") documentId: String,
            serializedData: ByteString,
        ): EudiDocumentMetadata = EudiDocumentMetadata(Data.fromCbor(serializedData))
    }
}

/**
 * The format of a stored document. multipaz has no such notion — it stores credentials, not
 * documents of a format — so, as on Android, the format is application metadata.
 */
internal sealed interface StoredDocumentFormat {

    /** The docType or vct, i.e. what `WalletDocument.formatType` carries. */
    val identifier: String

    data class MsoMdoc(val docType: String) : StoredDocumentFormat {
        override val identifier: String get() = docType
    }

    data class SdJwtVc(val vct: String) : StoredDocumentFormat {
        override val identifier: String get() = vct
    }
}

/** Same CBOR shape as the Android original's `DocumentFormat.toDataItem()`. */
private fun StoredDocumentFormat.toDataItem(): DataItem = buildCborMap {
    when (this@toDataItem) {
        is StoredDocumentFormat.MsoMdoc -> put("docType", docType)
        is StoredDocumentFormat.SdJwtVc -> put("vct", vct)
    }
}

private fun storedDocumentFormatFromDataItem(item: DataItem): StoredDocumentFormat {
    require(item is CborMap) { "Expected a CborMap for the document format" }
    return when {
        item.hasKey("docType") -> StoredDocumentFormat.MsoMdoc(item["docType"].asTstr)
        item.hasKey("vct") -> StoredDocumentFormat.SdJwtVc(item["vct"].asTstr)
        else -> throw IllegalArgumentException("Unknown document format")
    }
}

private const val POLICY_ONCE_ONLY = "onceOnly"
private const val POLICY_LIMITED_TIME = "limitedTime"
private const val POLICY_ROTATING_BATCH = "rotatingBatch"

private fun WalletCredentialPolicy.toDataItem(): DataItem = buildCborMap {
    when (val policy = this@toDataItem) {
        is WalletCredentialPolicy.OnceOnly -> {
            put("type", POLICY_ONCE_ONLY)
            put("numberOfCredentials", policy.numberOfCredentials.toLong())
            policy.reissueTriggerUnused?.let { put("reissueTriggerUnused", it.toLong()) }
        }

        is WalletCredentialPolicy.LimitedTime -> {
            put("type", POLICY_LIMITED_TIME)
            policy.reissueTriggerLifetimeLeft
                ?.let { put("reissueTriggerLifetimeLeft", it.inWholeSeconds) }
        }

        is WalletCredentialPolicy.RotatingBatch -> {
            put("type", POLICY_ROTATING_BATCH)
            put("numberOfCredentials", policy.numberOfCredentials.toLong())
            policy.reissueTriggerLifetimeLeft
                ?.let { put("reissueTriggerLifetimeLeft", it.inWholeSeconds) }
        }
    }
}

private fun credentialPolicyFromDataItem(item: DataItem): WalletCredentialPolicy {
    require(item is CborMap) { "Expected a CborMap for the credential policy" }
    return when (val type = item["type"].asTstr) {
        POLICY_ONCE_ONLY -> WalletCredentialPolicy.OnceOnly(
            numberOfCredentials = item["numberOfCredentials"].asNumber.toInt(),
            reissueTriggerUnused = item.optional("reissueTriggerUnused") { it.asNumber.toInt() },
        )

        POLICY_LIMITED_TIME -> WalletCredentialPolicy.LimitedTime(
            reissueTriggerLifetimeLeft =
                item.optional("reissueTriggerLifetimeLeft") { it.asNumber.seconds },
        )

        POLICY_ROTATING_BATCH -> WalletCredentialPolicy.RotatingBatch(
            numberOfCredentials = item["numberOfCredentials"].asNumber.toInt(),
            reissueTriggerLifetimeLeft =
                item.optional("reissueTriggerLifetimeLeft") { it.asNumber.seconds },
        )

        else -> throw IllegalArgumentException("Unknown credential policy type: $type")
    }
}

/** Reads [key] if present; absent keys are how this schema stays forward-compatible. */
private inline fun <T> DataItem.optional(key: String, extract: (DataItem) -> T?): T? =
    if (this is CborMap && hasKey(key)) extract(this[key]) else null

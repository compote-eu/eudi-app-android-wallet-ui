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

// What Android's wallet-core records about the relying party with each presentation — TS10 fields read
// from its registration certificate — kept with multipaz's event on iOS. multipaz records the verifier's
// certificate chain and trust metadata, but nothing of the registration certificate, and that is where a
// data-deletion request's contacts and the data protection authority come from.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tstr
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventPresentment

/** The relying party as a presentation's transaction record keeps it. */
@Serializable
internal data class PresentationPartyRecord(
    val name: String? = null,
    val identifier: QualifiedIdentifierRecord? = null,
    /** Plain values, as TS10 keeps them: a country code sits among the URLs and is no contact. */
    val contacts: List<String> = emptyList(),
    val intermediaryName: String? = null,
    val intermediaryIdentifier: QualifiedIdentifierRecord? = null,
    val registrarUrl: String? = null,
    val purpose: List<LocalizedTextRecord> = emptyList(),
    val privacyPolicyUrls: List<String> = emptyList(),
    val authorityName: String? = null,
    val authorityContacts: List<String> = emptyList(),
)

@Serializable
internal data class QualifiedIdentifierRecord(val schemeUri: String, val value: String)

@Serializable
internal data class LocalizedTextRecord(val language: String, val value: String)

/** wallet-core 0.31.0's `PresentationLogBuilder.withRegistrationCertificate`, field for field. */
internal fun IssuerRegistration.toPresentationPartyRecord(): PresentationPartyRecord = PresentationPartyRecord(
    name = legalName
        ?: listOfNotNull(givenName, familyName).joinToString(" ").ifBlank { null }
        ?: name,
    identifier = subject?.toQualifiedIdentifierOrNull(),
    contacts = listOfNotNull(country, supportUri, infoUri),
    intermediaryName = intermediaryName.takeIf { intermediaryIdentifier != null },
    intermediaryIdentifier = intermediaryIdentifier?.toQualifiedIdentifierOrNull(),
    registrarUrl = registryUri,
    purpose = purpose.map { text -> LocalizedTextRecord(text.language, text.value) },
    privacyPolicyUrls = listOfNotNull(privacyPolicyUri),
    authorityName = supervisoryAuthority?.name,
    authorityContacts = supervisoryAuthority?.let { listOfNotNull(it.email, it.phone, it.uri) }.orEmpty(),
)

/**
 * wallet-core's `toQualifiedIdentifierOrNull`: an ETSI semantic identifier — `LEI-…`, `VAT…-…`, `NTR…-…`,
 * `EOR…-…`, `EXC…-…` — as the register it names and the value after the first hyphen.
 */
internal fun String.toQualifiedIdentifierOrNull(): QualifiedIdentifierRecord? {
    val scheme = when (take(3).uppercase()) {
        "LEI" -> "http://data.europa.eu/eudi/id/LEI"
        "VAT" -> "http://data.europa.eu/eudi/id/VATIN"
        "NTR" -> "http://data.europa.eu/eudi/id/EUID"
        "EOR" -> "http://data.europa.eu/eudi/id/EORI-No"
        "EXC" -> "http://data.europa.eu/eudi/id/Excise"
        else -> return null
    }
    val value = substringAfter('-', missingDelimiterValue = "").ifEmpty { return null }
    return QualifiedIdentifierRecord(schemeUri = scheme, value = value)
}

/**
 * Carries the consent step's view of a relying party to the event multipaz logs once the response is
 * accepted. The two meet only through the verifier's signing certificate: the presenter knows the
 * registration, the event knows the chain. Each record is handed over once.
 */
internal class PresentationPartyRecords {
    private val mutex = Mutex()
    private val bySigner = mutableMapOf<ByteString, PresentationPartyRecord>()

    suspend fun remember(signer: X509Cert, record: PresentationPartyRecord) = mutex.withLock {
        bySigner[signer.encoded] = record
    }

    suspend fun take(chain: X509CertChain?): PresentationPartyRecord? {
        val signer = chain?.certificates?.firstOrNull() ?: return null
        return mutex.withLock { bySigner.remove(signer.encoded) }
    }

    /**
     * The app data multipaz keeps with [event]: the relying party's record for a presentation whose
     * consent left one, and nothing for anything else. Never null — null would drop the event.
     */
    suspend fun appDataFor(event: Event): Map<String, DataItem> {
        val presentment = event as? EventPresentment ?: return emptyMap()
        val record = take(presentment.presentmentData.requesterCertChain) ?: return emptyMap()
        return mapOf(PRESENTATION_PARTY_KEY to Tstr(recordJson.encodeToString(PresentationPartyRecord.serializer(), record)))
    }
}

/** The relying party recorded with this event, or null when none was (or it cannot be read). */
internal fun Event.presentationPartyRecord(): PresentationPartyRecord? =
    appData[PRESENTATION_PARTY_KEY]?.let { item ->
        runCatching { recordJson.decodeFromString(PresentationPartyRecord.serializer(), item.asTstr) }.getOrNull()
    }

internal const val PRESENTATION_PARTY_KEY = "eu.europa.ec.eudi.presentationParty"

private val recordJson = Json { ignoreUnknownKeys = true }

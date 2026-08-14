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

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlinx.coroutines.withContext

/**
 * One credential a configured issuer says it can issue.
 *
 * Deliberately flat and multipaz-free: it is the raw answer, before this wallet decides anything about it.
 * What a PID is, how issuers are grouped and which entries get folded together are all decisions made
 * above — see `AddDocumentInteractorImpl`.
 */
data class OfferableCredential(
    /** The issuer's localized display name for it, falling back to the configuration id. */
    val name: String,
    val configurationId: String,
    val issuerUrl: String,
    val issuerOrder: Int,
    /** mdoc doctype or SD-JWT VC type, whichever the configuration declares. */
    val formatType: String?,
)

/** What the catalogue read produced, or why it produced nothing. */
sealed interface OfferableCredentialsResult {
    data class Success(val credentials: List<OfferableCredential>) : OfferableCredentialsResult
    data class Failure(val message: String) : OfferableCredentialsResult
}

/**
 * Reads what the configured issuers can issue — iOS's answer to Android's
 * `WalletCoreDocumentsController.getScopedDocuments`.
 *
 * Both go to the same place for the same data: each issuer's `.well-known/openid-credential-issuer` and
 * its `credential_configurations_supported`. Android gets there through wallet-core; this goes through
 * multipaz's `OpenID4VCI.getMetadata`, over the compatibility HTTP client — which matters, because
 * these issuers advertise logos on a host that does not resolve, and `openID4VciHttpClient` is what turns
 * those failures into tolerated 502s instead of a failed read.
 *
 * @param issuers usually [IosIssuerCatalog.issuers]; injectable so tests can point at a mock issuer.
 * @param engine the transport, injectable for the same reason. Defaults to Darwin via the compatibility
 *   engine.
 */
class IosOfferableCredentialsReader(
    private val issuers: List<IosVciIssuer> = IosIssuerCatalog.issuers,
    private val engine: HttpClientEngine? = null,
) {

    /**
     * @param locale a BCP-47 tag, ranked first when the issuer publishes several translations of a
     *   configuration's display name.
     */
    suspend fun read(locale: String): OfferableCredentialsResult {
        if (issuers.isEmpty()) {
            return OfferableCredentialsResult.Failure(message = "No issuers are configured.")
        }

        val httpClient = if (engine != null) openID4VciHttpClient(engine) else openID4VciHttpClient()
        // multipaz's provisioning code reaches its collaborators through a `BackendEnvironment` in the
        // coroutine context rather than constructor arguments; for a metadata read the only interface it
        // asks for is the HTTP client.
        val environment = object : BackendEnvironment {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> getInterface(clazz: KClass<T>): T? =
                if (clazz == HttpClient::class) httpClient as T else null
        }

        val credentials = mutableListOf<OfferableCredential>()
        var firstFailure: String? = null

        try {
            withContext(environment) {
                issuers.forEach { issuer ->
                    try {
                        credentials += issuer.offerableCredentials(httpClient, locale)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // 🚩 DELIBERATE DIVERGENCE from Android, which fails the whole list if any issuer
                        // cannot be reached. One unreachable issuer hiding every other issuer's documents
                        // is worse than showing what *is* available, and this list is offered, not
                        // authoritative. A read that reaches nobody still fails, below.
                        Logger.w(TAG, "issuer ${issuer.issuerUrl} could not be read: ${t.message}")
                        if (firstFailure == null) {
                            firstFailure = t.message ?: t::class.simpleName ?: "unknown error"
                        }
                    }
                }
            }
        } finally {
            httpClient.close()
        }

        return when {
            credentials.isNotEmpty() -> OfferableCredentialsResult.Success(credentials)
            else -> OfferableCredentialsResult.Failure(
                message = firstFailure ?: "No documents are offered."
            )
        }
    }

    private suspend fun IosVciIssuer.offerableCredentials(
        httpClient: HttpClient,
        locale: String,
    ): List<OfferableCredential> {
        val metadata = OpenID4VCI.getMetadata(
            issuerUrl = issuerUrl,
            httpClient = httpClient,
            clientPreferences = OpenID4VCIClientPreferences(
                clientId = clientId,
                redirectUrl = redirectUri,
                // The user's locale first, English second: an issuer that publishes neither gives
                // whatever it has, which multipaz ranks last rather than dropping.
                locales = if (locale.startsWith(FALLBACK_LOCALE)) {
                    listOf(FALLBACK_LOCALE)
                } else {
                    listOf(locale, FALLBACK_LOCALE)
                },
                signingAlgorithms = listOf(Algorithm.ESP256),
            ),
        )

        return metadata.credentials.map { (configurationId, credential) ->
            OfferableCredential(
                // multipaz falls back to an empty display text rather than the id, so do it here.
                name = credential.display.text.ifBlank { configurationId },
                configurationId = configurationId,
                issuerUrl = issuerUrl,
                issuerOrder = order,
                formatType = when (val format = credential.format) {
                    is CredentialFormat.Mdoc -> format.docType
                    is CredentialFormat.SdJwt -> format.vct
                },
            )
        }
    }

    private companion object {
        const val TAG = "IosOfferableCredentialsReader"
        const val FALLBACK_LOCALE = "en"
    }
}

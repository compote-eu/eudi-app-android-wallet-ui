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

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.backend.BackendEnvironment
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlinx.coroutines.withContext
import io.ktor.client.HttpClient

/**
 * A credential offer as this wallet needs to see it before showing it to anyone.
 *
 * @property configurationIds what the issuer is offering, in offer order.
 * @property txCodeLength how many characters the issuer's transaction code has, or null for none.
 * @property txCodeIsNumeric false when the issuer asks for free text — which this wallet cannot collect,
 *   a judgement made above rather than here.
 */
data class IosCredentialOffer(
    val offerUri: String,
    val issuerUrl: String,
    val configurationIds: List<String>,
    val txCodeLength: Int?,
    val txCodeIsNumeric: Boolean,
)

/** A resolved offer, with the display data the offer screen shows, or why it could not be resolved. */
sealed interface IosOfferResolution {

    data class Resolved(
        val offer: IosCredentialOffer,
        /** Localized names of the offered documents, in offer order. */
        val documentNames: List<String>,
        val issuerName: String?,
        val issuerLogoUri: String?,
        /** True when any offered configuration is a PID — the wallet's activation rule needs it. */
        val containsPid: Boolean,
    ) : IosOfferResolution

    data class Failure(val message: String) : IosOfferResolution
}

/**
 * Resolves an `openid-credential-offer://` link into something the offer screen can show.
 *
 * **The offer itself is parsed here rather than by multipaz, deliberately.** multipaz can parse one —
 * `CredentialOffer.parseCredentialOffer` does exactly this — but that class is `internal`, and reaching the
 * same information through its public API means `createClientFromOffer` plus
 * `getAuthorizationChallenges()`, which for a pre-authorized offer *without* a transaction code
 * immediately exchanges the code for a token. Resolution would then have spent the offer before the user
 * had seen it, and the later issuance would fail with a used code. So the offer's own fields — which are
 * fixed by the OpenID4VCI specification and small — are read directly, and multipaz is asked only for what
 * requires talking to the issuer: the display names.
 *
 * @param engine the transport, injectable for tests.
 */
class IosCredentialOfferReader(
    private val engine: HttpClientEngine? = null,
    private val issuers: List<IosVciIssuer> = IosIssuerCatalog.issuers,
) {

    suspend fun resolve(offerUri: String, locale: String): IosOfferResolution {
        val httpClient = if (engine != null) openID4VciHttpClient(engine) else openID4VciHttpClient()

        return try {
            val offer = parse(offerUri, httpClient)
            val metadata = readIssuerMetadata(offer.issuerUrl, locale, httpClient)

            val offered = offer.configurationIds.map { configurationId ->
                configurationId to metadata.credentials[configurationId]
            }
            // An offer naming a configuration the issuer does not advertise cannot be honoured: the
            // wallet would not know what it is agreeing to.
            val unknown = offered.filter { (_, credential) -> credential == null }.map { it.first }
            if (unknown.isNotEmpty()) {
                return IosOfferResolution.Failure(
                    "The issuer does not offer ${unknown.joinToString()}."
                )
            }

            IosOfferResolution.Resolved(
                offer = offer,
                documentNames = offered.map { (configurationId, credential) ->
                    credential!!.display.text.ifBlank { configurationId }
                },
                issuerName = metadata.display.text.ifBlank { null },
                issuerLogoUri = null, // multipaz hands back logo *bytes*; the screen wants a URI.
                containsPid = offered.any { (_, credential) ->
                    when (val format = credential!!.format) {
                        is CredentialFormat.Mdoc -> format.docType in PidFormatTypes
                        is CredentialFormat.SdJwt -> format.vct in PidFormatTypes
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            IosOfferResolution.Failure(
                t.message ?: t::class.simpleName ?: "The credential offer could not be read."
            )
        } finally {
            httpClient.close()
        }
    }

    /**
     * Reads the offer's own parameters, fetching `credential_offer_uri` when the link carries a reference
     * rather than the offer itself.
     */
    private suspend fun parse(offerUri: String, httpClient: HttpClient): IosCredentialOffer {
        val parameters = Url(offerUri).parameters
        val inline = parameters["credential_offer"]
        val offerJson = if (inline != null) {
            inline
        } else {
            val reference = parameters["credential_offer_uri"]
                ?: throw IllegalArgumentException(
                    "The link carries neither 'credential_offer' nor 'credential_offer_uri'."
                )
            val response = httpClient.get(reference)
            if (response.status != HttpStatusCode.OK) {
                throw IllegalStateException("The offer at $reference could not be fetched.")
            }
            response.bodyAsText()
        }

        val offer = Json.parseToJsonElement(offerJson).jsonObject
        val issuerUrl = offer["credential_issuer"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("The offer does not name a credential issuer.")
        val configurationIds = offer["credential_configuration_ids"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        require(configurationIds.isNotEmpty()) { "The offer names no document." }

        val txCode = offer["grants"]?.jsonObject
            ?.get(PRE_AUTHORIZED_CODE_GRANT)?.jsonObject
            ?.get("tx_code")?.jsonObject

        return IosCredentialOffer(
            offerUri = offerUri,
            issuerUrl = issuerUrl,
            configurationIds = configurationIds,
            txCodeLength = txCode?.get("length")?.jsonPrimitive?.intOrNull,
            // Per the specification the default is "numeric"; anything else is free text.
            txCodeIsNumeric = txCode == null ||
                    (txCode["input_mode"]?.jsonPrimitive?.contentOrNull ?: "numeric") == "numeric",
        )
    }

    /** The offering issuer's metadata, localized — the same read the add-document catalogue performs. */
    private suspend fun readIssuerMetadata(
        issuerUrl: String,
        locale: String,
        httpClient: HttpClient,
    ) = withContext(
        object : BackendEnvironment {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> getInterface(clazz: KClass<T>): T? =
                if (clazz == HttpClient::class) httpClient as T else null
        }
    ) {
        // An offer may name an issuer this build has never heard of, which is the point of offers. The
        // client identity then falls back to the wallet's own, as Android's `getVciManager(useDefault =
        // true)` does.
        val known = issuers.firstOrNull { it.issuerUrl == issuerUrl } ?: issuers.first()

        OpenID4VCI.getMetadata(
            issuerUrl = issuerUrl,
            httpClient = httpClient,
            clientPreferences = OpenID4VCIClientPreferences(
                clientId = known.clientId,
                redirectUrl = known.redirectUri,
                locales = if (locale.startsWith(FALLBACK_LOCALE)) {
                    listOf(FALLBACK_LOCALE)
                } else {
                    listOf(locale, FALLBACK_LOCALE)
                },
                signingAlgorithms = listOf(Algorithm.ESP256),
            ),
        )
    }

    private companion object {
        const val FALLBACK_LOCALE = "en"
        const val PRE_AUTHORIZED_CODE_GRANT = "urn:ietf:params:oauth:grant-type:pre-authorized_code"
    }
}

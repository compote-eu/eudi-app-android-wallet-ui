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

import io.ktor.http.Url
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.X509Cert
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url

/**
 * A request object refused because the verifier it names is not proven by the certificate that signed it.
 *
 * The message is what the user sees — [IosRemotePresenter] shows a failure's message — so it is a sentence, and
 * the specific [reason] goes to the log instead.
 */
internal class ClientIdBindingException(val reason: String) : IllegalStateException(REFUSED)

/**
 * Refuses a request object whose `client_id` its signing certificate does not prove.
 *
 * OpenID4VP 1.0 §5.9.3 makes this the wallet's job, and multipaz does not do it: 0.99 verifies the signature
 * against `x5c[0]` and never compares that certificate with `client_id` (nor does its `main` as of 2026-09-30,
 * which only carries the two side by side). The rules here are Android's, from openid4vp-kt 0.15.1, so both
 * platforms refuse the same requests:
 * - the link's `client_id` is present and identical to the request object's, prefix included;
 * - the prefix is `x509_san_dns` or `x509_hash` — the two `WalletCoreConfigImpl` enables, in both flavours;
 * - `x509_san_dns`: the identifier is one of the certificate's DNS names, and the `response_uri` is on that host;
 * - `x509_hash`: the identifier is the base64url SHA-256 of the certificate's DER encoding.
 *
 * What the consent screen names was never at risk: it comes from the certificate, not from `client_id`. What this
 * protects is what the response is bound to — `client_id` is the audience of the presentation, in the mdoc
 * session transcript and the SD-JWT key-binding `aud`.
 */
internal suspend fun checkClientIdBinding(linkClientId: String?, requestObject: JsonObject, signer: X509Cert?) {
    val clientId = requestObject["client_id"]?.jsonPrimitive?.contentOrNull
        ?: refuse("the request object names no client_id")
    if (linkClientId == null) refuse("the link names no client_id")
    if (linkClientId != clientId) refuse("the link names $linkClientId, the request object $clientId")
    if (signer == null) refuse("the request object carries no signing certificate")

    val identifier = clientId.substringAfter(':')
    when (val prefix = clientId.substringBefore(':', missingDelimiterValue = "")) {
        X509_SAN_DNS -> {
            if (identifier !in signer.dnsNames()) refuse("$identifier is not a DNS name of the signing certificate")
            val responseUri = requestObject["response_uri"]?.jsonPrimitive?.contentOrNull
            if (responseUri != null) {
                val host = runCatching { Url(responseUri).host }.getOrNull()
                if (host != identifier) refuse("the response_uri is on $host, not on $identifier")
            }
        }
        X509_HASH -> if (identifier != signer.sha256()) refuse("$identifier is not the hash of the signing certificate")
        else -> refuse("the client_id prefix '$prefix' is not accepted")
    }
}

/** The `client_id` the verifier's link carries, which the signed request object must repeat. */
internal fun linkClientIdOf(uri: String): String? =
    uri.substringAfter('?', missingDelimiterValue = "").parseUrlEncodedParameters()["client_id"]

private fun refuse(reason: String): Nothing {
    Logger.w(TAG, "refusing the request object: $reason")
    throw ClientIdBindingException(reason)
}

/** The certificate's `dNSName` subject alternative names; a URI or email entry is not a DNS name. */
private fun X509Cert.dnsNames(): List<String> = runCatching {
    val names = getExtensionValue(SUBJECT_ALT_NAME)?.let { ASN1.decode(it) } as? ASN1Sequence
    names?.elements.orEmpty()
        .filterIsInstance<ASN1TaggedObject>()
        .filter { it.cls == ASN1TagClass.CONTEXT_SPECIFIC && it.tag == DNS_NAME_TAG }
        .map { it.content.decodeToString() }
}.getOrDefault(emptyList())

private suspend fun X509Cert.sha256(): String = Crypto.digest(Algorithm.SHA256, encoded.toByteArray()).toBase64Url()

private const val TAG = "ClientIdBinding"
private const val X509_SAN_DNS = "x509_san_dns"
private const val X509_HASH = "x509_hash"
private const val SUBJECT_ALT_NAME = "2.5.29.17"

/** `dNSName` is `[2] IA5String` in RFC 5280's `GeneralName`. */
private const val DNS_NAME_TAG = 2

private const val REFUSED =
    "This request could not be verified: the verifier it names is not proven by the certificate that signed it."

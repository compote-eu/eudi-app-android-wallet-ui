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

import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import eu.europa.ec.shared.wallet.trust.IssuerTrustSource
import eu.europa.ec.shared.wallet.trust.TrustVerdict
import eu.europa.ec.shared.wallet.trust.toTrustChain
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.multipaz.cbor.Cbor
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.sdjwt.SdJwt
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url

/**
 * The issuer is not one the EU trust lists vouch for, for what it was doing.
 *
 * Typed so the screens can tell it from every other failure, as Android does with wallet-core's
 * `IssuerNotTrustedException`: adding a document shows the "issuer not trusted" sheet rather than a
 * generic error. An [IllegalStateException] because that is what both refusals threw before they had a
 * type of their own, so nothing that caught them changes.
 */
internal class IssuerNotTrustedException(message: String) : IllegalStateException(message)

/** True when this failure, or anything that caused it, is an [IssuerNotTrustedException]. */
internal fun Throwable.isIssuerNotTrusted(): Boolean =
    generateSequence(this) { it.cause }.any { it is IssuerNotTrustedException }

/**
 * Refuses PID credentials whose signer the EU list of PID providers does not name.
 *
 * Android's `configureIssuerTrust { policy { default(INFORM); forContext(PID, ENFORCE) } }`:
 * wallet-core's `evaluateIssuerTrust` checks what the issuer returned before the document is stored,
 * and under `ENFORCE` it **fails closed** — no certificate chain, no verdict, and a definite "not
 * trusted" all refuse the document. Everything other than a PID stays `INFORM` there, which here means
 * not asking: nothing would act on the answer.
 *
 * ⚠️ So an undetermined verdict refuses, the opposite of the metadata check in
 * [OpenID4VciCompatibilityEngine], which lets one through. That is Android's line too: its metadata
 * check is openid4vci's, outside the trust policy, and only the PID context is enforced.
 *
 * A credential counts as a PID when the configuration asked for one ([requestedPid]) **or** its own
 * contents say so — an mdoc's MSO `docType`, an SD-JWT VC's `vct`. Android classifies by the request
 * alone; by contents alone, an issuer could fill a PID document with a credential typed as something
 * else and never be asked. A credential that cannot be read is judged on the request alone.
 *
 * Each distinct chain is asked about once: a batch of sixty shares one document signer.
 *
 * @param credentials the credentials as the issuer sent them: an mdoc base64url-encoded, an SD-JWT VC
 *   as its compact serialization.
 * @throws IssuerNotTrustedException when any PID among them is not signed by a recognised PID provider.
 */
internal suspend fun IssuerTrustSource.enforcePidSigners(credentials: List<String>, requestedPid: Boolean) {
    val pidChains = credentials
        .map { signerOf(it) }
        .filter { signer -> requestedPid || signer?.formatType in PidFormatTypes }
        .map { signer -> signer?.chain.orEmpty() }
        .distinct()
    pidChains.forEach { chain ->
        if (chain.isEmpty()) {
            throw IssuerNotTrustedException("a PID arrived with no certificate chain to check its signer against")
        }
        val verdict = verdict(chain.toTrustChain(), VerificationContext.PID)
        if (verdict != TrustVerdict.TRUSTED) {
            Logger.w(TAG, "refusing a PID: its signer ${chain.first().subject.name} is $verdict")
            throw IssuerNotTrustedException("the signer of this PID is not a recognised PID provider ($verdict)")
        }
        Logger.i(TAG, "the signer of this PID is on the EU list of PID providers")
    }
}

/** What a credential says it is, and the chain its issuer signed it with. */
internal class CredentialSigner(val formatType: String?, val chain: List<X509Cert>)

/**
 * Reads [credential]'s type and signer chain, or null when it is neither an mdoc nor an SD-JWT VC this
 * can parse.
 *
 * Told apart by shape: an SD-JWT VC is a JWS and so contains a `.`, which the base64url alphabet an mdoc
 * is sent in does not have.
 */
internal suspend fun signerOf(credential: String): CredentialSigner? = runCatching {
    if ('.' in credential) {
        val sdJwt = SdJwt.fromCompactSerialization(credential)
        CredentialSigner(formatType = sdJwt.credentialType, chain = sdJwt.x5c?.certificates.orEmpty())
    } else {
        val issuerAuth = Cbor.decode(credential.fromBase64Url())["issuerAuth"].asCoseSign1
        val mso = MobileSecurityObject.fromDataItem(Cbor.decode(Cbor.decode(issuerAuth.payload!!).asTagged.asBstr))
        val chain = issuerAuth.unprotectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]?.asX509CertChain
        CredentialSigner(formatType = mso.docType, chain = chain?.certificates.orEmpty())
    }
}.getOrNull()

/**
 * Which credential configurations each issuer publishes as a PID, keyed by its credential endpoint.
 *
 * Process-wide for the reason [CredentialEncryptionRegistry] is: multipaz caches issuer metadata for the
 * whole process, and in [IosCredentialIssuer]'s batch path the session reads it while each document
 * posts through a client of its own, so the client that sees the credential response may never have
 * read the metadata. Whatever did read it went through [OpenID4VciCompatibilityEngine].
 */
internal object PidConfigurationRegistry {
    private val lock = Mutex()
    private val byEndpoint = mutableMapOf<String, Set<String>>()

    /** Records the PID configurations [metadata] publishes; nothing, if it names no credential endpoint. */
    suspend fun record(metadata: JsonObject) {
        val endpoint = (metadata["credential_endpoint"] as? JsonPrimitive)?.contentOrNull ?: return
        val configurations = metadata["credential_configurations_supported"] as? JsonObject ?: return
        val pids = configurations.filterValues { configuration ->
            // `doctype` for an mdoc, `vct` for an SD-JWT VC, both at the top of the configuration.
            listOf("doctype", "vct").any { member ->
                ((configuration as? JsonObject)?.get(member) as? JsonPrimitive)?.contentOrNull in PidFormatTypes
            }
        }.keys
        lock.withLock { byEndpoint[endpoint] = pids }
    }

    /** Whether the issuer behind [endpoint] publishes [configurationId] as a PID. */
    suspend fun isPid(endpoint: String, configurationId: String): Boolean =
        lock.withLock { byEndpoint[endpoint] }?.contains(configurationId) == true
}

private const val TAG = "PidIssuerTrust"

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
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.sdjwt.SdJwt
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import kotlin.coroutines.cancellation.CancellationException

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
 * ⛔ The chain a credential carries proves nothing until the credential's signature verifies with its
 * first certificate: a provider's chain is public, so anyone can paste it into a credential they signed
 * themselves. So every PID's signature is checked against its own chain **before** the list is asked,
 * and one that does not verify is refused without asking. Android does the same — wallet-core's
 * `MsoMdocCredentialTrustVerifier` runs `coseSign1Check` with the leaf key, its SD-JWT VC verifier checks
 * the JWS — and multipaz's `certify` checks nothing, so here is the only place it can happen.
 *
 * Each credential's signature is checked, but each distinct chain is asked about once: a batch of sixty
 * shares one document signer, and sixty signatures.
 *
 * @param credentials the credentials as the issuer sent them: an mdoc base64url-encoded, an SD-JWT VC
 *   as its compact serialization.
 * @throws IssuerNotTrustedException when any PID among them is not signed by a recognised PID provider.
 */
internal suspend fun IssuerTrustSource.enforcePidSigners(credentials: List<String>, requestedPid: Boolean) {
    val pidSigners = credentials
        .map { signerOf(it) }
        .filter { signer -> requestedPid || signer?.formatType in PidFormatTypes }
    pidSigners.forEach { signer ->
        val leaf = signer?.chain?.firstOrNull()
            ?: throw IssuerNotTrustedException("a PID arrived with no certificate chain to check its signer against")
        if (!signer.isSignedBy(leaf)) {
            Logger.w(TAG, "refusing a PID: it is not signed by the chain it carries, whose first certificate is ${leaf.subject.name}")
            throw IssuerNotTrustedException("this PID is not signed by the certificate chain it carries")
        }
    }
    pidSigners.mapNotNull { it?.chain }.distinct().forEach { chain ->
        val verdict = verdict(chain.toTrustChain(), VerificationContext.PID)
        if (verdict != TrustVerdict.TRUSTED) {
            Logger.w(TAG, "refusing a PID: its signer ${chain.first().subject.name} is $verdict")
            throw IssuerNotTrustedException("the signer of this PID is not a recognised PID provider ($verdict)")
        }
        Logger.i(TAG, "the signer of this PID is on the EU list of PID providers")
    }
}

/**
 * What a credential says it is, the chain it says its issuer signed it with, and the check of whether
 * that issuer really did.
 *
 * @param signature throws unless the credential's issuer signature verifies with the key it is given.
 */
internal class CredentialSigner(
    val formatType: String?,
    val chain: List<X509Cert>,
    private val signature: suspend (EcPublicKey) -> Unit,
) {
    /** Whether the credential's issuer signature verifies with [certificate]'s public key. */
    suspend fun isSignedBy(certificate: X509Cert): Boolean = isSignedBy(certificate, signature)
}

/**
 * Whether [check] passes with [certificate]'s public key.
 *
 * Every failure is the same answer: a signature made by another key, a certificate whose key is not an
 * EC key (multipaz verifies no other kind, and no EU issuer measured signs with one), a signature that
 * will not even parse. None of them shows that the certificate's owner signed anything.
 */
internal suspend fun isSignedBy(certificate: X509Cert, check: suspend (EcPublicKey) -> Unit): Boolean =
    try {
        check(certificate.ecPublicKey)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.w(TAG, "a signature did not verify with the key of ${certificate.subject.name}: ${t.message}")
        false
    }

/**
 * Reads [credential]'s type and signer chain, or null when it is neither an mdoc nor an SD-JWT VC this
 * can parse.
 *
 * Told apart by shape: an SD-JWT VC is a JWS and so contains a `.`, which the base64url alphabet an mdoc
 * is sent in does not have.
 *
 * The signature checked is the issuer's alone: for an mdoc the `issuerAuth` COSE_Sign1 over the MSO,
 * for an SD-JWT VC the JWS before the first `~`. The digests they sign are what bind the claims, and
 * whoever presents them checks those.
 */
internal suspend fun signerOf(credential: String): CredentialSigner? = runCatching {
    if ('.' in credential) {
        val sdJwt = SdJwt.fromCompactSerialization(credential)
        CredentialSigner(
            formatType = sdJwt.credentialType,
            chain = sdJwt.x5c?.certificates.orEmpty(),
            signature = { key -> JsonWebSignature.verify(credential.substringBefore('~'), key) },
        )
    } else {
        val issuerAuth = Cbor.decode(credential.fromBase64Url())["issuerAuth"].asCoseSign1
        val mso = MobileSecurityObject.fromDataItem(Cbor.decode(Cbor.decode(issuerAuth.payload!!).asTagged.asBstr))
        val chain = issuerAuth.unprotectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]?.asX509CertChain
        CredentialSigner(
            formatType = mso.docType,
            chain = chain?.certificates.orEmpty(),
            signature = { key ->
                val algorithm = issuerAuth.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_ALG)]?.asNumber?.toInt()
                    ?: throw IllegalArgumentException("no algorithm in the protected headers")
                Cose.coseSign1Check(
                    publicKey = key,
                    detachedData = null,
                    signature = issuerAuth,
                    signatureAlgorithm = Algorithm.fromCoseAlgorithmIdentifier(algorithm),
                )
            },
        )
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

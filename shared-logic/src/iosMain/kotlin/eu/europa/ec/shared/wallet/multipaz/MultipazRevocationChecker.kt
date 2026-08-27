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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import eu.europa.ec.shared.wallet.revocation.DocumentStatusDomain
import eu.europa.ec.shared.wallet.revocation.StatusSignerTrustDomain
import kotlinx.serialization.json.jsonObject
import org.multipaz.revocation.RevocationStatus
import org.multipaz.revocation.StatusList
import org.multipaz.webtoken.WebTokenCheck
import org.multipaz.webtoken.basicCertificateChainValidator
import org.multipaz.webtoken.validateJwt
import kotlin.time.Duration.Companion.days

/**
 * What a status check concluded about one credential.
 *
 * Mirrors the three outcomes the Android side gets from wallet-core's `resolveStatus` — Valid,
 * Invalid, Suspended — plus [Unknown] for the cases where no answer could be obtained. The wallet
 * treats Invalid and Suspended alike (both mean "flag this document"), matching
 * `RevocationWorkManager`, but they are kept apart here because the *reason* differs and a future
 * suspended-vs-revoked distinction in the UI should not need this file changed.
 *
 * Public because `IosWalletEngine.refreshRevocationStatuses` reports it per document: a refresh that
 * could conclude nothing is very different from one that found everything valid, and a host with no
 * way to tell those apart cannot log anything useful.
 */
sealed interface RevocationOutcome {

    /**
     * Whether the status list's signer was **anchored** — bound to a key the issuer itself vouched
     * for — rather than merely presenting a self-consistent certificate chain.
     *
     * `true` only when the credential named the signer (ISO mdoc's `status_list` `certificate`
     * field), which is evidence the issuer signed. `false` means the list's `x5c` chain verified
     * *internally* — signatures link, subject/issuer match, key usage and basic constraints are
     * right, nothing expired — but nothing ties its root to a party this wallet trusts, because iOS
     * has no trust anchors (see the class comment).
     *
     * **An unanchored reading informs; it never decides.** Callers must not change persisted state
     * on one, in either direction. That is Android's `TrustPolicy.Action.INFORM` for the document
     * status resolver, expressed in the type rather than in a convention.
     */
    val signerAnchored: Boolean

    data class Valid(override val signerAnchored: Boolean) : RevocationOutcome
    data class Invalid(override val signerAnchored: Boolean) : RevocationOutcome
    data class Suspended(override val signerAnchored: Boolean) : RevocationOutcome

    /**
     * No conclusion. Carries [reason] for the log, because every cause here is operational — the
     * credential carries no status, the list could not be fetched, its signature did not verify, or
     * the format is one this checker does not implement.
     */
    data class Unknown(val reason: String) : RevocationOutcome {
        /** Nothing was read, so there is nothing to have anchored. */
        override val signerAnchored: Boolean get() = false
    }

    /** Whether the wallet should flag the document, i.e. show it as revoked. */
    val isRevoked: Boolean get() = this is Invalid || this is Suspended
}

/** The platform-neutral half of this outcome, for the shared revocation policy. */
internal fun RevocationOutcome.toDocumentStatusDomain(): DocumentStatusDomain = when (this) {
    is RevocationOutcome.Valid -> DocumentStatusDomain.Valid
    is RevocationOutcome.Invalid -> DocumentStatusDomain.Invalid
    is RevocationOutcome.Suspended -> DocumentStatusDomain.Suspended
    is RevocationOutcome.Unknown -> DocumentStatusDomain.Unknown
}

/**
 * How well this reading's signer was established, in the shared vocabulary.
 *
 * An unanchored reading maps to [StatusSignerTrustDomain.NoAnchorsAvailable] rather than
 * [StatusSignerTrustDomain.NotTrusted], and the distinction is wallet-core's own: nothing failed a
 * check here, there was simply no anchor to check against. iOS cannot currently produce `NotTrusted`
 * at all, because reaching it would require anchors to fail against.
 */
internal fun RevocationOutcome.toSignerTrustDomain(): StatusSignerTrustDomain = when {
    this is RevocationOutcome.Unknown -> StatusSignerTrustDomain.NotEvaluated
    signerAnchored -> StatusSignerTrustDomain.Trusted
    else -> StatusSignerTrustDomain.NoAnchorsAvailable
}

/**
 * Resolves a credential's revocation status against its status list, per the IETF
 * [Token Status List](https://datatracker.ietf.org/doc/draft-ietf-oauth-status-list/) draft.
 *
 * **Why this exists rather than `eudi-lib-kmp-statium`:** that library — which is what wallet-core
 * uses on Android, inside `resolveStatus` — publishes only `androidJvm` and `jvm` variants. Checked
 * against its Gradle module metadata on Maven Central rather than the local cache: at 0.5.1 and
 * 0.4.1 there is no Kotlin/Native variant at all, so it cannot be consumed from `iosMain`. Multipaz,
 * already this platform's document layer, implements the same draft in **commonMain**
 * (`org.multipaz.revocation`), so the work here is fetching the token and asking multipaz for the
 * bit — not a second implementation of the spec.
 *
 * The consequence worth stating: the two platforms resolve status through two different libraries.
 * That is the same shape as everything else in this port — each platform's document engine answers
 * for itself behind the `WalletEngine` seam — and the *decisions* (which statuses count as revoked,
 * when to re-check, what to cache) live above it in shared code.
 *
 * ## Trust anchoring, and why an outcome carries [RevocationOutcome.signerAnchored]
 *
 * A status list is a signed token, so believing it means deciding whose signature counts. There are
 * two sources here and iOS has only one of them:
 *
 *  - the credential may **name the signer** in its `status_list` `certificate` field. That arrives
 *    inside data the issuer signed, so it is the strongest binding available, and it anchors the
 *    reading. Neither EU dev issuer populates it.
 *  - otherwise the token's own `x5c` chain is all there is. It can be validated *structurally*, and
 *    the token's signature checked against that chain's leaf, but nothing ties the chain to a party
 *    this wallet trusts: **iOS has no trust anchors at all.** Android's come from the ETSI LoTE
 *    trusted lists, whose iOS verifier is still `InsecureAcceptAllJwtSignature`
 *    (eudi-lib-kmp-etsi-1196x2#130), and this repository ships no wallet trust store of its own.
 *
 * So the second case is read and reported with `signerAnchored = false`, and callers must not act on
 * it. That is deliberately Android's posture for this specific check — its
 * `configureDocumentStatusResolver` uses `TrustPolicy.Action.INFORM`, not `ENFORCE` — rather than a
 * shortcut taken because anchors are missing. The alternative, which this class did until the INFORM
 * change, was to refuse such lists outright and report nothing at all.
 */
internal class MultipazRevocationChecker(
    private val httpClient: HttpClient,
) {

    /**
     * Fetches [status]'s list and returns the outcome for its index.
     *
     * Never throws: a status check is a background health task, and a failed one must not take down
     * the caller's loop over the other documents. Everything that can go wrong becomes
     * [RevocationOutcome.Unknown], which the caller treats as "leave this document as it was" — the
     * same thing the Android worker does with a failed `resolveStatus` (`onFailure = {}`).
     */
    suspend fun check(status: RevocationStatus?): RevocationOutcome = when (status) {
        null -> RevocationOutcome.Unknown("credential carries no revocation status")

        is RevocationStatus.StatusList -> checkStatusList(status)

        // ISO/IEC 18013-5 §12.3.6.4. Not implemented: no EUDI issuer observed using it, and it is a
        // different fetch-and-match shape (a CWT of identifiers, not a bit index). multipaz has
        // `IdentifierList` ready for whoever needs it.
        is RevocationStatus.IdentifierList ->
            RevocationOutcome.Unknown("identifier lists are not supported yet")

        // The credential *has* a status entry that multipaz could not parse. Per multipaz's own
        // note this means "unrecognised", not "invalid", so the document is left alone.
        is RevocationStatus.Unknown ->
            RevocationOutcome.Unknown("unrecognised revocation status format")
    }

    private suspend fun checkStatusList(status: RevocationStatus.StatusList): RevocationOutcome {
        val token = fetch(status.uri) ?: return RevocationOutcome.Unknown(
            "status list at ${status.uri} could not be fetched"
        )

        // ISO mdoc's `status_list` `certificate` field. When the issuer put a key there it is the
        // strongest binding available, because it arrives inside data the issuer signed.
        val signerKey = status.certificate?.ecPublicKey

        val list = try {
            if (signerKey != null) {
                StatusList.fromJwt(jwt = token, publicKey = signerKey)
            } else {
                readWithoutAnchor(token)
            }
        } catch (t: Throwable) {
            return RevocationOutcome.Unknown(
                "status list at ${status.uri} was rejected: ${t.message ?: t::class.simpleName}"
            )
        }

        return statusAt(list, status.idx, signerAnchored = signerKey != null)
    }

    /**
     * Reads a status list whose only signer evidence is its own `x5c` chain — which is what both EU
     * dev issuers publish, neither naming a `certificate` in the credential.
     *
     * **Why this is not a signature bypass.** `basicCertificateChainValidator` performs the real
     * checks — `Crypto.validateCertChainSignatures` over the chain, subject/issuer linkage,
     * `KEY_CERT_SIGN` key usage, basic constraints and validity at the given instant — and throws if
     * any of them fail. `validateJwt` then verifies the token's own signature against the **leaf** of
     * that chain, so a list signed by a key its `x5c` does not certify is still refused. What none of
     * it can do is tie the chain to a party this wallet trusts, so the validator returns `false` —
     * "valid, but no trust is established" — and `require(publicKey != null || caValidated)` refuses
     * the token outright. Returning `true` instead accepts a structurally sound list while the caller
     * records that nothing anchored it.
     *
     * Note that a JWT's `x5c` is emitted with the **root excluded** (multipaz's `buildJwt` calls
     * `toX5c(excludeRoot = true)`), so the transmitted chain never contains the root anyway. Anchoring
     * could only ever come from outside the token — which is exactly what iOS lacks.
     *
     * That is deliberately the posture Android takes: its
     * `configureDocumentStatusResolver { configureTrust { policy { default(INFORM) } } }` informs
     * rather than enforces. Reading the status and marking it unanchored is parity with Android, not
     * a weakening of it — and it is strictly more informative than the previous behaviour, which
     * refused and reported nothing.
     *
     * ⚠️ This replicates `CompressedStatusList.fromJwt`, which is the only way to reach
     * `certificateChainValidator`: `StatusList.fromJwt` does not expose it. Both constants below must
     * stay in step with that function — in particular **[STATUS_LIST_MAX_VALIDITY], because
     * `validateJwt`'s own default is 10 hours** and every status list older than that would fail.
     */
    private suspend fun readWithoutAnchor(token: String): StatusList {
        val body = validateJwt(
            jwt = token,
            jwtName = "Status List",
            checks = mapOf(WebTokenCheck.TYP to STATUS_LIST_TYP),
            maxValidity = STATUS_LIST_MAX_VALIDITY,
            certificateChainValidator = { chain, atTime ->
                basicCertificateChainValidator(chain, atTime)
                true
            },
        )
        val statusList = body[STATUS_LIST_CLAIM]?.jsonObject
            ?: throw IllegalArgumentException("missing required '$STATUS_LIST_CLAIM' claim")
        return StatusList.fromJson(statusList)
    }

    /**
     * Note on out-of-range indices: multipaz's `StatusList[idx]` returns **0** for an index past the
     * end of the list rather than failing, so a credential pointing beyond its list reads as valid.
     * That is the library's choice and it cannot be detected from here — the backing size is private —
     * so it is documented rather than papered over with a guard that would never fire.
     */
    private fun statusAt(list: StatusList, idx: Int, signerAnchored: Boolean): RevocationOutcome =
        when (val value = list[idx]) {
            STATUS_VALID -> RevocationOutcome.Valid(signerAnchored)
            STATUS_INVALID -> RevocationOutcome.Invalid(signerAnchored)
            STATUS_SUSPENDED -> RevocationOutcome.Suspended(signerAnchored)
            // 0x03 and 0x0B..0x0F are reserved for application-specific use by the draft; anything
            // else is not something this wallet can interpret.
            else -> RevocationOutcome.Unknown("unrecognised status value $value at index $idx")
        }

    private suspend fun fetch(uri: String): String? = try {
        val response = httpClient.get(uri)
        if (response.status.isSuccess()) response.bodyAsText() else null
    } catch (_: Throwable) {
        // Offline, DNS failure, TLS refusal — all the same to the caller.
        null
    }

    private companion object {
        // draft-ietf-oauth-status-list §7.1.
        const val STATUS_VALID = 0x00
        const val STATUS_INVALID = 0x01
        const val STATUS_SUSPENDED = 0x02

        // Kept in step with multipaz's `CompressedStatusList.fromJwt`, which `readWithoutAnchor`
        // replicates in order to reach `certificateChainValidator`.
        const val STATUS_LIST_TYP = "statuslist+jwt"
        const val STATUS_LIST_CLAIM = "status_list"
        val STATUS_LIST_MAX_VALIDITY = 365.days
    }
}

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
import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import eu.europa.ec.shared.wallet.revocation.StatusSignerTrustDomain
import eu.europa.ec.shared.wallet.trust.IosEtsiTrust
import eu.europa.ec.shared.wallet.trust.IssuerTrustSource
import eu.europa.ec.shared.wallet.trust.TrustVerdict
import eu.europa.ec.shared.wallet.trust.toTrustChain
import org.multipaz.crypto.X509CertChain
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
     * How well this reading's signer was established.
     *
     * Three of the four values are reachable here, and the difference between them is the whole
     * point of the type:
     *  - [StatusSignerTrustDomain.Trusted] — either the credential named the signer (ISO mdoc's
     *    `status_list` `certificate` field, which is evidence the issuer signed) **or** the list's
     *    `x5c` chain validated against the EU trusted lists for this credential's status context.
     *  - [StatusSignerTrustDomain.NotTrusted] — anchors existed for that context and this chain
     *    failed against them. A decided refusal, not an absence of evidence.
     *  - [StatusSignerTrustDomain.NoAnchorsAvailable] — no list classifies this credential's status,
     *    or the lists could not be consulted. The chain verified *internally* — signatures link,
     *    subject/issuer match, key usage and basic constraints are right, nothing expired — but
     *    nothing ties it to a party this wallet trusts.
     *
     * **What each of them may decide is not this file's business** — that is
     * [eu.europa.ec.shared.wallet.revocation.revocationAction], shared with Android, which under
     * `Inform` acts on all three and under `Enforce` acts only on `Trusted`.
     *
     * ⚠️ This was a `Boolean` until anchoring existed, and a boolean could not express the middle
     * case: "we had anchors and this chain failed" read identically to "we had no anchors".
     */
    val signerTrust: StatusSignerTrustDomain

    data class Valid(override val signerTrust: StatusSignerTrustDomain) : RevocationOutcome
    data class Invalid(override val signerTrust: StatusSignerTrustDomain) : RevocationOutcome
    data class Suspended(override val signerTrust: StatusSignerTrustDomain) : RevocationOutcome

    /**
     * No conclusion. Carries [reason] for the log, because every cause here is operational — the
     * credential carries no status, the list could not be fetched, its signature did not verify, or
     * the format is one this checker does not implement.
     */
    data class Unknown(val reason: String) : RevocationOutcome {
        /** Nothing was read, so no signer was evaluated. */
        override val signerTrust: StatusSignerTrustDomain
            get() = StatusSignerTrustDomain.NotEvaluated
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
 * ## Trust anchoring, and why an outcome carries [RevocationOutcome.signerTrust]
 *
 * A status list is a signed token, so believing it means deciding whose signature counts. There are
 * two sources, and **iOS now has both**:
 *
 *  - the credential may **name the signer** in its `status_list` `certificate` field. That arrives
 *    inside data the issuer signed, so it is the strongest binding available and it anchors the
 *    reading outright. Neither EU dev issuer populates it.
 *  - otherwise the token's own `x5c` chain is what there is, and it is checked against the **EU
 *    trusted lists** for this credential's status context — see [readAgainstTrustedLists]. Before
 *    trust shipped this branch could only validate the chain *structurally* and report that nothing
 *    anchored it; it now reaches a real verdict for the credential types the lists classify.
 *
 * ⚠️ **Only PID status lists can be anchored, and that is parity rather than a shortcut.** Android
 * classifies exactly the two PID identifiers (`AttestationClassifications(pids = ...)`) and nothing
 * else, so an mDL's or a PubEAA's status signer has no list to be checked against on either
 * platform. Those keep reporting [StatusSignerTrustDomain.NoAnchorsAvailable].
 *
 * 📌 **Anchoring did not change the policy, only the honesty of its input.** The default stays
 * `INFORM`, matching Android's `configureDocumentStatusResolver` and the official iOS wallet's
 * `.warning`; what changed is that the trust value fed to the shared `revocationAction` is now
 * measured instead of always being "no anchors", and the log line says something true. `ENFORCE` is
 * finally *meaningful* for a PID — it would act on a trusted signer and leave everything else alone
 * — but choosing it would still invent a posture stricter than either reference wallet.
 */
internal class MultipazRevocationChecker(
    private val httpClient: HttpClient,
    /**
     * Who may sign a status list, per the EU trusted lists. Null answers "no anchors" without asking
     * anyone — which is what the tests want, since a real check would make them pass or fail with
     * the network.
     */
    private val issuerTrust: IssuerTrustSource? = IosEtsiTrust(),
) {

    /**
     * Fetches [status]'s list and returns the outcome for its index.
     *
     * Never throws: a status check is a background health task, and a failed one must not take down
     * the caller's loop over the other documents. Everything that can go wrong becomes
     * [RevocationOutcome.Unknown], which the caller treats as "leave this document as it was" — the
     * same thing the Android worker does with a failed `resolveStatus` (`onFailure = {}`).
     */
    suspend fun check(status: RevocationStatus?, formatType: String?): RevocationOutcome =
        when (status) {
        null -> RevocationOutcome.Unknown("credential carries no revocation status")

        is RevocationStatus.StatusList -> checkStatusList(status, formatType)

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

    private suspend fun checkStatusList(
        status: RevocationStatus.StatusList,
        formatType: String?,
    ): RevocationOutcome {
        val token = fetch(status.uri) ?: return RevocationOutcome.Unknown(
            "status list at ${status.uri} could not be fetched"
        )

        // ISO mdoc's `status_list` `certificate` field. When the issuer put a key there it is the
        // strongest binding available, because it arrives inside data the issuer signed.
        val signerKey = status.certificate?.ecPublicKey

        val read = try {
            if (signerKey != null) {
                // `require(publicKey == null || certificateChainValidator == null)` in `validateJwt`:
                // the two are mutually exclusive, so this branch cannot also consult the lists.
                // It does not need to — a key the issuer itself named is the stronger binding.
                ReadList(
                    list = StatusList.fromJwt(jwt = token, publicKey = signerKey),
                    signerTrust = StatusSignerTrustDomain.Trusted,
                )
            } else {
                readAgainstTrustedLists(token, statusContextFor(formatType))
            }
        } catch (t: Throwable) {
            return RevocationOutcome.Unknown(
                "status list at ${status.uri} was rejected: ${t.message ?: t::class.simpleName}"
            )
        }

        return statusAt(read.list, status.idx, signerTrust = read.signerTrust)
    }

    /** A list, and how well the signer behind it was established. */
    private data class ReadList(val list: StatusList, val signerTrust: StatusSignerTrustDomain)

    /**
     * Which trusted list, if any, says who may sign this credential's status list.
     *
     * **Mirrors Android's `classifications(AttestationClassifications(pids = ...))`**, which names
     * exactly the two PID identifiers in [PidFormatTypes] and classifies nothing else. So a PID's
     * status list can be anchored and an mDL's or a PubEAA's cannot — not a shortcut, but the same
     * coverage Android has.
     *
     * 📌 Confirmed against the live lists rather than assumed: a chain checked for `PIDStatus` comes
     * back `TRUSTED`, and the same chain for `PubEAAStatus` comes back undetermined with
     * *"no trust list covers PubEAAStatus"*. Returning null for those avoids asking a question whose
     * answer could only ever be "don't know".
     */
    private fun statusContextFor(formatType: String?): VerificationContext? =
        if (formatType in PidFormatTypes) VerificationContext.PIDStatus else null

    /**
     * Reads a status list whose only signer evidence is its own `x5c` chain — which is what both EU
     * dev issuers publish, neither naming a `certificate` in the credential.
     *
     * **Why returning `true` is not a signature bypass.** `basicCertificateChainValidator` performs
     * the real checks — `Crypto.validateCertChainSignatures` over the chain, subject/issuer linkage,
     * `KEY_CERT_SIGN` key usage, basic constraints and validity at the given instant — and throws if
     * any of them fail. `validateJwt` then verifies the token's own signature against the **leaf** of
     * that chain, so a list signed by a key its `x5c` does not certify is still refused. multipaz's
     * own validator returns `false` after those checks — "valid, but no trust is established" — which
     * makes `require(publicKey != null || caValidated)` refuse the token. Returning `true` instead
     * accepts a structurally sound list and reports the trust verdict separately, which is what lets
     * "untrusted signer" stay distinguishable from "unreadable list".
     *
     * 📌 **Where the anchor comes from.** A JWT's `x5c` is emitted with the **root excluded**
     * (multipaz's `buildJwt` calls `toX5c(excludeRoot = true)`), so the transmitted chain never
     * contains its own root — anchoring could only ever come from outside the token, and the EU
     * trusted lists are that outside. Which is why this needed `IosEtsiTrust` rather than anything
     * the token itself carries.
     *
     * ⚠️ This replicates `CompressedStatusList.fromJwt`, which is the only way to reach
     * `certificateChainValidator`: `StatusList.fromJwt` does not expose it. Both constants below must
     * stay in step with that function — in particular **[STATUS_LIST_MAX_VALIDITY], because
     * `validateJwt`'s own default is 10 hours** and every status list older than that would fail.
     */
    private suspend fun readAgainstTrustedLists(
        token: String,
        context: VerificationContext?,
    ): ReadList {
        // The initial value is the answer when the validator never runs, which happens when the
        // token carries no `x5c` at all: nothing was checked, so nothing is anchored.
        var signerTrust = StatusSignerTrustDomain.NoAnchorsAvailable
        val body = validateJwt(
            jwt = token,
            jwtName = "Status List",
            checks = mapOf(WebTokenCheck.TYP to STATUS_LIST_TYP),
            maxValidity = STATUS_LIST_MAX_VALIDITY,
            certificateChainValidator = { chain, atTime ->
                // Structural validation first, and it throws on failure — so a chain that does not
                // link, has the wrong key usage or has expired never reaches the trust question.
                basicCertificateChainValidator(chain, atTime)
                signerTrust = signerTrustFor(chain, context)
                // Always `true`: this decides whether the token is *readable*, and a list whose
                // signer is not trusted is still worth reading and reporting. Refusing here would
                // collapse "untrusted signer" into "unreadable list", which the caller could not
                // then tell from a network failure.
                true
            },
        )
        val statusList = body[STATUS_LIST_CLAIM]?.jsonObject
            ?: throw IllegalArgumentException("missing required '$STATUS_LIST_CLAIM' claim")
        return ReadList(list = StatusList.fromJson(statusList), signerTrust = signerTrust)
    }

    /**
     * Whether the EU trusted lists vouch for [chain] as a signer of [context]'s status lists.
     *
     * ⚠️ **An undetermined verdict becomes [StatusSignerTrustDomain.NoAnchorsAvailable], not
     * [StatusSignerTrustDomain.NotTrusted]**, and the two causes it covers — no list classifies this
     * credential, or the lists could not be consulted — are both genuinely "nothing to check
     * against" rather than "this chain failed". Mapping them to `NotTrusted` would let an offline
     * moment read as a hostile issuer, which under `Enforce` is the difference between leaving a
     * document alone and refusing to trust its issuer.
     */
    private suspend fun signerTrustFor(
        chain: X509CertChain,
        context: VerificationContext?,
    ): StatusSignerTrustDomain {
        val trust = issuerTrust ?: return StatusSignerTrustDomain.NoAnchorsAvailable
        // No context means no list covers this credential type; asking would only ever say so.
        if (context == null) return StatusSignerTrustDomain.NoAnchorsAvailable

        return when (trust.verdict(chain.certificates.toTrustChain(), context)) {
            TrustVerdict.TRUSTED -> StatusSignerTrustDomain.Trusted
            TrustVerdict.NOT_TRUSTED -> StatusSignerTrustDomain.NotTrusted
            TrustVerdict.UNDETERMINED -> StatusSignerTrustDomain.NoAnchorsAvailable
        }
    }

    /**
     * Note on out-of-range indices: multipaz's `StatusList[idx]` returns **0** for an index past the
     * end of the list rather than failing, so a credential pointing beyond its list reads as valid.
     * That is the library's choice and it cannot be detected from here — the backing size is private —
     * so it is documented rather than papered over with a guard that would never fire.
     */
    private fun statusAt(
        list: StatusList,
        idx: Int,
        signerTrust: StatusSignerTrustDomain,
    ): RevocationOutcome =
        when (val value = list[idx]) {
            STATUS_VALID -> RevocationOutcome.Valid(signerTrust)
            STATUS_INVALID -> RevocationOutcome.Invalid(signerTrust)
            STATUS_SUSPENDED -> RevocationOutcome.Suspended(signerTrust)
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

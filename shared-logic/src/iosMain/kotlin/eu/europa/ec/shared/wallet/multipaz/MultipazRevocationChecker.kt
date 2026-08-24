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
import org.multipaz.revocation.RevocationStatus
import org.multipaz.revocation.StatusList

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
    data object Valid : RevocationOutcome
    data object Invalid : RevocationOutcome
    data object Suspended : RevocationOutcome

    /**
     * No conclusion. Carries [reason] for the log, because every cause here is operational — the
     * credential carries no status, the list could not be fetched, its signature did not verify, or
     * the format is one this checker does not implement.
     */
    data class Unknown(val reason: String) : RevocationOutcome

    /** Whether the wallet should flag the document, i.e. show it as revoked. */
    val isRevoked: Boolean get() = this is Invalid || this is Suspended
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

        val list = try {
            // Signature verification is NOT optional and NOT ours to skip: an unverified status list
            // is worse than none, since anyone able to answer this URL could un-revoke a credential.
            // multipaz enforces it — with no `publicKey` and no TRUST check it requires the token to
            // carry an `x5c` chain that validates, and throws otherwise. We pass the signer key from
            // the credential itself when the issuer put one there (ISO mdoc's `status_list`
            // `certificate` field), which is the strongest binding available: it comes from data the
            // issuer signed.
            StatusList.fromJwt(
                jwt = token,
                publicKey = status.certificate?.ecPublicKey,
            )
        } catch (t: Throwable) {
            // Distinguish the one failure that is not the issuer's fault and not a bug here. multipaz's
            // `validateJwt` ends with `require(publicKey != null || caValidated)`, so a status list
            // signed only with an `x5c` — which is what both EU dev issuers publish, neither putting a
            // `certificate` in the credential's status claim — is refused with a bare "Failed
            // requirement.". Correctly: an unverifiable status list must not be believed. But the
            // message says nothing, and the fix is not here.
            if (status.certificate == null) {
                return RevocationOutcome.Unknown(
                    "status list at ${status.uri} is signed with a certificate chain this wallet " +
                            "cannot validate: the credential names no signer key, and iOS has no trust " +
                            "anchors wired up yet. Blocked on the same gap as issuer trust."
                )
            }
            return RevocationOutcome.Unknown(
                "status list at ${status.uri} was rejected: ${t.message ?: t::class.simpleName}"
            )
        }

        return statusAt(list, status.idx)
    }

    /**
     * Note on out-of-range indices: multipaz's `StatusList[idx]` returns **0** for an index past the
     * end of the list rather than failing, so a credential pointing beyond its list reads as valid.
     * That is the library's choice and it cannot be detected from here — the backing size is private —
     * so it is documented rather than papered over with a guard that would never fire.
     */
    private fun statusAt(list: StatusList, idx: Int): RevocationOutcome =
        when (val value = list[idx]) {
            STATUS_VALID -> RevocationOutcome.Valid
            STATUS_INVALID -> RevocationOutcome.Invalid
            STATUS_SUSPENDED -> RevocationOutcome.Suspended
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
    }
}

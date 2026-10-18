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

package eu.europa.ec.shared.wallet.document

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * How a document's credentials are consumed — a KMP port of
 * `eu.europa.ec.eudi.wallet.document.CreateDocumentSettings.CredentialPolicy`.
 *
 * It matters to the *read* path (not just issuance) in two places: [OnceOnly] excludes already-used
 * credentials from the usable set, and it is the only policy for which a document can be
 * "low on credentials".
 */
internal sealed interface WalletCredentialPolicy {

    /** The batch size the document was issued with — `WalletDocument.initialCredentialsCount`. */
    val numberOfCredentials: Int

    /** Each credential is presentable once and is deleted after use. */
    data class OnceOnly(
        override val numberOfCredentials: Int = 1,
        val reissueTriggerUnused: Int? = null,
    ) : WalletCredentialPolicy

    /** A single credential presented repeatedly until its lifetime runs out. */
    data class LimitedTime(
        val reissueTriggerLifetimeLeft: Duration? = null,
    ) : WalletCredentialPolicy {
        override val numberOfCredentials: Int get() = 1
    }

    /** A batch rotated across presentations; a credential's usage count grows but it survives. */
    data class RotatingBatch(
        override val numberOfCredentials: Int = 1,
        val reissueTriggerLifetimeLeft: Duration? = null,
    ) : WalletCredentialPolicy
}

/**
 * A platform-neutral view of one *certified* credential of a stored document — the fields the read
 * path needs from multipaz's `SecureAreaBoundCredential`, and nothing else. Keeping the projection
 * logic on this type rather than on the multipaz class is what lets it be tested in commonTest.
 */
internal data class StoredCredential(
    val alias: String,
    /** The credential's multipaz domain, which the document manager sets to its own identifier. */
    val domain: String,
    val usageCount: Int,
    val validFrom: Instant,
    val validUntil: Instant,
    /** Whether the secure-area key behind the credential has been invalidated. */
    val isInvalidated: Boolean = false,
)

/**
 * A platform-neutral view of one stored document, assembled by the platform's document reader and
 * turned into the app-facing [eu.europa.ec.shared.wallet.WalletDocument] by [toWalletDocument].
 *
 * [issuedAt] doubles as the issuance marker: the document manager sets it exactly when the issuer's
 * data arrives, so `null` means the document is still awaiting issuance and nothing
 * credential- or validity-related is knowable yet.
 */
internal data class StoredDocument(
    val id: String,
    val name: String,
    /** An mdoc docType or an SD-JWT VC vct. */
    val formatType: String,
    /** Identifier of the document manager that owns the document; scopes its credentials. */
    val documentManagerId: String,
    val policy: WalletCredentialPolicy,
    val issuedAt: Instant? = null,
    val claims: Map<String, String> = emptyMap(),
    /** The document's certified credentials, **unfiltered** — [usableCredentials] narrows them. */
    val certifiedCredentials: List<StoredCredential> = emptyList(),
    val issuerMetadata: IssuerMetadata? = null,
)

/**
 * The credentials this document can still present — a port of
 * `IssuedDocument.getCredentials()`, minus the `filterIsInstance<SecureAreaBoundCredential>` step
 * (the caller has already narrowed to secure-area-bound credentials by building
 * [StoredDocument.certifiedCredentials]).
 *
 * Like the original this does **not** filter on temporal validity, so expired credentials are still
 * counted; that is deliberate, and is what lets [walletExpiresAt] report an expiry in the past
 * instead of vanishing once the document expires.
 */
internal fun StoredDocument.usableCredentials(): List<StoredCredential> =
    certifiedCredentials
        .filterNot { it.isInvalidated }
        .filter { it.domain == documentManagerId }
        .filter {
            when (policy) {
                is WalletCredentialPolicy.LimitedTime,
                is WalletCredentialPolicy.RotatingBatch -> true

                is WalletCredentialPolicy.OnceOnly -> it.usageCount == 0
            }
        }

/**
 * The latest instant any usable credential is valid until, or null when there are none left to ask
 * (e.g. an exhausted once-only batch) — which is *not* the same as being expired.
 */
internal fun List<StoredCredential>.walletExpiresAt(): Instant? = maxOfOrNull { it.validUntil }

/**
 * Whether this document is due for background re-issuance, mirroring Android's `ReIssuanceWorkManager`.
 *
 * The threshold travels with the document's stored policy — set by the issuer's advertised
 * `credential_reuse_policy` where there is one — so this asks the policy, not a global setting:
 *
 * - **once-only** is driven by the *count* of currently-valid unused credentials, never by lifetime.
 *   Expired credentials are excluded deliberately: counting them would hold an exhausted batch above
 *   the threshold forever, and it would never re-issue.
 * - **rotating batch** and **limited time** are driven by remaining lifetime, since presenting one of
 *   those credentials does not consume it.
 *
 * A null threshold is not expected — every policy this wallet stores carries one — but it is treated
 * defensively as "only once there is nothing usable left", which is the conservative direction: it
 * delays a network round-trip rather than provoking one.
 */
internal fun StoredDocument.isDueForReIssuance(now: Instant): Boolean {
    val usable = usableCredentials()
    return when (policy) {
        is WalletCredentialPolicy.OnceOnly -> {
            val validUnused = usable.count { now >= it.validFrom && now <= it.validUntil }
            validUnused <= (policy.reissueTriggerUnused ?: 0)
        }

        is WalletCredentialPolicy.RotatingBatch -> usable.isDueByLifetime(
            now = now,
            trigger = policy.reissueTriggerLifetimeLeft,
        )

        is WalletCredentialPolicy.LimitedTime -> usable.isDueByLifetime(
            now = now,
            trigger = policy.reissueTriggerLifetimeLeft,
        )
    }
}

/**
 * Nothing valid left, or the last valid credential expires within [trigger].
 *
 * A null expiry means no usable credential remains at all, which is itself a reason to re-issue —
 * the same reading Android gives a failed `getValidUntil()`.
 */
private fun List<StoredCredential>.isDueByLifetime(now: Instant, trigger: Duration?): Boolean {
    val validUntil = walletExpiresAt() ?: return true
    return validUntil <= now + (trigger ?: Duration.ZERO)
}

/**
 * Whether the document is running out of credentials and should be re-issued. Mirrors
 * `WalletCoreDocumentsControllerImpl.isDocumentLowOnCredentials`: only a once-only batch can run
 * out, since a rotating credential survives being presented.
 */
internal fun isLowOnCredentials(
    policy: WalletCredentialPolicy,
    credentialsCount: Int,
): Boolean = policy is WalletCredentialPolicy.OnceOnly && credentialsCount <= 1

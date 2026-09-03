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

package eu.europa.ec.shared.wallet.revocation

/**
 * What a revocation check concluded about one credential, independent of which library ran it.
 *
 * Android resolves this through wallet-core's `resolveStatus`, iOS through multipaz's
 * `org.multipaz.revocation`. The two engines report the same four things.
 */
enum class DocumentStatusDomain {
    Valid,
    Invalid,
    Suspended,

    /** No conclusion: nothing fetched, nothing parsed, or a status value neither wallet reads. */
    Unknown,
}

/**
 * How well the status list token's *signer* was established.
 *
 * These are wallet-core's own distinctions rather than invented ones, because it already makes them
 * and collapsing them loses the case that matters. `TrustEvaluatingJwtSignatureVerifier` evaluates
 * the chain and then separates a null trust result — no anchors exist for this attestation type —
 * from `CertificationChainValidation.NotTrusted`, a chain that failed against anchors that do exist.
 */
enum class StatusSignerTrustDomain {
    /** The signer was tied to a key the issuer vouched for, or to a trusted anchor. */
    Trusted,

    /** Anchors exist for this attestation type and this chain failed against them. */
    NotTrusted,

    /**
     * No anchors exist to check against — nothing failed a check, there was simply nothing to check
     * against.
     *
     * On iOS this is now what a credential type **no trusted list classifies** reports (an mDL, a
     * PubEAA), or a reading taken while the lists could not be consulted at all. A PID's status
     * signer reaches [Trusted] or [NotTrusted] instead. Android's classifications cover exactly the
     * same set, so this is a shared limit rather than an iOS one.
     */
    NoAnchorsAvailable,

    /**
     * Trust was not established either way, because the platform cannot report it. **This is
     * Android's state today**: wallet-core evaluates trust and then, under
     * [StatusTrustPolicyDomain.Inform], logs the result and discards it — `resolveStatus` hands back
     * a bare status. The condition is not absent on Android, only unobservable from app code.
     */
    NotEvaluated,
}

/**
 * What a failure to establish the signer's trust should cost.
 *
 * Mirrors wallet-core's `TrustPolicy.Action` for the document status resolver, deliberately: the
 * point of this file is that both platforms answer to one policy, and inventing a third action
 * alongside upstream's two would defeat that.
 */
enum class StatusTrustPolicyDomain {
    /** An untrusted or unanchored reading is not usable, so nothing is decided from it. */
    Enforce,

    /** Trust does not gate the outcome; the status alone decides. */
    Inform,
}

/** What to do with the cached revoked-document flag. */
enum class RevocationActionDomain {
    /** Mark the document revoked, and report it as newly revoked. */
    Flag,

    /** Drop an existing revoked mark: the credential is valid again. */
    Clear,

    Leave,
}

/**
 * **The one rule deciding what a revocation reading does to stored state, for both platforms.**
 *
 * It used to be written twice — Android's `RevocationWorkManager` and iOS's
 * `MultipazWalletEngine.refreshRevocationStatuses` each had their own copy of the same `when` — which
 * is precisely how the two drifted apart: a change to one was invisible to the other. Both engines
 * now map their library's result onto [DocumentStatusDomain] + [StatusSignerTrustDomain] and ask
 * this. Identical readings under identical policy therefore produce identical actions by
 * construction, and the tests covering it run on the JVM *and* on Kotlin/Native.
 *
 * The platforms still differ in what they can *observe*, and that difference is visible in the type
 * rather than buried in an engine: Android reports [StatusSignerTrustDomain.NotEvaluated] because
 * wallet-core computes its trust result and does not return it, while iOS reports a measured value —
 * [StatusSignerTrustDomain.Trusted] or [StatusSignerTrustDomain.NotTrusted] for a PID, whose status
 * signer the EU trusted lists classify, and [StatusSignerTrustDomain.NoAnchorsAvailable] for a
 * credential type they do not. A difference in evidence, not in policy.
 *
 * @param currentlyFlagged whether this document is already marked revoked, which decides whether
 *   there is anything to change: re-flagging an already-flagged document would re-fire the
 *   user-facing "documents revoked" notification on every sweep.
 */
fun revocationAction(
    status: DocumentStatusDomain,
    signerTrust: StatusSignerTrustDomain,
    policy: StatusTrustPolicyDomain,
    currentlyFlagged: Boolean,
): RevocationActionDomain {
    if (!signerTrust.permits(policy)) return RevocationActionDomain.Leave

    return when (status) {
        DocumentStatusDomain.Invalid,
        DocumentStatusDomain.Suspended,
            -> if (currentlyFlagged) RevocationActionDomain.Leave else RevocationActionDomain.Flag

        DocumentStatusDomain.Valid ->
            if (currentlyFlagged) RevocationActionDomain.Clear else RevocationActionDomain.Leave

        // Never acted on, in either direction. An unreachable or unreadable status list must not
        // silently clear a revocation, and cannot establish one either.
        DocumentStatusDomain.Unknown -> RevocationActionDomain.Leave
    }
}

/**
 * Whether a reading with this signer trust may decide anything under [policy].
 *
 * Under [StatusTrustPolicyDomain.Inform] everything passes, which is what wallet-core does: it
 * computes the trust result and ignores it. Under [StatusTrustPolicyDomain.Enforce] only
 * [StatusSignerTrustDomain.Trusted] passes — wallet-core throws `StatusListNotTrustedException` for
 * the other two, and the Android worker's `onFailure = {}` then leaves the document untouched, which
 * is the same outcome this expresses directly.
 *
 * [StatusSignerTrustDomain.NotEvaluated] under `Enforce` is not reachable on either platform today —
 * a platform that enforces has by definition evaluated. It is refused rather than allowed, because a
 * policy asking for proof should not be satisfied by its absence.
 */
private fun StatusSignerTrustDomain.permits(policy: StatusTrustPolicyDomain): Boolean =
    when (policy) {
        StatusTrustPolicyDomain.Inform -> true
        StatusTrustPolicyDomain.Enforce -> this == StatusSignerTrustDomain.Trusted
    }

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

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The issuer's advertised credential reuse policy — ETSI TS 119 472-3's `credential_reuse_policy`.
 *
 * **This is where the numbers are supposed to come from.** Android does not invent a batch size or a
 * re-issuance threshold: it reads the issuer's policy, picks the first option it supports, and stores
 * the result on the document. iOS invented both — a fixed `credentialBatchSize` and, until recently, a
 * policy kind derived from that size — which is why its PID read `7/20` where the issuer had actually
 * said "seven, once-only, re-issue when six remain unused".
 *
 * The wire member names are the library's own (`Specs.kt`, `ETSI119472Part3`), and note that two of the
 * four detail values are hyphenated where the others are not — `rotating-batch` and `per-relying-party`
 * against `once_only` and `limited_time`. Copy them, do not normalise them.
 */
@Serializable
internal data class IssuerReusePolicyOption(
    @SerialName("details") val details: List<String> = emptyList(),
    @SerialName("batch_size") val batchSize: Int? = null,
    @SerialName("reissue_trigger_unused") val reissueTriggerUnused: Int? = null,
    @SerialName("reissue_trigger_lifetime_left") val reissueTriggerLifetimeLeftSeconds: Long? = null,
) {
    val reissueTriggerLifetimeLeft: Duration? get() = reissueTriggerLifetimeLeftSeconds?.seconds
}

/** The whole `credential_reuse_policy` object: a prioritised list of options. */
@Serializable
internal data class IssuerReusePolicy(
    @SerialName("id") val id: String? = null,
    @SerialName("options") val options: List<IssuerReusePolicyOption> = emptyList(),
)

internal object EtsiReusePolicyDetail {
    const val ONCE_ONLY = "once_only"
    const val LIMITED_TIME = "limited_time"
    const val ROTATING_BATCH = "rotating-batch"

    /** Excluded deliberately, as on Android: this wallet cannot bind a credential to one verifier. */
    const val PER_RELYING_PARTY = "per-relying-party"

    val supported = listOf(ONCE_ONLY, LIMITED_TIME, ROTATING_BATCH)
}

/** One option paired with the detail this wallet matched it on. */
internal data class SelectedReusePolicy(
    val option: IssuerReusePolicyOption,
    val detail: String,
)

/**
 * The first option this wallet supports, in the issuer's own order.
 *
 * The order is the issuer's prioritisation and the spec says to take the first supported entry, so this
 * does not look for a "best" one. `per-relying-party` is skipped rather than failing the issuance: an
 * issuer that offers it alongside something we do support should still be usable.
 */
internal fun IssuerReusePolicy.firstSupportedOption(): SelectedReusePolicy? =
    options.firstNotNullOfOrNull { option ->
        option.details
            .firstOrNull { it in EtsiReusePolicyDetail.supported }
            ?.let { SelectedReusePolicy(option, it) }
    }

/**
 * The stored policy this option describes, or null when the issuer left out a member its own detail
 * requires — `once_only` without a `batch_size`, say.
 *
 * Returning null rather than substituting a default is the point: a half-published policy is the
 * issuer's mistake, and falling back to [credentialPolicyFor] is more honest than issuing against
 * numbers nobody stated.
 */
internal fun SelectedReusePolicy.toCredentialPolicy(maxBatchSize: Int): WalletCredentialPolicy? =
    when (detail) {
        EtsiReusePolicyDetail.ONCE_ONLY -> {
            val size = option.batchSize ?: return null
            val trigger = option.reissueTriggerUnused ?: return null
            WalletCredentialPolicy.OnceOnly(
                numberOfCredentials = size.coerceAtMost(maxBatchSize),
                reissueTriggerUnused = trigger,
            )
        }

        EtsiReusePolicyDetail.ROTATING_BATCH -> {
            val size = option.batchSize ?: return null
            val lifetime = option.reissueTriggerLifetimeLeft ?: return null
            WalletCredentialPolicy.RotatingBatch(
                numberOfCredentials = size.coerceAtMost(maxBatchSize),
                reissueTriggerLifetimeLeft = lifetime,
            )
        }

        EtsiReusePolicyDetail.LIMITED_TIME -> {
            val lifetime = option.reissueTriggerLifetimeLeft ?: return null
            WalletCredentialPolicy.LimitedTime(reissueTriggerLifetimeLeft = lifetime)
        }

        else -> null
    }

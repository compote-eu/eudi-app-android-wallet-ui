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
import io.ktor.client.engine.darwin.Darwin
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the registration check run ahead of an issuance decided — Android's
 * `WalletCoreDocumentsController.preflightRegistrationRefusalOrNull`, for the flows that have no approval
 * screen to refuse on: a wallet-initiated issuance and a re-issuance. A refusal here opens no browser
 * and stores nothing.
 */
internal sealed interface RegistrationPreflight {

    /** Go ahead. [registration] is the verified certificate, or null when the check is off. */
    data class Proceed(val registration: IssuerRegistration?) : RegistrationPreflight

    /** Refused: the issuer is not trusted for this on its registration certificate. */
    data object Refused : RegistrationPreflight

    /** The check could not be made, so the flow fails as any other failure does — not as a refusal. */
    data class Unavailable(val detail: String?) : RegistrationPreflight
}

/**
 * Runs the registration check before an issuance with no approval screen.
 *
 * With the check off, [check] is never called: "off" means no traffic for it. With it on, Android's rule
 * applies — `isBlockedForIssuance` over the outcome — so anything short of a verified certificate that
 * covers every offered attestation refuses, an issuer publishing no certificate included (Android:
 * *"no registration certificate published; refuse like any unverified outcome"*). Only metadata that
 * could not be fetched, or a check that threw, is a failure rather than a refusal, as Android's `else`
 * branch makes it.
 */
internal suspend fun registrationPreflight(
    isEnabled: suspend () -> Boolean,
    check: suspend () -> IssuerRegistrationOutcome,
): RegistrationPreflight {
    if (!isEnabled()) return RegistrationPreflight.Proceed(registration = null)

    val outcome = try {
        check()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Logger.w(TAG, "the registration check did not complete", error)
        return RegistrationPreflight.Unavailable(detail = error.message)
    }

    return when (outcome) {
        is IssuerRegistrationOutcome.Verified ->
            if (outcome.overProvided.isEmpty()) {
                RegistrationPreflight.Proceed(registration = outcome.registration)
            } else {
                RegistrationPreflight.Refused
            }

        is IssuerRegistrationOutcome.Failed -> RegistrationPreflight.Refused

        IssuerRegistrationOutcome.NotOffered -> RegistrationPreflight.Refused

        is IssuerRegistrationOutcome.Unavailable -> RegistrationPreflight.Unavailable(detail = outcome.detail)
    }.also { verdict -> Logger.i(TAG, "registration pre-flight: ${verdict::class.simpleName}") }
}

/** The real check: the issuer's signed metadata, fetched and judged for [configurationIds]. */
internal suspend fun checkIssuerRegistrationOnline(
    issuerUrl: String,
    configurationIds: Set<String>,
): IssuerRegistrationOutcome = HttpClient(Darwin).use { client ->
    IosIssuerRegistrationChecker(client).check(issuerUrl = issuerUrl, configurationIds = configurationIds)
}

private const val TAG = "RegistrationPreflight"

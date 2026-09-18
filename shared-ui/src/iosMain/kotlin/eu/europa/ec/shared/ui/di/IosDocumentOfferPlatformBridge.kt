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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.corelogic.model.IssuerRegistrationDomain
import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain
import eu.europa.ec.corelogic.model.isBlockedForIssuance
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.issuancefeature.interactor.DocumentOfferPlatformBridge
import eu.europa.ec.issuancefeature.interactor.PlatformOfferResolution
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.wallet.multipaz.IosCredentialIssuer
import eu.europa.ec.shared.wallet.multipaz.IosCredentialOffer
import eu.europa.ec.shared.wallet.multipaz.IosCredentialOfferReader
import eu.europa.ec.shared.wallet.multipaz.IosIssuanceProgress
import eu.europa.ec.shared.wallet.multipaz.IosOfferResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * Whether a registration outcome refuses this issuance.
 *
 * 🚨 The `NotEvaluated` guard is the whole subtlety and belongs in one place. `isBlockedForIssuance`
 * answers true for it — correctly, for a wallet with the check on — but `NotEvaluated` is also exactly
 * what a wallet with the check *off* produces, and off is the default. Consulting the shared rule
 * alone would refuse every issuance in a stock build.
 */
private fun IssuerRegistrationDomain.refusesIssuance(): Boolean =
    this !is IssuerRegistrationDomain.NotEvaluated && isBlockedForIssuance

/**
 * iOS's [DocumentOfferPlatformBridge]: offers are read and issued through multipaz.
 *
 * **It holds the resolved offers**, as the contract requires, though for a different reason than Android:
 * there the cached object is wallet-core's `Offer`, which cannot cross into shared code, while here the
 * offer link alone would be enough to issue from. Keeping the map anyway is what makes
 * [issueResolvedOffer]'s promise true — an offer nobody resolved is refused rather than quietly fetched a
 * second time, which would risk issuing something other than what the user was shown.
 */
internal class IosDocumentOfferPlatformBridge(
    private val offers: IosCredentialOfferReader,
    private val credentialIssuer: IosCredentialIssuer,
    /**
     * Whether the user asked for issuer registration certificates to be checked. Off by default on
     * both platforms — see [IosPreferences.checkIssuerRegistration].
     */
    private val isRegistrationCheckEnabled: suspend () -> Boolean = {
        IosPreferences.checkIssuerRegistration()
    },
    /**
     * The check itself, supplied by the DI module rather than defaulted here.
     *
     * 🚨 **Not a default, and the reason is the linker.** Referencing the real checker from this file
     * — even as an unused default — makes `IosEtsiTrust` reachable from **every test binary in this
     * module**, and the trust stack reaches the `PKIXBridge` cinterop whose Swift half only an Xcode
     * target can supply. The whole module then fails to link with
     * `Undefined symbols … _TtC10PKIXBridge13PKIXValidator`, and no test in it runs. Kotlin/Native
     * drops unreferenced code, so keeping the reference in the DI module keeps the tests linkable.
     */
    private val checkRegistration: suspend (IosCredentialOffer, String) -> IssuerRegistrationDomain,
) : DocumentOfferPlatformBridge {

    private val resolvedOffers: MutableMap<String, IosCredentialOffer> = mutableMapOf()

    /**
     * What the last resolve concluded about each offer's issuer, so issuance can refuse what the
     * screen refused. Keyed by offer, because a second offer from the same issuer may name different
     * configurations and so reach a different answer.
     */
    private val registrationOutcomes: MutableMap<String, IssuerRegistrationDomain> = mutableMapOf()

    override fun localeTag(): String = NSLocale.currentLocale.languageCode

    override suspend fun resolveOffer(offerUri: String, locale: String): PlatformOfferResolution =
        when (val resolution = offers.resolve(offerUri = offerUri, locale = locale)) {
            is IosOfferResolution.Failure ->
                PlatformOfferResolution.Failure(errorMessage = resolution.message)

            is IosOfferResolution.Resolved -> {
                resolvedOffers[offerUri] = resolution.offer

                val registration = if (isRegistrationCheckEnabled()) {
                    checkRegistration(resolution.offer, locale)
                } else {
                    // Not "we looked and found nothing" — "we did not look". The shared rule reads
                    // this together with the settings flag, never on its own.
                    IssuerRegistrationDomain.NotEvaluated
                }
                registrationOutcomes[offerUri] = registration

                if (registration.refusesIssuance()) {
                    // Android refuses here rather than on the offer screen, and the shared UI already
                    // has the screen for it — the user is told the issuer could not be placed instead
                    // of being shown an offer they cannot accept.
                    return PlatformOfferResolution.IssuerNotTrusted(
                        reason = UntrustedIssuerReasonDomain.REGISTRATION_CERTIFICATE,
                    )
                }

                if (resolution.documentNames.isEmpty()) {
                    PlatformOfferResolution.NoDocuments(
                        issuerName = resolution.issuerName,
                        issuerLogoUri = resolution.issuerLogoUri,
                        issuerRegistration = registration,
                    )
                } else {
                    PlatformOfferResolution.Success(
                        documentNames = resolution.documentNames,
                        issuerName = resolution.issuerName,
                        issuerLogoUri = resolution.issuerLogoUri,
                        containsPid = resolution.containsPid,
                        txCodeLength = resolution.offer.txCodeLength,
                        txCodeIsNumeric = resolution.offer.txCodeIsNumeric,
                        issuerRegistration = registration,
                    )
                }
            }
        }

    override fun issueResolvedOffer(
        offerUri: String,
        txCode: String?,
    ): Flow<IssueDocumentsPartialState> {
        val offer = resolvedOffers[offerUri]
            ?: return flow {
                emit(IssueDocumentsPartialState.Failure(errorMessage = OFFER_NOT_RESOLVED))
            }

        // The screen already refused to offer this, but the screen is not the gate: a deep link or a
        // resumed flow can reach here without one. Android gates in its controller for the same reason.
        val registration = registrationOutcomes[offerUri] ?: IssuerRegistrationDomain.NotEvaluated
        if (registration.refusesIssuance()) {
            return flow {
                emit(
                    IssueDocumentsPartialState.IssuerNotTrusted(
                        reason = UntrustedIssuerReasonDomain.REGISTRATION_CERTIFICATE,
                    )
                )
            }
        }

        return credentialIssuer.issueOffer(offer = offer, txCode = txCode).map { progress ->
            when (progress) {
                is IosIssuanceProgress.Failure ->
                    IssueDocumentsPartialState.Failure(errorMessage = progress.message)

                is IosIssuanceProgress.Issued ->
                    IssueDocumentsPartialState.Success(documentIds = progress.documentIds)
            }
        }
    }

    /**
     * Nothing to raise: multipaz's `SecureEnclaveSecureArea` presents the LocalAuthentication dialog
     * itself when a key is used, so there is no separate prompt — and an offer's own secret is the
     * transaction code, which the offer-code screen collects rather than this.
     */
    override fun handleUserAuth(
        context: PlatformContext,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult,
    ) = resultHandler.onAuthenticationFailure()

    /**
     * Inert, because the issuer consumes its own redirect: `IosCredentialIssuer` awaits
     * `IosAuthorizationRedirects` for the flow it started, rather than being pushed a URL from outside as
     * wallet-core's resume does.
     */
    override fun resumeOpenId4VciWithAuthorization(uri: String) = Unit

    // `internal`, matching the class's own visibility rather than widening anything: these two
    // strings are the bridge's failure vocabulary, and the gate tests assert on which one came back.
    internal companion object {
        const val OFFER_NOT_RESOLVED = "This offer was not read; open it again."
    }
}

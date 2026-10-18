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

// RESKIN PROBE (option B), debug-only evidence harness. It draws the app's REAL PresentationRequestScreen with the
// REAL PresentationRequestViewModel; only the interactor is a canned fake, so the consent screen can be shot on the
// simulator without a live verifier. No screen, view-model or interactor source changes for this harness.
package eu.europa.ec.uxspike.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.ui.request.model.DocumentFormatDomain
import eu.europa.ec.commonfeature.ui.request.model.DocumentPayloadDomain
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.commonfeature.ui.request.model.RequestDocumentItemUi
import eu.europa.ec.corelogic.model.ClaimDomain
import eu.europa.ec.corelogic.model.ClaimPathDomain
import eu.europa.ec.corelogic.model.ClaimType
import eu.europa.ec.corelogic.model.RegistrationDetailsDomain
import eu.europa.ec.corelogic.model.RegistrationFailureReasonDomain
import eu.europa.ec.corelogic.model.RegistrationStatusDomain
import eu.europa.ec.corelogic.model.RelyingPartyDomain
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractorPartialState
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestScreen
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestViewModel
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.navigation.DashboardRoute
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemSupportingContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.CheckboxDataUi
import eu.europa.ec.uilogic.component.wrap.ExpandableListItemUi
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** `--ux-spike reskin-request[-untrusted]`: the scripts this harness answers. */
internal const val RESKIN_REQUEST_PREFIX = "reskin-request"

/** The real screen and view model over [CannedPresentationRequestInteractor]. */
@Composable
internal fun ReskinRequestHarness(script: String, navigator: AppNavigator) {
    val untrusted = script.endsWith("-untrusted")
    val viewModel = remember(script) {
        PresentationRequestViewModel(
            interactor = CannedPresentationRequestInteractor(untrusted),
            requestUriConfig = RequestUriConfig(PresentationMode.OpenId4Vp(uri = "openid4vp://reskin", initiatorRoute = DashboardRoute)),
        )
    }
    PresentationRequestScreen(intentAction = null, navigator = navigator, viewModel = viewModel)
}

private class CannedPresentationRequestInteractor(private val untrusted: Boolean) : PresentationRequestInteractor {
    override var presentationScopeId: String = ""
    override fun setScopeId(scopeId: String) { presentationScopeId = scopeId }
    override fun stopPresentation() = Unit
    override fun rejectPresentation() = Unit
    override fun updateRequestedDocuments(selectedCombination: RequestCombinationUi?) = Unit
    override fun setConfig(config: RequestUriConfig, intentAction: IntentAction?) = Unit

    override fun getRequestDocuments(): Flow<PresentationRequestInteractorPartialState> {
        val details = RegistrationDetailsDomain(
            tradeName = "Príkladová banka, a.s.",
            uniqueId = "LEISK-123456789",
            logoUri = null,
            intendedUse = "Overenie totožnosti pri otvorení účtu",
            privacyPolicyUrl = "https://www.example.sk/ochrana-udajov",
            serviceDescription = "Internetové bankovníctvo",
        )
        val registration = if (untrusted) {
            RegistrationStatusDomain.NotVerified(RegistrationFailureReasonDomain.SIGNATURE_INVALID, details)
        } else {
            RegistrationStatusDomain.Verified(details, overaskedClaims = emptyList())
        }
        return flowOf(
            PresentationRequestInteractorPartialState.Success(
                relyingParty = RelyingPartyDomain(
                    name = "Príkladová banka, a.s.",
                    uniqueId = "LEISK-123456789",
                    hasTrustedAccessCertificate = !untrusted,
                    logoUri = null,
                    registration = registration,
                ),
                combinationsUi = listOf(RequestCombinationUi(documents = listOf(pid()), matches = emptyList(), transactionData = null)),
                claimsAreSelectable = true,
            ),
        )
    }

    private fun pid(): RequestDocumentItemUi {
        val claims = listOf(
            "family_name" to ("Priezvisko" to "Novák"),
            "given_name" to ("Meno" to "Ján"),
            "birth_date" to ("Dátum narodenia" to "12. 4. 1985"),
            "nationality" to ("Štátna príslušnosť" to "SK"),
        )
        return RequestDocumentItemUi(
            domainPayload = DocumentPayloadDomain(
                docName = "Občiansky preukaz",
                docId = "pid-1",
                docFormatDomain = DocumentFormatDomain.MsoMdoc,
                docClaimsDomain = claims.map { (key, v) ->
                    ClaimDomain.Primitive(
                        key = key,
                        displayTitle = v.first,
                        value = v.second,
                        isRequired = false,
                        path = ClaimPathDomain.ofPlainKeys(names = listOf(key), type = ClaimType.MsoMdoc(namespace = "eu.europa.ec.eudi.pid.1")),
                    )
                },
            ),
            headerUi = ExpandableListItemUi.NestedListItem(
                header = ListItemDataUi(
                    itemId = "pid-1",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Občiansky preukaz"),
                    supportingContentData = ListItemSupportingContentDataUi.Text(text = "4 údaje"),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(iconData = AppIcons.KeyboardArrowUp),
                ),
                nestedItems = claims.mapIndexed { i, (key, v) ->
                    ExpandableListItemUi.SingleListItem(
                        ListItemDataUi(
                            itemId = "pid-1.$key",
                            overlineText = v.first,
                            mainContentData = ListItemMainContentDataUi.Text(text = v.second),
                            trailingContentData = ListItemTrailingContentDataUi.Checkbox(CheckboxDataUi(isChecked = i < 2)),
                        ),
                    )
                },
                isExpanded = true,
            ),
        )
    }
}

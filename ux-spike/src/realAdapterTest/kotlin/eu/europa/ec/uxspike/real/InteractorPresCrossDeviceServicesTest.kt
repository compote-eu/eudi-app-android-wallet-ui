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

package eu.europa.ec.uxspike.real

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.commonfeature.config.PresentationMode
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.corelogic.model.AuthenticationData
import eu.europa.ec.corelogic.model.RegistrationDetailsDomain
import eu.europa.ec.corelogic.model.RegistrationStatusDomain
import eu.europa.ec.dashboardfeature.ui.settings.FakeBiometricInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingObserveResponsePartialState as Observed
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingSendRequestedDocumentPartialState
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractorPartialState as Req
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestViewModelTest.Companion.document
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestViewModelTest.Companion.success
import eu.europa.ec.presentationfeature.ui.request.PresentationRequestViewModelTest.FakePresentationRequestInteractor
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.testPlatformContext
import eu.europa.ec.shared.platform.testPlatformIntent
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.ux.contract.pres.AuthenticateResult
import eu.europa.ec.ux.contract.pres.CancelResult
import eu.europa.ec.ux.contract.pres.FetchRequestResult
import eu.europa.ec.ux.contract.pres.PresCrossDeviceInvoker
import eu.europa.ec.ux.contract.pres.PresCrossDeviceServiceResults
import eu.europa.ec.ux.contract.pres.Requirement
import eu.europa.ec.ux.contract.pres.SendResult
import eu.europa.ec.ux.contract.pres.Trust
import eu.europa.ec.ux.contract.pres.VerificationCodeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M11b: every mapping of the real-interactor adapter, against fakes of the REAL interactor interfaces (the app's own
 * `FakePresentationRequestInteractor` and `FakeBiometricInteractor`, plus a loading fake). Runs on the Android host and
 * the iOS simulator.
 */
class InteractorPresCrossDeviceServicesTest {

    private val logs = mutableListOf<String>()
    private val closedScopes = mutableListOf<String>()

    private fun adapter(
        request: PresentationRequestInteractor = FakePresentationRequestInteractor(listOf(success(document("d1", "c1", checked = true)))),
        loading: FakeLoading = FakeLoading(),
        biometrics: FakeBiometricInteractor = FakeBiometricInteractor(),
        context: PlatformContext? = null,
    ) = InteractorPresCrossDeviceServices(request, loading, biometrics, { context }, { closedScopes += it }, { logs += it })

    private fun untrusted(state: Req.Success) =
        state.copy(relyingParty = state.relyingParty.copy(hasTrustedAccessCertificate = false))

    // --- conformance (DEC-081) ---

    @Test
    fun undeclaredListsExactlyTheDocumentedGaps() {
        assertEquals(AdapterGaps.UNDECLARED, PresCrossDeviceServiceResults.undeclared(adapter()))
    }

    @Test
    fun theInvokerCarriesTheAdapterResultAsTheBundleResultName() = runTest {
        val out = PresCrossDeviceInvoker(adapter()).invoke("fetchRequest", JsonObject(mapOf("uri" to JsonPrimitive(URI))))
        assertEquals("OptionalOnly", out.getValue("type").jsonPrimitive.content)
    }

    // --- fetchRequest ---

    @Test
    fun fetchConfiguresTheLinkAsTheDashboardDoes() = runTest {
        val request = FakePresentationRequestInteractor(listOf(success(document("d1", "c1", checked = true))))
        adapter(request).fetchRequest(URI)
        val mode = assertIs<PresentationMode.OpenId4Vp>(request.configuredWith?.mode)
        assertEquals(URI, mode.uri)
        assertNull(request.configuredIntentAction)
    }

    @Test
    fun selectableTrustedRequestIsOptionalOnlyWithUntickedRows() = runTest {
        val result = assertIs<FetchRequestResult.OptionalOnly>(adapter().fetchRequest(URI))
        val item = result.request.attributes.single()
        assertEquals("c1", item.id)
        assertEquals(Requirement.OPTIONAL, item.requirement)
        assertEquals(false, item.selected)
        assertEquals("doc-d1", result.request.documentName)
        assertEquals(Trust.TRUSTED, result.verifier.trust)
        assertEquals("Acme", result.verifier.name)
        assertTrue(logs.any { "claimsAreSelectable" in it })
    }

    @Test
    fun notSelectableTrustedRequestIsRequestReadyWithMandatoryRows() = runTest {
        val state = success(document("d1", "c1", checked = true)).copy(claimsAreSelectable = false)
        val result = assertIs<FetchRequestResult.RequestReady>(adapter(FakePresentationRequestInteractor(listOf(state))).fetchRequest(URI))
        assertEquals(Requirement.MANDATORY, result.request.attributes.single().requirement)
    }

    @Test
    fun aRelyingPartyNotFullyVerifiedIsRpUntrusted() = runTest {
        val state = untrusted(success(document("d1", "c1", checked = true)).copy(claimsAreSelectable = false))
        val result = assertIs<FetchRequestResult.RpUntrusted>(adapter(FakePresentationRequestInteractor(listOf(state))).fetchRequest(URI))
        assertEquals(Trust.UNTRUSTED, result.verifier.trust)
    }

    @Test
    fun rpUntrustedWithOnlyOptionalRowsIsLoggedAsAContractConflict() = runTest {
        val state = untrusted(success(document("d1", "c1", checked = true)))
        assertIs<FetchRequestResult.RpUntrusted>(adapter(FakePresentationRequestInteractor(listOf(state))).fetchRequest(URI))
        assertTrue(logs.any { "RUL-pres-request-has-mandatory" in it })
    }

    @Test
    fun theVerifierPayloadComesFromTheRegistration() {
        val rp = success().relyingParty.copy(
            registration = RegistrationStatusDomain.Verified(
                details = RegistrationDetailsDomain("Trade", "id", null, "Age check", "https://p.example", "Shop"),
                overaskedClaims = emptyList(),
            ),
        )
        val verifier = rp.toVerifierPayload()
        assertEquals("https://p.example", verifier.privacyUrl)
        assertEquals("Age check", verifier.reason)
        assertEquals("Shop", verifier.serviceName)
    }

    @Test
    fun noDataIsDocMissingWithoutADocumentName() = runTest {
        val result = adapter(FakePresentationRequestInteractor(listOf(Req.NoData(success().relyingParty)))).fetchRequest(URI)
        assertEquals(FetchRequestResult.DocMissing(documentName = null), result)
    }

    @Test
    fun anUntypedFailureIsErrorNeverExpired() = runTest {
        val result = adapter(FakePresentationRequestInteractor(listOf(Req.Failure("request expired")))).fetchRequest(URI)
        assertEquals(FetchRequestResult.Error(reason = "request expired"), result)
    }

    @Test
    fun theAppsVerifierBlockIsErrorNotRpUntrusted() = runTest {
        val result = adapter(FakePresentationRequestInteractor(listOf(Req.VerifierNotTrusted))).fetchRequest(URI)
        assertEquals(FetchRequestResult.Error(reason = AdapterGaps.VERIFIER_NOT_TRUSTED), result)
    }

    @Test
    fun aDisconnectIsError() = runTest {
        val result = adapter(FakePresentationRequestInteractor(listOf(Req.Disconnect))).fetchRequest(URI)
        assertEquals(FetchRequestResult.Error(reason = AdapterGaps.DISCONNECTED), result)
    }

    @Test
    fun aStreamWithNoEventIsError() = runTest {
        val result = adapter(FakePresentationRequestInteractor(emptyList())).fetchRequest(URI)
        assertEquals(FetchRequestResult.Error(reason = AdapterGaps.NO_REQUEST_EVENT), result)
    }

    @Test
    fun aThrowingInteractorIsErrorWithItsMessage() = runTest {
        val fake = FakePresentationRequestInteractor(emptyList())
        val throwing = object : PresentationRequestInteractor by fake {
            override fun getRequestDocuments(): Flow<Req> = flow { error("boom") }
        }
        assertEquals(FetchRequestResult.Error(reason = "boom"), adapter(throwing).fetchRequest(URI))
    }

    // --- authenticate ---

    @Test
    fun aSuccessfulPromptIsAuthenticated() = runTest {
        val biometrics = FakeBiometricInteractor()
        assertEquals(AuthenticateResult.Authenticated, adapter(biometrics = biometrics).authenticate())
        assertEquals(1, biometrics.authPrompts)
    }

    @Test
    fun aFailedPromptIsFailed() = runTest {
        val biometrics = FakeBiometricInteractor(authResult = BiometricsAuthenticate.Failed("no match"))
        assertEquals(AuthenticateResult.Failed, adapter(biometrics = biometrics).authenticate())
    }

    @Test
    fun aCancelledPromptIsFailedAndLogged() = runTest {
        val biometrics = FakeBiometricInteractor(authResult = BiometricsAuthenticate.Cancelled)
        assertEquals(AuthenticateResult.Failed, adapter(biometrics = biometrics).authenticate())
        assertTrue(logs.any { "Cancelled -> Failed" in it })
    }

    @Test
    fun noEnrolledBiometricsIsNotEnrolled() = runTest {
        val biometrics = FakeBiometricInteractor(availability = BiometricsAvailability.NonEnrolled)
        assertEquals(AuthenticateResult.NotEnrolled, adapter(biometrics = biometrics).authenticate())
        assertEquals(0, biometrics.authPrompts)
    }

    @Test
    fun anUnavailableSensorIsErrorWithItsMessage() = runTest {
        val biometrics = FakeBiometricInteractor(availability = BiometricsAvailability.Failure("no hardware"))
        assertEquals(AuthenticateResult.Error(reason = "no hardware"), adapter(biometrics = biometrics).authenticate())
    }

    @Test
    fun aPinUserGetsErrorAndNoPrompt() = runTest {
        val biometrics = FakeBiometricInteractor(biometricUserSelection = false)
        assertEquals(AuthenticateResult.Error(reason = AdapterGaps.PIN_ONLY_USER), adapter(biometrics = biometrics).authenticate())
        assertEquals(0, biometrics.authPrompts)
    }

    // --- verificationCode ---

    @Test
    fun theVerificationCodeHasNoSourceAndIsAnExplicitError() = runTest {
        assertEquals(
            VerificationCodeResult.Error(reason = AdapterGaps.NO_VERIFICATION_CODE_SOURCE),
            adapter().verificationCode(),
        )
    }

    // --- send ---

    @Test
    fun sendTicksTheSelectionSendsAndTearsDown() = runTest {
        val request = FakePresentationRequestInteractor(listOf(success(document("d1", "c1", checked = true), document("d2", "c2", checked = true))))
        val loading = FakeLoading(Observed.RequestReadyToBeSent, Observed.Success)
        val services = adapter(request, loading)
        services.fetchRequest(URI)

        assertEquals(SendResult.Sent, services.send(listOf("c2")))

        assertEquals(mapOf("c1" to false, "c2" to true), request.disclosed.single()!!.checks())
        assertEquals(1, loading.sends)
        assertEquals(request.presentationScopeId, loading.presentationScopeId)
        assertEquals(1, request.stopCount)
        assertEquals(listOf(request.presentationScopeId), closedScopes)
        assertEquals(CancelResult.NothingToCancel, services.cancel())
    }

    @Test
    fun aRowTheUserCannotUntickStaysTicked() = runTest {
        val state = success(document("d1", "c1", checked = true)).copy(claimsAreSelectable = false)
        val request = FakePresentationRequestInteractor(listOf(state))
        val services = adapter(request, FakeLoading(Observed.RequestReadyToBeSent, Observed.Success))
        services.fetchRequest(URI)
        services.send(emptyList())
        assertEquals(mapOf("c1" to true), request.disclosed.single()!!.checks())
    }

    @Test
    fun aRefusedSendIsErrorWithItsMessage() = runTest {
        val loading = FakeLoading(Observed.RequestReadyToBeSent, send = PresentationLoadingSendRequestedDocumentPartialState.Failure("nothing kept"))
        val services = adapter(loading = loading).also { it.fetchRequest(URI) }
        assertEquals(SendResult.Error(reason = "nothing kept"), services.send(listOf("c1")))
    }

    @Test
    fun aRedirectIsSentAndTheRedirectIsDropped() = runTest {
        val services = adapter(loading = FakeLoading(Observed.RequestReadyToBeSent, Observed.Redirect("https://rp.example/done")))
        services.fetchRequest(URI)
        assertEquals(SendResult.Sent, services.send(listOf("c1")))
        assertTrue(logs.any { "redirect is dropped" in it })
    }

    @Test
    fun anUntypedSendFailureIsErrorNeverExpired() = runTest {
        val services = adapter(loading = FakeLoading(Observed.RequestReadyToBeSent, Observed.Failure("expired")))
        services.fetchRequest(URI)
        assertEquals(SendResult.Error(reason = "expired"), services.send(listOf("c1")))
    }

    @Test
    fun aRejectionWhileSendingIsError() = runTest {
        val services = adapter(loading = FakeLoading(Observed.RequestReadyToBeSent, Observed.Rejected(null)))
        services.fetchRequest(URI)
        assertEquals(SendResult.Error(reason = AdapterGaps.REJECTED_WHILE_SENDING), services.send(listOf("c1")))
    }

    @Test
    fun aDcApiIntentIsNotThisFlow() = runTest {
        val services = adapter(loading = FakeLoading(Observed.IntentToSend(testPlatformIntent())))
        services.fetchRequest(URI)
        assertEquals(SendResult.Error(reason = AdapterGaps.DC_API_INTENT), services.send(listOf("c1")))
    }

    @Test
    fun keyAuthenticationWithAHostRunsPerDocumentThenSends() = runTest {
        var unlocked = 0
        val data = List(2) { AuthenticationData(BiometricCrypto(null)) { unlocked++ } }
        val loading = FakeLoading(Observed.UserAuthenticationRequired(data), Observed.Success)
        val services = adapter(loading = loading, context = testPlatformContext())
        services.fetchRequest(URI)
        assertEquals(SendResult.Sent, services.send(listOf("c1")))
        assertEquals(2, loading.prompts)
        assertEquals(2, unlocked)
        assertEquals(1, loading.sends)
    }

    @Test
    fun keyAuthenticationWithNoHostIsError() = runTest {
        val data = listOf(AuthenticationData(BiometricCrypto(null)) {})
        val loading = FakeLoading(Observed.UserAuthenticationRequired(data), Observed.Success)
        val services = adapter(loading = loading, context = null)
        services.fetchRequest(URI)
        assertEquals(SendResult.Error(reason = AdapterGaps.DEVICE_AUTH_FAILED), services.send(listOf("c1")))
        assertEquals(0, loading.sends)
    }

    @Test
    fun aFailedKeyAuthenticationIsErrorAndNothingIsSent() = runTest {
        val data = listOf(AuthenticationData(BiometricCrypto(null)) {})
        val loading = FakeLoading(Observed.UserAuthenticationRequired(data), authSucceeds = false)
        val services = adapter(loading = loading, context = testPlatformContext())
        services.fetchRequest(URI)
        assertEquals(SendResult.Error(reason = AdapterGaps.DEVICE_AUTH_FAILED), services.send(listOf("c1")))
        assertEquals(0, loading.sends)
    }

    @Test
    fun aResponseStreamThatEndsIsError() = runTest {
        val services = adapter(loading = FakeLoading()).also { it.fetchRequest(URI) }
        assertEquals(SendResult.Error(reason = AdapterGaps.NO_RESPONSE_EVENT), services.send(listOf("c1")))
    }

    @Test
    fun sendWithoutAFetchedRequestIsError() = runTest {
        assertEquals(SendResult.Error(reason = AdapterGaps.NOTHING_TO_SEND), adapter().send(listOf("c1")))
    }

    // --- cancel ---

    @Test
    fun cancelBeforeAnyRequestIsNothingToCancel() = runTest {
        val request = FakePresentationRequestInteractor(emptyList())
        assertEquals(CancelResult.NothingToCancel, adapter(request).cancel())
        assertEquals(0, request.rejectCount)
    }

    @Test
    fun cancelAfterTheRequestRejectsStopsAndClosesTheScope() = runTest {
        val request = FakePresentationRequestInteractor(listOf(success(document("d1", "c1", checked = true))))
        val services = adapter(request)
        services.fetchRequest(URI)
        assertEquals(CancelResult.Cancelled, services.cancel())
        assertEquals(1, request.rejectCount)
        assertEquals(1, request.stopCount)
        assertEquals(listOf(request.presentationScopeId), closedScopes)
        assertEquals(CancelResult.NothingToCancel, services.cancel())
    }

    @Test
    fun aThrowingRejectionIsCancelError() = runTest {
        val fake = FakePresentationRequestInteractor(listOf(success(document("d1", "c1", checked = true))))
        val throwing = object : PresentationRequestInteractor by fake {
            override fun rejectPresentation() = error("offline")
        }
        val services = adapter(throwing).also { it.fetchRequest(URI) }
        assertEquals(CancelResult.Error(reason = "offline"), services.cancel())
    }

    @Test
    fun closeTearsDownAnOpenExchangeOnce() = runTest {
        val request = FakePresentationRequestInteractor(listOf(success(document("d1", "c1", checked = true))))
        val services = adapter(request).also { it.fetchRequest(URI) }
        services.close()
        services.close()
        assertEquals(1, request.stopCount)
        assertEquals(0, request.rejectCount)
    }

    private companion object {
        const val URI = "openid4vp://?request_uri=https%3A%2F%2Fverifier.example%2Fr"
    }
}

/** The checkbox state of every leaf row, by row id. */
private fun RequestCombinationUi.checks(): Map<String, Boolean> = documents.flatMap { it.headerUi.nestedItems }.associate { row ->
    row.header.itemId to ((row.header.trailingContentData as ListItemTrailingContentDataUi.Checkbox).checkboxData.isChecked)
}

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

import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.corelogic.di.getOrNullKoinScope
import eu.europa.ec.presentationfeature.interactor.PresentationLoadingInteractor
import eu.europa.ec.presentationfeature.interactor.PresentationRequestInteractor
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.ux.contract.pres.AuthenticateResult
import eu.europa.ec.ux.contract.pres.CancelResult
import eu.europa.ec.ux.contract.pres.FetchRequestResult
import eu.europa.ec.ux.contract.pres.PresCrossDeviceResultDeclaration
import eu.europa.ec.ux.contract.pres.PresCrossDeviceServices
import eu.europa.ec.ux.contract.pres.SendResult
import eu.europa.ec.ux.contract.pres.VerificationCodeResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * M11b (OR-5, a probe): the generated [PresCrossDeviceServices] on top of the app's REAL presentation interactors,
 * with no change to them. Every result it cannot produce honestly is left out of the declaration below, so
 * `PresCrossDeviceServiceResults.undeclared(adapter)` lists exactly the contract gaps (see [AdapterGaps]); a lossy
 * mapping is logged with a `GAP` line when it happens. Nothing is faked.
 *
 * Wiring (debug only, `--ux-spike real`): the hooks resolve the interactors from Koin. [platformContext] is Android's
 * host activity for the biometric prompts (null on iOS, whose prompt belongs to the system). [closeScope] ends the
 * Android presentation Koin scope, which the app's success and request screens close and the contract has no call for.
 */
class InteractorPresCrossDeviceServices(
    private val request: PresentationRequestInteractor,
    private val loading: PresentationLoadingInteractor,
    private val biometrics: BiometricInteractor,
    private val platformContext: () -> PlatformContext? = { null },
    private val closeScope: (String) -> Unit = { getOrNullKoinScope(it)?.close() },
    private val log: (String) -> Unit = { println("UX-SPIKE: $it") },
) : PresCrossDeviceServices, PresCrossDeviceResultDeclaration, AutoCloseable {

    /** The exchange [fetchRequest] started: its first combination (what [send] answers) and whether it ended. */
    private class Exchange(val combination: RequestCombinationUi?, val claimsAreSelectable: Boolean) {
        var finished: Boolean = false
    }

    private var exchange: Exchange? = null

    // The declaration (DEC-081): only what the real interactors can produce. Left out: AdapterGaps.UNDECLARED.
    override val fetchRequestResults: Set<KClass<out FetchRequestResult>> = setOf(
        FetchRequestResult.RequestReady::class,
        FetchRequestResult.OptionalOnly::class,
        FetchRequestResult.RpUntrusted::class,
        FetchRequestResult.DocMissing::class,
        FetchRequestResult.Error::class,
    )

    override val authenticateResults: Set<KClass<out AuthenticateResult>> = setOf(
        AuthenticateResult.Authenticated::class,
        AuthenticateResult.Failed::class,
        AuthenticateResult.NotEnrolled::class,
        AuthenticateResult.Error::class,
    )

    override val verificationCodeResults: Set<KClass<out VerificationCodeResult>> = setOf(
        VerificationCodeResult.Error::class,
    )

    override val sendResults: Set<KClass<out SendResult>> = setOf(
        SendResult.Sent::class,
        SendResult.Error::class,
    )

    override val cancelResults: Set<KClass<out CancelResult>> = setOf(
        CancelResult.Cancelled::class,
        CancelResult.NothingToCancel::class,
        CancelResult.Error::class,
    )

    override suspend fun fetchRequest(uri: String): FetchRequestResult = guarded({ FetchRequestResult.Error(it) }) {
        val fetched = fetchRequestFrom(request, uri, log)
        exchange = Exchange(fetched.combination, fetched.claimsAreSelectable)
        fetched.result
    }

    override suspend fun authenticate(): AuthenticateResult = guarded({ AuthenticateResult.Error(it) }) {
        authenticateWith(biometrics, platformContext(), log)
    }

    /** No source at all: the app has no verification-code step (gap G-VC). An explicit error, never a made-up code. */
    override suspend fun verificationCode(): VerificationCodeResult {
        log("GAP verificationCode: the app has no verification-code source; Error, never a made-up code")
        return VerificationCodeResult.Error(reason = AdapterGaps.NO_VERIFICATION_CODE_SOURCE)
    }

    override suspend fun send(selection: List<String>): SendResult = guarded({ SendResult.Error(it) }) {
        val current = exchange
        val combination = current?.combination
        if (current == null || combination == null || current.finished) {
            return@guarded SendResult.Error(reason = AdapterGaps.NOTHING_TO_SEND)
        }
        val result = sendWith(request, loading, combination, selection, current.claimsAreSelectable, platformContext(), log)
        if (result is SendResult.Sent) finish(current)
        result
    }

    /**
     * Before a response is sent: tells the verifier the user declined (`rejectPresentation`), then the teardown
     * (`stopPresentation`) and the scope. The real calls return nothing, so `Cancelled` means "asked", not "confirmed".
     */
    override suspend fun cancel(): CancelResult = guarded({ CancelResult.Error(it) }) {
        val current = exchange
        if (current == null || current.finished) return@guarded CancelResult.NothingToCancel
        request.rejectPresentation()
        finish(current)
        log("GAP cancel: rejectPresentation/stopPresentation return Unit; Cancelled is not a confirmation")
        CancelResult.Cancelled
    }

    /** The teardown the contract has no call for (gap G-SCOPE): the hook calls it when the flow exits. */
    override fun close() {
        exchange?.let { if (!it.finished) finish(it) }
    }

    private fun finish(current: Exchange) {
        current.finished = true
        request.stopPresentation()
        closeScope(request.presentationScopeId)
    }

    /** A throw becomes the service's `Error` with the message; cancellation is never swallowed. */
    private inline fun <T> guarded(error: (String?) -> T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        log("adapter: ${e::class.simpleName}: ${e.message}")
        error(e.message)
    }
}

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

package eu.europa.ec.uxspike

import eu.europa.ec.ux.contract.pres.AuthenticateResult
import eu.europa.ec.ux.contract.pres.CancelResult
import eu.europa.ec.ux.contract.pres.FetchRequestResult
import eu.europa.ec.ux.contract.pres.PresCrossDeviceInvoker
import eu.europa.ec.ux.contract.pres.PresCrossDeviceResultDeclaration
import eu.europa.ec.ux.contract.pres.PresCrossDeviceServiceResults
import eu.europa.ec.ux.contract.pres.PresCrossDeviceServices
import eu.europa.ec.ux.contract.pres.SendResult
import eu.europa.ec.ux.contract.pres.VerificationCodeResult
import eu.europa.ec.ux.runtime.engine.ServicePort
import kotlin.reflect.KClass

/**
 * The fake app logic behind the spike flow (SPIKE-PLAN §1.6): each method plays the next step of the script for its
 * service, decoded with the contract's serializers. Biometric authentication is whatever the script says; every
 * shipped script says `Authenticated` (always succeed). The real presentation interactors are not involved.
 *
 * Conformance (DEC-081, V33): a script may name any result of the contract, so the fake declares every one, read from
 * the generated `PresCrossDeviceServiceResults.results` (M9r); `PresCrossDeviceServiceResults.undeclared(fake)` is empty
 * (`FakeResultDeclarationTest`) for any export.
 */
class FakePresCrossDeviceServices(val player: FakeScriptPlayer) :
    PresCrossDeviceServices,
    PresCrossDeviceResultDeclaration {
    // M9r (spike rule T1): the declarations come from the generated table of every result, so a result the spec adds is
    // declared with no edit here (the fake plays whatever a script names, decoded by the contract's serializers). The
    // conformance check stays meaningful for a real adapter, which declares by hand what it handles.
    override val fetchRequestResults: Set<KClass<out FetchRequestResult>> = declared("fetchRequest")

    override val authenticateResults: Set<KClass<out AuthenticateResult>> = declared("authenticate")

    override val verificationCodeResults: Set<KClass<out VerificationCodeResult>> = declared("verificationCode")

    override val sendResults: Set<KClass<out SendResult>> = declared("send")

    override val cancelResults: Set<KClass<out CancelResult>> = declared("cancel")

    override suspend fun fetchRequest(uri: String): FetchRequestResult = player.play("fetchRequest", FetchRequestResult.serializer())

    override suspend fun authenticate(): AuthenticateResult = player.play("authenticate", AuthenticateResult.serializer())

    override suspend fun verificationCode(): VerificationCodeResult =
        player.play("verificationCode", VerificationCodeResult.serializer())

    override suspend fun send(selection: List<String>): SendResult = player.play("send", SendResult.serializer())

    override suspend fun cancel(): CancelResult = player.play("cancel", CancelResult.serializer())

    /** The runtime's only way in: the contract invoker behind [ServicePort] (boundary §2.5). */
    fun port(): ServicePort = ServicePort.fromJsonInvoker(invoke = PresCrossDeviceInvoker(this)::invoke)
}

/** Every result class the generated contract lists for [service] (the conformance table, DEC-081). */
@Suppress("UNCHECKED_CAST")
private fun <T : Any> declared(service: String): Set<KClass<out T>> =
    PresCrossDeviceServiceResults.results.getValue(service).values.toSet() as Set<KClass<out T>>

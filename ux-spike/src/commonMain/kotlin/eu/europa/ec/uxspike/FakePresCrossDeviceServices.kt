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
import eu.europa.ec.ux.contract.pres.PresCrossDeviceServices
import eu.europa.ec.ux.contract.pres.SendResult
import eu.europa.ec.ux.contract.pres.VerificationCodeResult
import eu.europa.ec.ux.runtime.engine.ServicePort

/**
 * The fake app logic behind the spike flow (SPIKE-PLAN §1.6): each method plays the next step of the script for its
 * service, decoded with the contract's serializers. Biometric authentication is whatever the script says; every
 * shipped script says `Authenticated` (always succeed). The real presentation interactors are not involved.
 */
class FakePresCrossDeviceServices(val player: FakeScriptPlayer) : PresCrossDeviceServices {
    override suspend fun fetchRequest(uri: String): FetchRequestResult = player.play("fetchRequest", FetchRequestResult.serializer())

    override suspend fun authenticate(): AuthenticateResult = player.play("authenticate", AuthenticateResult.serializer())

    override suspend fun verificationCode(): VerificationCodeResult =
        player.play("verificationCode", VerificationCodeResult.serializer())

    override suspend fun send(selection: List<String>): SendResult = player.play("send", SendResult.serializer())

    override suspend fun cancel(): CancelResult = player.play("cancel", CancelResult.serializer())

    /** The runtime's only way in: the contract invoker behind [ServicePort] (boundary §2.5). */
    fun port(): ServicePort = ServicePort.fromJsonInvoker(invoke = PresCrossDeviceInvoker(this)::invoke)
}

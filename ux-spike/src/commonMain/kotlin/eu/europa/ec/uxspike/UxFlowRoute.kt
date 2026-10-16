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

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The standalone destination of the spike flow. It is a plain [NavKey], not an `AppRoute`, so the wallet's sealed
 * route hierarchy stays untouched (SPIKE-PLAN §1.5).
 *
 * [launch] carries the request the flow was opened for, for example the `openid4vp://` link.
 */
@Serializable
@SerialName("UxFlow")
data class UxFlowRoute(
    val flowId: String,
    val entry: String,
    val launch: String? = null,
) : NavKey

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

// What the proximity screens say when a QR fails: the string catalogue's words, in the user's language, for
// each failure the presenter can name — never the presenter's own English sentence for the log.
package eu.europa.ec.shared.ui.di

import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.wallet.multipaz.IosProximityState
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals

class IosProximityFailureTextTest {

    /** Resolves to the resource's own key, so each case names the entry it reads. */
    private val strings = object : StringCatalog {
        override fun get(resource: StringResource): String = resource.key
        override fun get(resource: StringResource, vararg args: Any): String = resource.key
        override suspend fun warm() = Unit
    }

    @Test
    fun an_expired_qr_is_worded_by_the_catalogue() {
        val failed = IosProximityState.Failed(message = "for the log", reason = IosProximityState.Failed.Reason.QR_EXPIRED)

        assertEquals("proximity_qr_expired", failed.text(strings))
    }

    /** A failure the presenter cannot name keeps the message it came with, as before. */
    @Test
    fun a_failure_without_a_reason_keeps_its_message() {
        assertEquals("multipaz said so", IosProximityState.Failed(message = "multipaz said so").text(strings))
    }
}

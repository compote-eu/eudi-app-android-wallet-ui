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

package eu.europa.ec.shared.resources

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves compose-resources generates the shared string accessors in commonMain and they
 * compile/run on both Android (JVM) and iOS (Kotlin/Native) — the Phase-3a foundation for
 * the KMP presentation layer. (Actual value resolution — `getString(Res.string.*)` — is a
 * suspend call that reads the packaged resources at app runtime; it isn't exercised here
 * because compose-resources' reader is not wired up in plain unit-test environments.)
 */
class SharedStringsTest {

    @Test
    fun shared_string_resources_are_generated_with_expected_keys() {
        assertEquals("generic_error_message", Res.string.generic_error_message.key)
        assertEquals("generic_network_error_message", Res.string.generic_network_error_message.key)
    }
}

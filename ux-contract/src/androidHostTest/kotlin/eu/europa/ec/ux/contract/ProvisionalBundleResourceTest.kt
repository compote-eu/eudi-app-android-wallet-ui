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

// PROVISIONAL (M1b): the bundle resource is a byte copy of the boundary fixture until ux-spec/ supplies it (M6).
package eu.europa.ec.ux.contract

import eu.europa.ec.ux.contract.pres.PresCrossDeviceSpec
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app reads the PROVISIONAL bundle from this module's compose resources; it must be the boundary fixture. */
class ProvisionalBundleResourceTest {

    private val moduleDir = listOf(File("."), File("ux-contract")).first { File(it, "build.gradle.kts").isFile && File(it, "provisional").isDirectory }

    @Test
    fun theBundleResourceIsTheBoundaryFixture() {
        val resource = File(moduleDir, "src/commonMain/composeResources/${PresCrossDeviceSpec.BUNDLE_PATH}")
        val fixture = File(moduleDir, "../spike-evidence/boundary/FLW-pres-cross-device.bundle.PROVISIONAL.json")
        assertTrue(resource.isFile, resource.absolutePath)
        assertEquals(FIXTURE_SHA256, sha256(fixture.readBytes()))
        assertEquals(FIXTURE_SHA256, sha256(resource.readBytes()))
    }

    @Test
    fun theProvisionalSpecHashIsTheFixtureHash() {
        val fixture = File(moduleDir, "src/commonMain/composeResources/${PresCrossDeviceSpec.BUNDLE_PATH}").readText()
        assertTrue("\"spec_hash\": \"${PresCrossDeviceSpec.SPEC_HASH}\"" in fixture || "\"spec_hash\":\"${PresCrossDeviceSpec.SPEC_HASH}\"" in fixture)
        assertTrue(PresCrossDeviceSpec.SPEC_HASH.startsWith("provisional:"))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FIXTURE_SHA256 = "31bfda6579b2a388de96e4a52a618362c53b18b883a1ab239f6856c0dbcf8d71"
    }
}

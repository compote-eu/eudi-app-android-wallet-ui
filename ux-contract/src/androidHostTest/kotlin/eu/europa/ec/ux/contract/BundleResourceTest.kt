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

// M6a (was ProvisionalBundleResourceTest, M1b): the bundle resource is now the export's, read through the lock.
package eu.europa.ec.ux.contract

import eu.europa.ec.ux.contract.pres.PresCrossDeviceSpec
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app reads the bundle from this module's compose resources: the file `ux-spec/` carries and its lock lists. */
class BundleResourceTest {

    private val root = listOf(File("."), File("..")).first { File(it, "ux-spec/UX_SPEC.lock").isFile }
    private val lock = File(root, "ux-spec/UX_SPEC.lock").readText()
    private val resource = File(root, "ux-spec/resources/${PresCrossDeviceSpec.BUNDLE_PATH}")

    @Test
    fun theBundleResourceIsTheLockedExport() {
        assertTrue(resource.isFile, resource.absolutePath)
        val entry = Regex("""\{[^{}]*"path": "resources/${Regex.escape(PresCrossDeviceSpec.BUNDLE_PATH)}"[^{}]*}""").find(lock)
        val sha = entry?.let { Regex(""""sha256": "([0-9a-f]{64})"""").find(it.value)?.groupValues?.get(1) }
        assertEquals(sha, sha256(resource.readBytes()), "the bundle is not the file UX_SPEC.lock lists")
    }

    @Test
    fun theSpecHashIsTheExportHash() {
        val text = resource.readText()
        assertTrue("\"spec_hash\": \"${PresCrossDeviceSpec.SPEC_HASH}\"" in text)
        assertTrue("\"spec_hash\": \"${PresCrossDeviceSpec.SPEC_HASH}\"" in lock)
        assertTrue(PresCrossDeviceSpec.SPEC_HASH.startsWith("sha256:"))
        assertFalse(PresCrossDeviceSpec.SPEC_HASH.startsWith("provisional:"))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

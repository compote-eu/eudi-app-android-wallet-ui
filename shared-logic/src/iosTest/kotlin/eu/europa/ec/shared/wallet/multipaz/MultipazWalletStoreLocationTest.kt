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

// Where the wallet's database lives — a property of the *file system*, which no amount of compiling
// proves. It must not be `NSDocumentDirectory`, which is where multipaz's own
// `Platform.nonBackedUpStorage` puts it and which iOS exposes to the Files app the moment an unrelated
// plist key is set.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class MultipazWalletStoreLocationTest {

    private val manager = NSFileManager.defaultManager

    private fun directory(which: ULong): NSURL? = manager.URLForDirectory(
        directory = which,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )

    private fun storePath(): String? =
        directory(NSApplicationSupportDirectory)
            ?.URLByAppendingPathComponent("wallet", isDirectory = true)
            ?.URLByAppendingPathComponent("wallet.db", isDirectory = false)
            ?.path

    /**
     * The store file and its sidecars, cleared before each run.
     *
     * Clearing them **must** happen, or the test passes vacuously: the file survives from an earlier
     * run, so `fileExistsAtPath` is true even when the store has been pointed at a different name.
     * Caught by deliberately renaming it and watching the test still pass.
     */
    private fun scratchPaths(): List<String> =
        listOfNotNull(
            storePath(),
            storePath()?.plus("-wal"),
            storePath()?.plus("-shm"),
        )

    @BeforeTest
    fun setUp() = scratchPaths().forEach { path -> manager.removeItemAtPath(path, error = null) }

    @AfterTest
    fun tearDown() = scratchPaths().forEach { path -> manager.removeItemAtPath(path, error = null) }

    @Test
    fun the_database_is_created_under_application_support_not_documents() = runTest {
        val expected = storePath()
        assertTrue(expected != null, "no Application Support path could be built")

        MultipazWalletStore.open()

        // Documents is the directory iOS exposes to the Files app the moment an unrelated plist key is
        // set, which is why the wallet's database must not be there.
        assertTrue(
            manager.fileExistsAtPath(expected),
            "no wallet database at $expected",
        )
    }

}

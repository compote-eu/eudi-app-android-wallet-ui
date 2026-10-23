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

package eu.europa.ec.corelogic.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.storagelogic.dao.FailedReIssuedDocumentDao
import eu.europa.ec.testlogic.extension.runTest
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Ported from upstream #729 (`b595f866`'s worker half): a revoked document is never re-issued. */
@RunWith(RobolectricTestRunner::class)
class TestReIssuanceWorkManager {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    private lateinit var failedReIssuedDocumentDao: FailedReIssuedDocumentDao

    @Mock
    private lateinit var walletCoreDocumentsController: WalletCoreDocumentsController

    private lateinit var closeable: AutoCloseable

    private val document: IssuedDocument = mock {
        on { id } doReturn "revoked-document"
    }

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)
    }

    @After
    fun after() {
        closeable.close()
    }

    @Test
    fun `Given a revoked document, When the worker runs, Then it is not re-issued`() {
        coroutineRule.runTest {
            whenever(walletCoreDocumentsController.getAllIssuedDocuments()).thenReturn(listOf(document))
            whenever(walletCoreDocumentsController.isDocumentRevoked(document.id)).thenReturn(true)

            runWorker()

            verify(walletCoreDocumentsController).getAllIssuedDocuments()
            verify(walletCoreDocumentsController).isDocumentRevoked(document.id)
            verifyNoMoreInteractions(walletCoreDocumentsController)
        }
    }

    private suspend fun runWorker(): ListenableWorker.Result {
        val worker =
            TestListenableWorkerBuilder<ReIssuanceWorkManager>(RuntimeEnvironment.getApplication())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = ReIssuanceWorkManager(
                        appContext = appContext,
                        workerParams = workerParameters,
                        failedReIssuedDocumentDao = failedReIssuedDocumentDao,
                        walletCoreDocumentsController = walletCoreDocumentsController,
                    )
                })
                .build()
        return worker.doWork()
    }
}

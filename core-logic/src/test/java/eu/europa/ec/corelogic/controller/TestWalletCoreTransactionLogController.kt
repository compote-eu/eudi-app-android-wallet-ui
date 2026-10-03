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

package eu.europa.ec.corelogic.controller

import eu.europa.ec.corelogic.extension.toStoredCommunicationMethod
import eu.europa.ec.corelogic.model.CommunicationMethodDomain
import eu.europa.ec.corelogic.model.TransactionLogDomain
import eu.europa.ec.corelogic.util.mockedTransactionTime
import eu.europa.ec.eudi.wallet.transactionLogging.model.MultiLangString
import eu.europa.ec.eudi.wallet.transactionLogging.model.TransactionEntry
import eu.europa.ec.eudi.wallet.transactionLogging.model.TransactionResult
import eu.europa.ec.eudi.wallet.transactionLogging.toJson
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.storagelogic.dao.TransactionLogDao
import eu.europa.ec.storagelogic.model.TransactionLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The controller's queue and persistence rules, which upstream leaves untested: saves and deletes run
 * in the order they were queued, and a deletion request or report is stored only under a presentation
 * that exists, once, and never rewritten. The DAO is an in-memory subclass of the real one, so its own
 * metadata-preserving [TransactionLogDao.store] runs too.
 *
 * `runCurrent()`, not `advanceUntilIdle()`: the queue's consumer runs in `backgroundScope`, and
 * `advanceUntilIdle()` stops as soon as only background work is left — a logged entry would never be
 * written, and an empty read would look like a controller bug.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestWalletCoreTransactionLogController {

    private class InMemoryTransactionLogDao : TransactionLogDao() {
        val rows = MutableStateFlow<Map<String, TransactionLog>>(emptyMap())

        override suspend fun retrieve(identifier: String): TransactionLog? = rows.value[identifier]

        override suspend fun retrieveAll(): List<TransactionLog> = rows.value.values.toList()

        override fun observeByParentPresentationId(parentPresentationId: String): Flow<List<TransactionLog>> =
            rows.map { all -> all.values.filter { it.parentPresentationId == parentPresentationId } }

        override suspend fun delete(identifier: String) {
            rows.value = rows.value - identifier
        }

        override suspend fun deleteAll() {
            rows.value = emptyMap()
        }

        override suspend fun upsert(value: TransactionLog) {
            rows.value = rows.value + (value.identifier to value)
        }

        override suspend fun updateStored(value: TransactionLog) {
            if (value.identifier in rows.value) rows.value = rows.value + (value.identifier to value)
        }
    }

    private val resourceProvider: ResourceProvider = mock<ResourceProvider>().also {
        whenever(it.getLocale()).thenReturn(Locale.ENGLISH)
    }

    private val presentation = TransactionEntry.Presentation(
        transactionIdentifier = "presentation",
        time = mockedTransactionTime,
        transactionResult = TransactionResult.Completed,
        listOfClaimsRequested = emptyList(),
        listOfClaimsPresented = emptyList(),
        interactingPartyName = MultiLangString(lang = "en", content = "Verifier"),
    )

    private val signing = TransactionEntry.SigningSealing(
        transactionIdentifier = "signing",
        time = mockedTransactionTime,
        transactionResult = TransactionResult.Completed,
    )

    private fun deletionRequest(id: String = "request", partyName: String = "Verifier") =
        TransactionEntry.DataDeletionRequest(
            id,
            mockedTransactionTime.plusSeconds(60),
            TransactionResult.Completed,
            listOfClaims = emptyList(),
            interactingPartyName = MultiLangString(lang = "en", content = partyName),
        )

    private fun TestScope.controller(dao: InMemoryTransactionLogDao) = WalletCoreTransactionLogControllerImpl(
        transactionLogDao = dao,
        resourceProvider = resourceProvider,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        coroutineScope = backgroundScope,
    )

    @Test
    fun `Given an SDK entry, When it is logged, Then it is read back as the domain`() = runTest {
        val dao = InMemoryTransactionLogDao()
        val controller = controller(dao)

        controller.log(presentation)
        runCurrent()

        val read = assertIs<TransactionLogDomain.Presentation>(controller.getTransactionLogs().single())
        assertEquals("presentation", read.id)
        assertEquals("Verifier", read.party.name?.text)
        assertEquals(read, controller.getTransactionLog("presentation"))
        // The stored payload is the SDK's own encoding.
        assertEquals(presentation.toJson(), dao.rows.value.getValue("presentation").value)
    }

    @Test
    fun `Given a save still queued, When the entry is deleted, Then the save does not bring it back`() = runTest {
        val dao = InMemoryTransactionLogDao()
        val controller = controller(dao)

        controller.log(presentation)
        controller.deleteTransactionLog("presentation")
        runCurrent()

        assertNull(controller.getTransactionLog("presentation"))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `Given a logged presentation, When a deletion request is recorded under it, Then it is stored with its parent and method`() =
        runTest {
            val dao = InMemoryTransactionLogDao()
            val controller = controller(dao)
            controller.log(presentation)
            runCurrent()

            val recorded = controller.recordPresentationAction(
                parentPresentationId = "presentation",
                entry = deletionRequest(),
                communicationMethod = CommunicationMethodDomain.Email,
            )

            assertTrue(recorded)
            val stored = dao.rows.value.getValue("request")
            assertEquals("presentation", stored.parentPresentationId)
            assertEquals(CommunicationMethodDomain.Email.toStoredCommunicationMethod(), stored.communicationMethod)
            val action = controller.observePresentationActions("presentation").first().single()
            assertIs<TransactionLogDomain.DataDeletionRequest>(action)
        }

    @Test
    fun `Given no presentation, a signing parent or itself as parent, When an action is recorded, Then it is refused`() =
        runTest {
            val dao = InMemoryTransactionLogDao()
            val controller = controller(dao)
            controller.log(signing)
            runCurrent()

            assertFalse(
                controller.recordPresentationAction("missing", deletionRequest(), CommunicationMethodDomain.Phone)
            )
            assertFalse(
                controller.recordPresentationAction("signing", deletionRequest(), CommunicationMethodDomain.Phone)
            )
            assertFalse(
                controller.recordPresentationAction("request", deletionRequest(), CommunicationMethodDomain.Phone)
            )
            assertEquals(setOf("signing"), dao.rows.value.keys)
        }

    @Test
    fun `Given a recorded action, When it is retried, Then only an identical retry succeeds and nothing is rewritten`() =
        runTest {
            val dao = InMemoryTransactionLogDao()
            val controller = controller(dao)
            controller.log(presentation)
            runCurrent()
            assertTrue(
                controller.recordPresentationAction("presentation", deletionRequest(), CommunicationMethodDomain.Email)
            )
            val original = dao.rows.value.getValue("request")

            assertTrue(
                controller.recordPresentationAction("presentation", deletionRequest(), CommunicationMethodDomain.Email)
            )
            assertFalse(
                controller.recordPresentationAction("presentation", deletionRequest(), CommunicationMethodDomain.Phone)
            )
            assertFalse(
                controller.recordPresentationAction(
                    "presentation",
                    deletionRequest(partyName = "Someone else"),
                    CommunicationMethodDomain.Email,
                )
            )

            assertEquals(original, dao.rows.value.getValue("request"))
        }

    @Test
    fun `Given a row that is not a readable entry, When the log is read, Then it is dropped and deleted`() = runTest {
        val dao = InMemoryTransactionLogDao()
        val controller = controller(dao)
        controller.log(presentation)
        runCurrent()
        dao.rows.value = dao.rows.value + ("broken" to TransactionLog("broken", "{not json", null, null))

        val read = controller.getTransactionLogs()
        runCurrent()

        assertEquals(listOf("presentation"), read.map { it.id })
        assertFalse("broken" in dao.rows.value)
    }
}

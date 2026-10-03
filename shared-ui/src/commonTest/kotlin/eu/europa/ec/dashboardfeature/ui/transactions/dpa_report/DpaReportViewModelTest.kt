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

// The report screen's view model shares the data-deletion one's open-then-record flow (tested there); what
// it adds is the choice of contact, which must be one the report listed.
package eu.europa.ec.dashboardfeature.ui.transactions.dpa_report

import eu.europa.ec.dashboardfeature.interactor.TransactionDetailsInteractorDpaReportPartialState
import eu.europa.ec.dashboardfeature.ui.transactions.data_deletion.FakeActionInteractor
import eu.europa.ec.dashboardfeature.ui.transactions.detail.model.TransactionDataProtectionAction
import eu.europa.ec.dashboardfeature.ui.transactions.dpa_report.model.DpaReportContactUi
import eu.europa.ec.dashboardfeature.ui.transactions.dpa_report.model.DpaReportUi
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DpaReportViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun contact(url: String) = DpaReportContactUi(
        item = ListItemDataUi(itemId = url, mainContentData = ListItemMainContentDataUi.Text(url)),
        url = url,
    )

    private val report = DpaReportUi(
        authority = "Hellenic Data Protection Authority",
        responsibility = "<b>The wallet doesn't submit this report.</b>",
        followUp = "The authority follows up.",
        contacts = listOf(contact("tel:+302106475600"), contact("mailto:complaints@dpa.example")),
    )

    @Test
    fun a_listed_contact_is_prepared_for_a_report_and_opened() = runTest(mainDispatcher) {
        val fake = FakeActionInteractor(report = TransactionDetailsInteractorDpaReportPartialState.Success(report))
        val viewModel = DpaReportViewModel(fake, "presentation")
        advanceUntilIdle()
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { viewModel.effect.toList(effects) }

        viewModel.setEvent(Event.ContactSelected("mailto:complaints@dpa.example"))
        advanceUntilIdle()

        assertEquals(report, viewModel.viewState.value.report)
        assertEquals(
            listOf(TransactionDataProtectionAction.ReportSuspiciousTransaction to "mailto:complaints@dpa.example"),
            fake.prepared,
        )
        assertEquals(listOf<Effect>(Effect.OpenChannel("attempt-1", "mailto:complaints@dpa.example?launch")), effects)
    }

    @Test
    fun a_contact_the_report_does_not_list_is_ignored() = runTest(mainDispatcher) {
        val fake = FakeActionInteractor(report = TransactionDetailsInteractorDpaReportPartialState.Success(report))
        val viewModel = DpaReportViewModel(fake, "presentation")
        advanceUntilIdle()

        viewModel.setEvent(Event.ContactSelected("https://elsewhere.example"))
        advanceUntilIdle()

        assertTrue(fake.prepared.isEmpty())
    }
}

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

package eu.europa.ec.dashboardfeature.ui.documents.detail

import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.shared.platform.PlatformIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which event each broadcast the details screen listens for raises. On iOS because only there can a test
 * build a `PlatformIntent` with an action and extras; the mapping itself is common code.
 */
class DocumentDetailsBroadcastTest {

    @Test
    fun the_revocation_workers_details_broadcast_refreshes_the_screen() {
        val intent = PlatformIntent(
            action = CoreActions.REVOCATION_WORK_REFRESH_DETAILS_ACTION,
            stringListExtras = mapOf(CoreActions.REVOCATION_IDS_DETAILS_EXTRA to listOf("doc-1")),
        )

        assertEquals(Event.OnRevocationStatusChanged(listOf("doc-1")), detailsEventFor(intent))
    }

    @Test
    fun the_re_issuance_workers_details_broadcast_checks_whether_the_document_is_gone() {
        val intent = PlatformIntent(
            action = CoreActions.RE_ISSUANCE_WORK_REFRESH_DETAILS_ACTION,
            stringListExtras = mapOf(CoreActions.RE_ISSUANCE_IDS_DETAILS_EXTRA to listOf("doc-1")),
        )

        assertEquals(Event.OnReIssuanceTriggered(listOf("doc-1")), detailsEventFor(intent))
    }

    @Test
    fun a_resumed_issuance_and_a_dynamic_presentation_carry_their_link() {
        val link = "eu.europa.ec.euidi://authorization?code=1"

        assertEquals(
            Event.OnResumeIssuance(link),
            detailsEventFor(PlatformIntent(action = CoreActions.VCI_RESUME_ACTION, stringExtras = mapOf("uri" to link))),
        )
        assertEquals(
            Event.OnDynamicPresentation(link),
            detailsEventFor(
                PlatformIntent(action = CoreActions.VCI_DYNAMIC_PRESENTATION, stringExtras = mapOf("uri" to link)),
            ),
        )
        // Without the link there is nothing to resume or present.
        assertNull(detailsEventFor(PlatformIntent(action = CoreActions.VCI_RESUME_ACTION)))
    }

    @Test
    fun an_extras_name_is_not_an_action() {
        // What the revocation branch used to match on: no broadcast is ever sent with it as its action.
        assertNull(detailsEventFor(PlatformIntent(action = CoreActions.REVOCATION_IDS_DETAILS_EXTRA)))
        assertNull(detailsEventFor(PlatformIntent(action = CoreActions.REVOCATION_WORK_REFRESH_ACTION)))
        assertNull(detailsEventFor(null))
    }
}

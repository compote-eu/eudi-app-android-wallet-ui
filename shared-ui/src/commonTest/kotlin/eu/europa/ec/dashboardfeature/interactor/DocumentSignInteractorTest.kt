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

package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.dashboardfeature.ui.document_sign.model.DocumentSignButtonUi
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.document_sign_select_document
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Moved here with its subject when the sign feature became shared.
 *
 * The old Android version also had a `launchRqesSdk` case, which could only assert that calling the
 * `EudiRQESUi` singleton threw in a unit test — it exercised a line without checking behaviour. That
 * responsibility now lives in `rememberDocumentSignTrigger`, whose platform halves are not unit
 * testable either, so the case is dropped rather than reproduced as a delegation check.
 */
class DocumentSignInteractorTest {

    /** Nested rather than top-level: a sibling test in this package already has one by that name. */
    private class FakeStringCatalog(private val values: Map<StringResource, String>) : StringCatalog {
        override fun get(resource: StringResource): String = values.getValue(resource)
        override fun get(resource: StringResource, vararg args: Any): String = get(resource)
        override suspend fun warm() = Unit
    }

    private val interactor: DocumentSignInteractor = DocumentSignInteractorImpl(
        FakeStringCatalog(mapOf(Res.string.document_sign_select_document to SELECT_DOCUMENT_TEXT))
    )

    @Test
    fun getItemUi_builds_the_select_document_row() {
        val result = interactor.getItemUi()

        assertEquals(
            DocumentSignButtonUi(
                data = ListItemDataUi(
                    itemId = "documentSignButtonId",
                    mainContentData = ListItemMainContentDataUi.Text(text = SELECT_DOCUMENT_TEXT),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(iconData = AppIcons.Add),
                )
            ),
            result,
        )
    }
}

private const val SELECT_DOCUMENT_TEXT = "Select document"

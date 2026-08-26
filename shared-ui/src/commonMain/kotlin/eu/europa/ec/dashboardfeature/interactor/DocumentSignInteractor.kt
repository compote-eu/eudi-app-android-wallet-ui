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

/**
 * The shared half of the sign screen.
 *
 * Only the button's presentation lives here now. Launching the signing SDK used to be the other
 * member of this interface and took an Android `Context` and `Uri`; it moved to
 * [eu.europa.ec.dashboardfeature.ui.document_sign.rememberDocumentSignTrigger], because picking a
 * document and handing it over are both scoped to composition on both platforms.
 */
interface DocumentSignInteractor {
    fun getItemUi(): DocumentSignButtonUi
}

class DocumentSignInteractorImpl(
    private val strings: StringCatalog,
) : DocumentSignInteractor {

    override fun getItemUi(): DocumentSignButtonUi {
        return DocumentSignButtonUi(
            data = ListItemDataUi(
                itemId = DOCUMENT_SIGN_BUTTON_ID,
                mainContentData = ListItemMainContentDataUi.Text(
                    text = strings.get(Res.string.document_sign_select_document)
                ),
                trailingContentData = ListItemTrailingContentDataUi.Icon(
                    iconData = AppIcons.Add
                ),
            )
        )
    }
}

private const val DOCUMENT_SIGN_BUTTON_ID = "documentSignButtonId"

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

package eu.europa.ec.uilogic.component.content

import eu.europa.ec.uilogic.component.AppIconAndTextDataUi
import eu.europa.ec.uilogic.component.RelyingPartyDataUi
import eu.europa.ec.uilogic.component.wrap.TextConfig
import kotlinx.serialization.Serializable

/**
 * Data class representing the configuration for a content header.
 * This header typically displays information like app icon, name, description,
 * and potentially relying party details.
 *
 * @property appIconAndTextData Data for displaying the app icon and text.
 * @property description A descriptive text for the content.
 * @property descriptionTextConfig Configuration for the appearance of the description text.
 * @property mainText The main title or heading text.
 * @property mainTextConfig Configuration for the appearance of the main text.
 * @property relyingPartyData Data for displaying information about the relying party, if applicable.
 */
@Serializable
data class ContentHeaderConfig(
    val appIconAndTextData: AppIconAndTextDataUi = AppIconAndTextDataUi(),
    val description: String?,
    val descriptionTextConfig: TextConfig? = null,
    val mainText: String? = null,
    val mainTextConfig: TextConfig? = null,
    val relyingPartyData: RelyingPartyDataUi? = null,
)

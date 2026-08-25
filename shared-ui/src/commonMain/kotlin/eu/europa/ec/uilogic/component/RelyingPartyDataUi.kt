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

package eu.europa.ec.uilogic.component

import eu.europa.ec.shared.resources.UiText
import eu.europa.ec.uilogic.component.wrap.TextConfig
import kotlinx.serialization.Serializable

/**
 * Data class representing information about a Relying Party.
 *
 * [name] is the one field here that is usually *runtime* text — the verifier or issuer name that
 * came off the request — so it is normally a `UiText.Raw`, falling back to a `UiText.Resource`
 * default when the request carried none. [description] is the opposite: always a resource.
 *
 * @property logo An optional logo URL string for the Relying Party (KMP-clean; the image loader
 *   accepts a String source directly).
 * @property isVerified A boolean indicating whether the Relying Party is verified.
 * @property name The name of the Relying Party.
 * @property nameTextConfig Optional [TextConfig] for styling the name text.
 * @property description An optional description of the Relying Party.
 * @property descriptionTextConfig Optional [TextConfig] for styling the description text.
 */
@Serializable
data class RelyingPartyDataUi(
    val logo: String? = null,
    val isVerified: Boolean,
    val name: UiText,
    val nameTextConfig: TextConfig? = null,
    /** The requester's registered identifier, from the registration certificate's subject. */
    val uniqueId: String? = null,
    val description: UiText? = null,
    val descriptionTextConfig: TextConfig? = null,
)

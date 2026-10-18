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

// Phase 3b: the list-item UI *model* split out of :ui-logic's ListItem.kt, where it sat beside the
// composables that render it. Tints are `ColorKey`, not `androidx.compose.ui.graphics.Color` —
// the same keyed-enum pattern `TextConfig` already uses, resolved by `ColorKey.toColor()` in
// :ui-logic. That keeps this model free of Compose-UI types. Package unchanged, so call sites don't
// churn; the composables stay in :ui-logic.
package eu.europa.ec.uilogic.component

import eu.europa.ec.uilogic.component.utils.DEFAULT_ICON_SIZE
import eu.europa.ec.uilogic.component.utils.ICON_SIZE_40
import eu.europa.ec.uilogic.component.wrap.CheckboxDataUi
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.wrap.RadioButtonDataUi
import eu.europa.ec.uilogic.component.wrap.SwitchDataUi

/**
 * Represents the data displayed within a single item in a list.
 *
 * This class encapsulates all the information needed to render a list item,
 * including its content, optional visual elements like leading/trailing icons or checkboxes,
 * and any associated data.
 *
 * @param itemId A unique identifier for this specific list item. This is crucial for identifying
 * the item within the list, especially when handling interactions.
 * @param mainContentData The primary content displayed in the list item. This is typically text
 * but could be other UI elements. See [ListItemMainContentDataUi] for details on how to structure
 * the main content.
 * @param overlineText Optional text displayed above the `mainContentData`, providing context
 * or a brief heading for the item.
 * @param supportingContentData Optional data for content displayed below the `mainContentData`,
 * offering additional details or description to supplement the main content. See
 * [ListItemSupportingContentDataUi] for details on supported supporting content types.
 * @param leadingContentData Optional data for content displayed at the beginning of the list item.
 * This could be an icon, image, or other visual element. See [ListItemLeadingContentDataUi]
 * for details on supported leading content types.
 * @param trailingContentData Optional data for content displayed at the end of the list item.
 * This could be an icon, checkbox, or other interactive element. See [ListItemTrailingContentDataUi]
 * for details on supported trailing content types.
 */
data class ListItemDataUi(
    val itemId: String,
    val mainContentData: ListItemMainContentDataUi,
    val overlineText: String? = null,
    val supportingContentData: ListItemSupportingContentDataUi? = null,
    val leadingContentData: ListItemLeadingContentDataUi? = null,
    val trailingContentData: ListItemTrailingContentDataUi? = null,
)

/**
 * Represents the main content data for an item in a list.
 * This sealed class provides different types of content that can be displayed:
 * - [Text]: Simple text content.
 * - [Image]: An image represented as a Base64 encoded string.
 */
sealed class ListItemMainContentDataUi {
    data class Text(val text: String) : ListItemMainContentDataUi()
    data class Image(val base64Image: String) : ListItemMainContentDataUi()
}

/**
 * Represents the supporting content displayed below a list item's main content.
 *
 * It mirrors the main/leading/trailing content families so a supporting line can carry its own
 * colour, instead of every call site passing one down to [ListItem].
 *
 * @property textColorKey Optional [ColorKey] for the supporting content, resolved at render time.
 * When null, [ListItem] renders it with the default supporting text colour, `onSurfaceVariant`.
 */
sealed class ListItemSupportingContentDataUi {
    abstract val textColorKey: ColorKey?

    data class Text(
        val text: String,
        val maxLines: Int = 1,
        override val textColorKey: ColorKey? = null,
    ) : ListItemSupportingContentDataUi()
}

/**
 * Represents data for the leading content within a list item.
 *
 * This sealed class provides a structured way to define the different types of
 * content that can be displayed at the leading edge of a list item. It supports
 * icons, user images (loaded from base64 strings), images loaded asynchronously
 * from URLs, and radio buttons (for single-choice rows such as selectable cards'
 * headers).
 *
 * Each subclass of `ListItemLeadingContentData` represents a distinct type of
 * leading content, allowing for flexible and varied visual elements in lists.
 *
 * @property size The size (width and height) of the leading content in dp. This determines
 *                 the visual dimensions of the icon, image, etc.
 */
sealed class ListItemLeadingContentDataUi {
    abstract val size: Int?

    data class Icon(
        override val size: Int = DEFAULT_ICON_SIZE,
        val iconData: IconDataUi,
        val tint: ColorKey? = null,
    ) : ListItemLeadingContentDataUi()

    data class UserImage(
        override val size: Int = ICON_SIZE_40,
        val userBase64Image: String,
    ) : ListItemLeadingContentDataUi()

    data class AsyncImage(
        override val size: Int = ICON_SIZE_40,
        val imageUrl: String,
        val contentDescription: String?,
        val errorImage: IconDataUi? = null,
        val placeholderImage: IconDataUi? = null,
    ) : ListItemLeadingContentDataUi()

    data class RadioButton(
        override val size: Int? = null,
        val radioButtonData: RadioButtonDataUi,
    ) : ListItemLeadingContentDataUi()
}

/**
 * Represents the data for the trailing content of a list item.
 *
 * This sealed class defines the possible types of trailing content that can be displayed
 * in a list item, allowing for different visual elements such as icons, checkboxes, and
 * radio buttons.
 *
 * The possible types are:
 *  - [Icon]: Represents an icon to be displayed as trailing content.
 *  - [Checkbox]: Represents a checkbox to be displayed as trailing content.
 *  - [RadioButton]: Represents a radio button to be displayed as trailing content.
 *  - [Switch]: Represents a switch to be displayed as trailing content.
 *  - [TextWithIcon]: Represents text and an icon to be displayed as trailing content.
 */
sealed class ListItemTrailingContentDataUi {
    data class Icon(val iconData: IconDataUi, val tint: ColorKey? = null) :
        ListItemTrailingContentDataUi()

    data class Checkbox(
        val checkboxData: CheckboxDataUi,
        val tint: ColorKey? = null,
    ) : ListItemTrailingContentDataUi()

    data class RadioButton(val radioButtonData: RadioButtonDataUi) : ListItemTrailingContentDataUi()
    data class Switch(val switchData: SwitchDataUi) : ListItemTrailingContentDataUi()
    data class TextWithIcon(
        val text: String,
        val iconData: IconDataUi,
        val tint: ColorKey? = null
    ) : ListItemTrailingContentDataUi()
}

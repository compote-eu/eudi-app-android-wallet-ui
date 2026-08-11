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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.europa.ec.uilogic.component.ClickableArea.ENTIRE_ROW
import eu.europa.ec.uilogic.component.ClickableArea.TRAILING_CONTENT
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.DEFAULT_ICON_SIZE
import eu.europa.ec.uilogic.component.utils.ICON_SIZE_40
import eu.europa.ec.uilogic.component.utils.SIZE_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.CheckboxDataUi
import eu.europa.ec.uilogic.component.wrap.RadioButtonDataUi
import eu.europa.ec.uilogic.component.wrap.SwitchDataUi
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.wrap.TextConfig
import eu.europa.ec.uilogic.component.wrap.TextStyleKey
import eu.europa.ec.uilogic.component.wrap.toColor
import eu.europa.ec.uilogic.component.wrap.WrapAsyncImage
import eu.europa.ec.uilogic.component.wrap.WrapCheckbox
import eu.europa.ec.uilogic.component.wrap.WrapIcon
import eu.europa.ec.uilogic.component.wrap.WrapIconButton
import eu.europa.ec.uilogic.component.wrap.WrapRadioButton
import eu.europa.ec.uilogic.component.wrap.WrapSwitch
import eu.europa.ec.uilogic.component.wrap.WrapText

/**
 * Represents the clickable area of a [ListItem].
 *
 * This enum defines the regions within a [ListItem] that respond to user clicks.
 *
 * @property ENTIRE_ROW  The entire row of the [ListItem] is clickable.
 * @property TRAILING_CONTENT The trailing content (e.g., an icon or checkbox) of the [ListItem] is clickable.
 */
enum class ClickableArea {
    ENTIRE_ROW, TRAILING_CONTENT,
}

/**
 * A composable function that displays a list item with various content options.
 *
 * This function provides a flexible way to display list items with customizable content,
 * including leading and trailing elements, main and supporting text, and optional image content.
 * It also supports hiding sensitive content by blurring it on devices with Android S and above.
 *
 * **Content Customization:**
 * - **Leading Content:** Can be an icon or a user image specified by [ListItemDataUi.leadingContentData].
 * - **Main Content:** Can be text or an image specified by [ListItemDataUi.mainContentData].
 * - **Supporting Text:** Provides additional information below the main content, specified by [ListItemDataUi.supportingText].
 * - **Trailing Content:** Can be a checkbox or an icon specified by [ListItemDataUi.trailingContentData].
 * - **Overline Text:**  Displays text above the main content, specified by [ListItemDataUi.overlineText].
 *
 * **Sensitivity Handling:**
 * - If `hideSensitiveContent` is true and the device supports blurring (Android S and above), the content will be blurred.
 * - On devices that don't support blurring, sensitive content is either hidden or displayed as plain text
 *   depending on the content type (e.g., images are hidden, leading content is hidden, text is displayed).
 *
 * **Click Handling:**
 * - `onItemClick` is invoked when a clickable area of the item is clicked. It receives the [ListItemDataUi] object as a parameter.
 *   This allows you to handle item clicks and perform actions based on the selected item.
 * - `clickableAreas` defines which areas of the list item are clickable. By default, only the trailing content is clickable.
 *    You can set it to [ClickableArea.ENTIRE_ROW] to make the entire row clickable, or provide a custom list of clickable areas.
 *
 * @param item The [ListItemDataUi] object containing the data to display in the list item.
 * @param onItemClick An optional lambda function that is invoked when a clickable area of the item is clicked.
 * @param modifier A [Modifier] that can be used to customize the appearance of the list item.
 * @param hideSensitiveContent A boolean flag indicating whether to hide sensitive content by blurring it. Defaults to false.
 * @param mainContentVerticalPadding An optional value specifying the vertical padding */
@Composable
fun ListItem(
    item: ListItemDataUi,
    onItemClick: ((item: ListItemDataUi) -> Unit)?,
    modifier: Modifier = Modifier,
    hideSensitiveContent: Boolean = false,
    mainContentVerticalPadding: Dp? = null,
    clickableAreas: List<ClickableArea> = listOf(TRAILING_CONTENT),
    overlineTextStyle: TextStyle = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant
    ),
    supportingTextColor: Color? = null,
    mainContentTextStyle: TextStyle? = null,
) {
    val maxSecondaryTextLines = 1
    val textOverflow = TextOverflow.Ellipsis
    val mainTextStyle = mainContentTextStyle ?: MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface
    )

    val blurModifier = if (hideSensitiveContent) {
        Modifier.sensitiveContentBlur()
    } else {
        Modifier
    }

    // Determines the appropriate click handling for a list item's row based on its trailing content.
    // - If the trailing content is a radiobutton, checkbox or switch, the handling is only enabled if it is enabled.
    // - If the trailing content is an icon, or there is no trailing content, the handling is always the provided `onItemClick` function.
    val handleRowItemClick = when (val trailingContentData = item.trailingContentData) {
        is ListItemTrailingContentDataUi.RadioButton ->
            if (trailingContentData.radioButtonData.enabled) onItemClick
            else null

        is ListItemTrailingContentDataUi.Checkbox ->
            if (trailingContentData.checkboxData.enabled) onItemClick
            else null

        is ListItemTrailingContentDataUi.Switch ->
            if (trailingContentData.switchData.enabled) onItemClick
            else null

        is ListItemTrailingContentDataUi.Icon -> onItemClick
        is ListItemTrailingContentDataUi.TextWithIcon -> onItemClick
        null -> onItemClick
    }

    with(item) {
        Row(
            modifier = if (clickableAreas.contains(ENTIRE_ROW) && handleRowItemClick != null) {
                Modifier.clickable {
                    handleRowItemClick(item)
                }
            } else {
                Modifier
            }.then(
                other = modifier.padding(horizontal = SPACING_MEDIUM.dp)
            ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading Content
            leadingContentData?.let { safeLeadingContentData ->
                val leadingContentModifier = Modifier
                    .padding(end = SIZE_MEDIUM.dp)
                    .then(
                        other = safeLeadingContentData.size?.let { safeLeadingContentDataSize ->
                            Modifier.size(safeLeadingContentDataSize.dp)
                        } ?: Modifier
                    )
                    .then(blurModifier)

                when (safeLeadingContentData) {
                    is ListItemLeadingContentDataUi.Icon -> WrapIcon(
                        modifier = leadingContentModifier,
                        iconData = safeLeadingContentData.iconData,
                        customTint = safeLeadingContentData.tint?.toColor()
                            ?: MaterialTheme.colorScheme.primary,
                    )

                    is ListItemLeadingContentDataUi.UserImage -> ImageOrPlaceholder(
                        modifier = leadingContentModifier,
                        base64Image = safeLeadingContentData.userBase64Image,
                    )

                    is ListItemLeadingContentDataUi.AsyncImage -> WrapAsyncImage(
                        modifier = leadingContentModifier,
                        source = safeLeadingContentData.imageUrl,
                        error = safeLeadingContentData.errorImage,
                        placeholder = safeLeadingContentData.placeholderImage,
                        contentDescription = safeLeadingContentData.contentDescription
                    )

                    is ListItemLeadingContentDataUi.RadioButton -> WrapRadioButton(
                        modifier = leadingContentModifier,
                        radioButtonData = safeLeadingContentData.radioButtonData,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = mainContentVerticalPadding ?: SPACING_SMALL.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                // Overline Text
                overlineText?.let { safeOverlineText ->
                    Text(
                        text = safeOverlineText,
                        style = overlineTextStyle,
                    )
                }

                // Main Content
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (mainContentData) {
                        is ListItemMainContentDataUi.Image -> ImageOrPlaceholder(
                            modifier = Modifier
                                .wrapContentWidth()
                                .padding(top = SPACING_SMALL.dp)
                                .then(blurModifier),
                            base64Image = (mainContentData as ListItemMainContentDataUi.Image).base64Image,
                            contentScale = ContentScale.Fit,
                        )

                        is ListItemMainContentDataUi.Text -> Text(
                            modifier = Modifier
                                .weight(1f)
                                .then(blurModifier),
                            text = (mainContentData as ListItemMainContentDataUi.Text).text,
                            style = mainTextStyle,
                            overflow = textOverflow,
                        )
                    }

                    if (trailingContentData is ListItemTrailingContentDataUi.TextWithIcon) {
                        // Bound to a local: `trailingContentData` is declared in :shared-ui now, so
                        // Kotlin will not smart-cast a public API property across modules.
                        val textWithIcon =
                            trailingContentData as ListItemTrailingContentDataUi.TextWithIcon
                        WrapText(
                            modifier = Modifier
                                .padding(start = SIZE_MEDIUM.dp),
                            text = textWithIcon.text,
                            textConfig = TextConfig(
                                styleKey = TextStyleKey.LabelSmall,
                                colorKey = ColorKey.OnSurfaceVariant,
                                maxLines = Int.MAX_VALUE,
                            )
                        )

                        WrapIconButton(
                            modifier = Modifier
                                .padding(start = SPACING_SMALL.dp)
                                .size(DEFAULT_ICON_SIZE.dp),
                            iconData = textWithIcon.iconData,
                            customTint = textWithIcon.tint?.toColor()
                                ?: MaterialTheme.colorScheme.primary,
                            onClick = if (clickableAreas.contains(TRAILING_CONTENT)) {
                                { onItemClick?.invoke(item) }
                            } else null,
                            throttleClicks = false,
                        )
                    }
                }

                // Supporting Text
                supportingText?.let { safeSupportingText ->
                    Text(
                        text = safeSupportingText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = supportingTextColor
                                ?: MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = maxSecondaryTextLines,
                        overflow = textOverflow,
                    )
                }
            }

            // Trailing Content
            trailingContentData?.let { safeTrailingContentData ->
                when (safeTrailingContentData) {
                    is ListItemTrailingContentDataUi.Checkbox -> WrapCheckbox(
                        checkboxData = safeTrailingContentData.checkboxData.copy(
                            onCheckedChange = if (clickableAreas.contains(TRAILING_CONTENT)) {
                                { onItemClick?.invoke(item) }
                            } else null
                        ),
                        modifier = Modifier.padding(start = SIZE_MEDIUM.dp),
                    )

                    is ListItemTrailingContentDataUi.Icon -> WrapIconButton(
                        modifier = Modifier
                            .padding(start = SIZE_MEDIUM.dp)
                            .size(DEFAULT_ICON_SIZE.dp),
                        iconData = safeTrailingContentData.iconData,
                        customTint = safeTrailingContentData.tint?.toColor()
                            ?: MaterialTheme.colorScheme.primary,
                        onClick = if (clickableAreas.contains(TRAILING_CONTENT)) {
                            { onItemClick?.invoke(item) }
                        } else null,
                        throttleClicks = false,
                    )

                    is ListItemTrailingContentDataUi.RadioButton -> WrapRadioButton(
                        radioButtonData = safeTrailingContentData.radioButtonData.copy(
                            onCheckedChange = if (clickableAreas.contains(TRAILING_CONTENT)) {
                                { onItemClick?.invoke(item) }
                            } else null
                        ),
                        modifier = Modifier.padding(start = SIZE_MEDIUM.dp),
                    )

                    is ListItemTrailingContentDataUi.TextWithIcon -> Unit // No-op, it is handled by the main content.

                    is ListItemTrailingContentDataUi.Switch -> WrapSwitch(
                        switchData = safeTrailingContentData.switchData,
                        onCheckedChange = if (clickableAreas.contains(TRAILING_CONTENT)) {
                            { onItemClick?.invoke(item) }
                        } else null,
                        modifier = Modifier.padding(start = SIZE_MEDIUM.dp),
                    )
                }
            }
        }
    }
}

@ThemeModePreviews
@Composable
private fun ListItemPreview() {
    PreviewTheme {
        val modifier = Modifier.fillMaxWidth()
        Column(
            modifier = modifier
                .padding(SPACING_MEDIUM.dp),
            verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp)
        ) {
            // Basic ListItem with only mainText
            ListItem(
                item = ListItemDataUi(
                    itemId = "1",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Basic Item")
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with overlineText and supportingText
            ListItem(
                item = ListItemDataUi(
                    itemId = "2",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Overline and Supporting Text"),
                    overlineText = "Overline Text",
                    supportingText = "Supporting Text"
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with leadingIcon
            ListItem(
                item = ListItemDataUi(
                    itemId = "3",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Leading Icon"),
                    leadingContentData = ListItemLeadingContentDataUi.Icon(iconData = AppIcons.Add),
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with icon for trailing content
            ListItem(
                item = ListItemDataUi(
                    itemId = "4",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Trailing Icon"),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = AppIcons.KeyboardArrowDown,
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with normal text for main content,
            // normal overline and supporting text,
            // and text with icon with normal text for trailing content
            ListItem(
                item = ListItemDataUi(
                    itemId = "5",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with trailing TextWithIcon and Text"),
                    overlineText = "Overline Text",
                    trailingContentData = ListItemTrailingContentDataUi.TextWithIcon(
                        text = "1/2",
                        iconData = AppIcons.KeyboardArrowRight
                    ),
                    supportingText = "Supporting Text"
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with normal text for main content,
            // normal overline text,
            // and text with icon with normal text for trailing content
            ListItem(
                item = ListItemDataUi(
                    itemId = "6",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with trailing TextWithIcon and Text"),
                    overlineText = "Overline Text",
                    trailingContentData = ListItemTrailingContentDataUi.TextWithIcon(
                        text = "1/2",
                        iconData = AppIcons.KeyboardArrowRight
                    ),
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with normal text for main content,
            // and text with icon with normal text for trailing content
            ListItem(
                item = ListItemDataUi(
                    itemId = "7",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with TextWithIcon and Text"),
                    trailingContentData = ListItemTrailingContentDataUi.TextWithIcon(
                        text = "1/2",
                        iconData = AppIcons.KeyboardArrowRight
                    ),
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with very big text for main content,
            // normal overline text,
            // and text with icon with normal text for trailing content
            ListItem(
                item = ListItemDataUi(
                    itemId = "8",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with very very very very very very big text"),
                    overlineText = "Overline Text",
                    trailingContentData = ListItemTrailingContentDataUi.TextWithIcon(
                        text = "1/2",
                        iconData = AppIcons.KeyboardArrowRight
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with normal text for main content,
            // very big overline text,
            // and text with icon with normal text for trailing content
            ListItem(
                item = ListItemDataUi(
                    itemId = "9",
                    overlineText = "Very very very very very very very very very very very very big Overline Text",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with trailing Icon and Text"),
                    trailingContentData = ListItemTrailingContentDataUi.TextWithIcon(
                        text = "1/2",
                        iconData = AppIcons.KeyboardArrowRight
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with normal text for main content,
            // very big overline text,
            // and text with icon with very big text for trailing content
            ListItem(
                item = ListItemDataUi(
                    itemId = "10",
                    overlineText = "Very very very very very very very very very very very very big Overline Text",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with trailing Icon and Text"),
                    trailingContentData = ListItemTrailingContentDataUi.TextWithIcon(
                        text = "Very very big trailing TextWithIcon",
                        iconData = AppIcons.KeyboardArrowRight
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with trailing enabled checkbox
            ListItem(
                item = ListItemDataUi(
                    itemId = "11",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Trailing Enabled Checkbox"),
                    trailingContentData = ListItemTrailingContentDataUi.Checkbox(
                        checkboxData = CheckboxDataUi(
                            isChecked = true,
                        )
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with trailing disabled checkbox
            ListItem(
                item = ListItemDataUi(
                    itemId = "12",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Trailing Disabled Checkbox"),
                    trailingContentData = ListItemTrailingContentDataUi.Checkbox(
                        checkboxData = CheckboxDataUi(
                            isChecked = true,
                            enabled = false,
                        )
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with trailing enabled radiobutton
            ListItem(
                item = ListItemDataUi(
                    itemId = "13",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Trailing Enabled Radiobutton"),
                    trailingContentData = ListItemTrailingContentDataUi.RadioButton(
                        radioButtonData = RadioButtonDataUi(
                            isSelected = true,
                        )
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with trailing disabled radiobutton
            ListItem(
                item = ListItemDataUi(
                    itemId = "14",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Trailing Disabled Radiobutton"),
                    trailingContentData = ListItemTrailingContentDataUi.RadioButton(
                        radioButtonData = RadioButtonDataUi(
                            isSelected = true,
                            enabled = false,
                        )
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with trailing enabled switch
            ListItem(
                item = ListItemDataUi(
                    itemId = "15",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Trailing Enabled Switch"),
                    trailingContentData = ListItemTrailingContentDataUi.Switch(
                        switchData = SwitchDataUi(
                            isChecked = true,
                        )
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with trailing disabled switch
            ListItem(
                item = ListItemDataUi(
                    itemId = "16",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Trailing Disabled Switch"),
                    trailingContentData = ListItemTrailingContentDataUi.Switch(
                        switchData = SwitchDataUi(
                            isChecked = true,
                            enabled = false,
                        )
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with a leading radio button (e.g. a selectable card's header)
            ListItem(
                item = ListItemDataUi(
                    itemId = "leading-radio",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Item with Leading Radiobutton"),
                    leadingContentData = ListItemLeadingContentDataUi.RadioButton(
                        radioButtonData = RadioButtonDataUi(
                            isSelected = true,
                        )
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )

            // ListItem with all elements
            ListItem(
                item = ListItemDataUi(
                    itemId = "17",
                    mainContentData = ListItemMainContentDataUi.Text(text = "Full Item Example"),
                    overlineText = "Overline Text",
                    supportingText = "Supporting Text",
                    leadingContentData = ListItemLeadingContentDataUi.Icon(iconData = AppIcons.Add),
                    trailingContentData = ListItemTrailingContentDataUi.Icon(
                        iconData = AppIcons.KeyboardArrowDown,
                    )
                ),
                modifier = modifier,
                onItemClick = {},
            )
        }
    }
}
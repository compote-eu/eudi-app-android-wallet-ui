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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.europa.ec.resourceslogic.theme.values.success
import eu.europa.ec.shared.resources.asUiText
import eu.europa.ec.shared.resources.resolve
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.TextLengthPreviewProvider
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.SPACING_EXTRA_SMALL
import org.jetbrains.compose.resources.stringResource
import eu.europa.ec.shared.resources.request_relying_party_id_format
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.uilogic.component.wrap.ColorKey
import eu.europa.ec.uilogic.component.utils.ICON_SIZE_40
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.utils.VSpacer
import eu.europa.ec.uilogic.component.wrap.TextAlignKey
import eu.europa.ec.uilogic.component.wrap.TextConfig
import eu.europa.ec.uilogic.component.wrap.TextStyleKey
import eu.europa.ec.uilogic.component.wrap.WrapAsyncImage
import eu.europa.ec.uilogic.component.wrap.WrapIcon
import eu.europa.ec.uilogic.component.wrap.WrapText

/**
 * How [RelyingParty] arranges the logo against the identity block.
 */
enum class RelyingPartyLayout {

    /** Logo beside the identity block, everything start-aligned. */
    InlineStart,

    /** Logo stacked above the identity block, everything centred. */
    StackedCentered,
}

/**
 * The reusable party-identity block: logo, verified badge + name, "(ID: …)" line, optional
 * description.
 */
@Composable
fun RelyingParty(
    modifier: Modifier = Modifier,
    relyingPartyData: RelyingPartyDataUi,
    layout: RelyingPartyLayout = RelyingPartyLayout.StackedCentered,
) {
    when (layout) {
        RelyingPartyLayout.InlineStart -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RelyingPartyIdentity(
                modifier = Modifier.weight(1f),
                relyingPartyData = relyingPartyData,
                horizontalAlignment = Alignment.Start,
                textAlignKey = TextAlignKey.Start,
            )

            relyingPartyData.logo?.let { safeLogo ->
                WrapAsyncImage(
                    modifier = Modifier
                        .padding(all = SPACING_SMALL.dp)
                        .size(ICON_SIZE_40.dp),
                    source = safeLogo,
                    error = AppIcons.Id,
                )
            }
        }

        RelyingPartyLayout.StackedCentered -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            relyingPartyData.logo?.let { safeLogo ->
                WrapAsyncImage(
                    modifier = Modifier.fillMaxWidth(0.5f),
                    source = safeLogo,
                    contentScale = ContentScale.FillWidth,
                    error = AppIcons.Id,
                )
                VSpacer.Small()
            }

            RelyingPartyIdentity(
                modifier = Modifier.fillMaxWidth(),
                relyingPartyData = relyingPartyData,
                horizontalAlignment = Alignment.CenterHorizontally,
                textAlignKey = TextAlignKey.Center,
            )
        }
    }
}

@Composable
private fun RelyingPartyIdentity(
    modifier: Modifier = Modifier,
    relyingPartyData: RelyingPartyDataUi,
    horizontalAlignment: Alignment.Horizontal,
    textAlignKey: TextAlignKey,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
    ) {
        with(relyingPartyData) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isVerified) {
                    WrapIcon(
                        modifier = Modifier
                            .padding(end = SPACING_EXTRA_SMALL.dp)
                            .size(20.dp),
                        iconData = AppIcons.Verified,
                        customTint = MaterialTheme.colorScheme.success,
                    )
                }
                WrapText(
                    modifier = Modifier.wrapContentWidth(),
                    text = name.resolve(),
                    textConfig = nameTextConfig ?: TextConfig(
                        styleKey = TextStyleKey.TitleMedium,
                        textAlignKey = textAlignKey,
                    )
                )
            }

            uniqueId?.let { safeUniqueId ->
                WrapText(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(
                        Res.string.request_relying_party_id_format,
                        safeUniqueId,
                    ),
                    textConfig = TextConfig(
                        styleKey = TextStyleKey.BodySmall,
                        colorKey = ColorKey.OnSurfaceVariant,
                        textAlignKey = textAlignKey,
                    ),
                )
            }

            description?.let { safeDescription ->
                WrapText(
                    modifier = Modifier.fillMaxWidth(),
                    text = safeDescription.resolve(),
                    textConfig = descriptionTextConfig ?: TextConfig(
                        styleKey = TextStyleKey.BodySmall,
                        textAlignKey = textAlignKey,
                    )
                )
            }
        }
    }
}

@ThemeModePreviews
@Composable
private fun RelyingPartyPreview(
    @PreviewParameter(TextLengthPreviewProvider::class) text: String
) {
    PreviewTheme {
        RelyingParty(
            relyingPartyData = RelyingPartyDataUi(
                isVerified = true,
                name = "Relying Party Name: $text".asUiText(),
                description = "Relying Party Description: $text".asUiText(),
            )
        )
    }
}
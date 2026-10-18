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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.europa.ec.shared.resources.resolve
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.SIZE_MEDIUM
import eu.europa.ec.uilogic.component.wrap.ButtonConfig
import eu.europa.ec.uilogic.component.wrap.ButtonType
import eu.europa.ec.uilogic.component.wrap.WrapButton
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.generic_error_button_retry
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.generic_error_retry

@Composable
internal fun ContentError(
    config: ContentErrorConfig,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ContentTitle(
            title = config.errorTitle?.resolve() ?: stringResource(
                Res.string.generic_error_message
            ),
            subtitle = config.errorSubTitle?.resolve() ?: stringResource(
                Res.string.generic_error_retry
            ),
            subTitleMaxLines = 10
        )

        Spacer(modifier = Modifier.weight(1f))

        config.onRetry?.let { callback ->
            WrapButton(
                buttonConfig = ButtonConfig(
                    type = ButtonType.PRIMARY,
                    onClick = {
                        callback()
                    },
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(Res.string.generic_error_button_retry)
                )
            }
        }
    }
}

@ThemeModePreviews
@Composable
private fun PreviewContentErrorWithRetry() {
    PreviewTheme {
        ContentError(
            config = ContentErrorConfig(
                onCancel = {},
                onRetry = {},
            ),
            modifier = Modifier.padding(SIZE_MEDIUM.dp)
        )
    }
}

@ThemeModePreviews
@Composable
private fun PreviewContentErrorWithoutRetry() {
    PreviewTheme {
        ContentError(
            config = ContentErrorConfig(
                onCancel = {},
                onRetry = null,
            ),
            modifier = Modifier.padding(SIZE_MEDIUM.dp)
        )
    }
}
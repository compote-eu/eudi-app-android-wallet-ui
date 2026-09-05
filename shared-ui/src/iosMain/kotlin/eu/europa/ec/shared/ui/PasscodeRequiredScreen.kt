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

package eu.europa.ec.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.passcode_required_description
import eu.europa.ec.shared.resources.passcode_required_open_settings
import eu.europa.ec.shared.resources.passcode_required_title
import eu.europa.ec.uilogic.component.AppIconAndText
import eu.europa.ec.uilogic.component.AppIconAndTextDataUi
import eu.europa.ec.uilogic.component.openIosAppSettings
import eu.europa.ec.uilogic.component.utils.SPACING_EXTRA_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.wrap.ButtonConfig
import eu.europa.ec.uilogic.component.wrap.ButtonType
import eu.europa.ec.uilogic.component.wrap.WrapButton
import org.jetbrains.compose.resources.stringResource

/**
 * Shown instead of the wallet when iOS reports no device passcode.
 *
 * ## Why this is a dead end rather than a dialog
 *
 * There is nothing to dismiss it to. The wallet's documents are Keychain items in a
 * passcode-required class, so on such a device it cannot store a credential, cannot present one, and
 * cannot even keep the PIN that guards it. Offering the dashboard behind an "OK" would be offering a
 * wallet that silently fails at the first write.
 *
 * It resolves itself: setting a passcode in Settings and reopening the app passes the gate.
 *
 * ## 🪤 Where the button can and cannot take them
 *
 * [openIosAppSettings] uses `UIApplicationOpenSettingsURLString`, which is public API and lands on
 * **this app's own pane** — not on Face ID & Passcode, for which there is no public URL. So the
 * button saves a trip to the home screen and no more, and the description has to name the
 * destination pane itself. That is why the copy says where to go rather than relying on the tap.
 *
 * ⚠️ **`App-Prefs:root=PASSCODE` would land exactly right and must not be used**: it is a private
 * scheme, Apple has broken it repeatedly, and shipping it risks review rejection — which for a
 * wallet is a poor trade for one saved tap. The official native EUDI iOS wallet lands on the same
 * pane we do, for the same reason.
 *
 * 🪤 **This is iOS-only by nature, not by omission.** Android's equivalent store is encrypted by the
 * platform's file-based encryption whether or not a lock screen is set, so the same screen there
 * would block users for no reason.
 */
@Composable
internal fun PasscodeRequiredScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SPACING_EXTRA_LARGE.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The same brand lockup the PIN screen opens with — mark plus wordmark, themed — rather than a
        // bare logo. This is the one screen a user can meet *before* the wallet exists, so it should
        // introduce the app the way the first real screen does.
        AppIconAndText(
            modifier = Modifier.padding(bottom = SPACING_EXTRA_LARGE.dp),
            appIconAndTextData = AppIconAndTextDataUi(),
        )
        Text(
            text = stringResource(Res.string.passcode_required_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.passcode_required_description),
            modifier = Modifier.padding(top = SPACING_MEDIUM.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        WrapButton(
            modifier = Modifier.padding(top = SPACING_EXTRA_LARGE.dp),
            buttonConfig = ButtonConfig(
                type = ButtonType.PRIMARY,
                onClick = ::openIosAppSettings,
            ),
        ) {
            Text(text = stringResource(Res.string.passcode_required_open_settings))
        }
    }
}

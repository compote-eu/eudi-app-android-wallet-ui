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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.passcode_required_description
import eu.europa.ec.shared.resources.passcode_required_open_settings
import eu.europa.ec.shared.resources.passcode_required_title
import eu.europa.ec.uilogic.component.AppIconAndText
import eu.europa.ec.uilogic.component.AppIconAndTextDataUi
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.openIosAppSettings
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.ButtonConfig
import eu.europa.ec.uilogic.component.wrap.ButtonType
import eu.europa.ec.uilogic.component.wrap.WrapButton
import org.jetbrains.compose.resources.stringResource

/**
 * Shown instead of the wallet when the Keychain will not store a document and this device has no
 * passcode.
 *
 * ## Why it is built out of the PIN screen's parts
 *
 * This is the only screen a user can meet *before* the wallet exists, and the screen they would
 * otherwise have met first is the PIN one. So it reuses that layout down to the spacing —
 * [ContentScreen] for the frame, the brand lockup, then title and subtitle in the same styles — and
 * diverges only where the PIN field would be, which is replaced by the one action available.
 * Anything else would make the wallet look like it had crashed rather than like it had something to
 * tell you.
 *
 * `ScreenNavigateAction.NONE` because there is nowhere to go back to: the wallet has not started.
 *
 * ## Why this is a dead end rather than a dialog
 *
 * The wallet's documents are Keychain items in a passcode-required class, so on such a device it
 * cannot store a credential, cannot present one, and cannot even keep the PIN that guards it.
 * Offering the dashboard behind an "OK" would be offering a wallet that fails at the first write.
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
 * 🪤 **iOS-only by nature, not by omission.** Android's equivalent store is encrypted by the
 * platform's file-based encryption whether or not a lock screen is set, so the same screen there
 * would block users for no reason.
 */
@Composable
internal fun PasscodeRequiredScreen() {
    ContentScreen(
        navigatableAction = ScreenNavigateAction.NONE,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
        ) {
            AppIconAndText(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SPACING_LARGE.dp),
                appIconAndTextData = AppIconAndTextDataUi(),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SPACING_LARGE.dp),
                verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp, Alignment.Top),
            ) {
                Text(
                    text = stringResource(Res.string.passcode_required_title),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Text(
                    text = stringResource(Res.string.passcode_required_description),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }

            // Where the PIN field sits on the screen this borrows from. Centred rather than
            // stretched: it is one optional shortcut, not the screen's primary input.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SPACING_LARGE.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WrapButton(
                    buttonConfig = ButtonConfig(
                        type = ButtonType.PRIMARY,
                        onClick = ::openIosAppSettings,
                    ),
                ) {
                    Text(text = stringResource(Res.string.passcode_required_open_settings))
                }
            }
        }
    }
}

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
import eu.europa.ec.shared.resources.passcode_required_title
import eu.europa.ec.uilogic.component.AppIconAndText
import eu.europa.ec.uilogic.component.AppIconAndTextDataUi
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
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
 * It resolves itself, and now without a relaunch: [IosAppRoot] re-reads the gate when the app returns
 * to the front, so setting a passcode in Settings and coming back enters the wallet.
 *
 * ## ⛔ Why there is no button, having had one
 *
 * It had a "Go to settings" button, and on a device it landed on **this app's own Settings pane** —
 * where a device passcode cannot be set. `UIApplicationOpenSettingsURLString` is the only public
 * destination and that is where it goes, so the control promised something the platform cannot do.
 *
 * ⛔ **`App-Prefs:` does not rescue it, and this was measured rather than assumed.** On iOS 26.6.1
 * iOS **accepts** the URL and reports `opened=true`, then shows this app's pane anyway — so it fails
 * *and* its own success flag cannot detect the failure, which leaves nothing to fall back on. It is
 * also a private scheme and an App Store rejection risk.
 *
 * ⛔ **Nor a button that quits**: `exit()` is against Apple's guidance precisely because it is
 * indistinguishable from a crash, which is the thing this screen exists to replace.
 *
 * So the description carries the whole instruction, and the recovery above removes the need for a
 * control at all.
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

            // ⛔ THERE IS DELIBERATELY NO BUTTON, and it is not an oversight.
            //
            // It had one, labelled "Go to settings", and on a device it landed on **this app's own
            // Settings pane** — where a device passcode cannot be set at all. `openSettingsURLString`
            // is the only public destination and that is where it goes, so the control read as a
            // promise the platform cannot keep.
            // ⛔ `App-Prefs:` does not rescue it. Measured on iOS 26.6.1: iOS **accepts** the URL and
            // reports `opened=true`, then shows this app's pane anyway — so it fails *and* its own
            // success flag cannot detect the failure, leaving nothing to fall back on. It is also a
            // private scheme and an App Store rejection risk.
            // ⛔ Nor a button that quits the app: `exit()` is against Apple's guidance precisely
            // because it is indistinguishable from a crash — which is what this screen exists to
            // replace.
            //
            // So the sentence is the whole instruction, and the screen recovers by itself instead:
            // the gate is re-read when the app returns to the front, so setting a passcode and coming
            // back enters the wallet with no relaunch. See [IosAppRoot].
        }
    }
}

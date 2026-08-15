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

import androidx.compose.runtime.Composable
import eu.europa.ec.shared.platform.PlatformActivity

/**
 * Always null: `PlatformActivity` has no constructor on iOS, by design. Nothing here can be an NFC card
 * emulator anyway — iOS gives that to Apple Pay and nothing else — so a screen that asks skips the step.
 */
@Composable
actual fun rememberPlatformActivityOrNull(): PlatformActivity? = null

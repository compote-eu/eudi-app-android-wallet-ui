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
 * The host screen, for the one shared screen that has to hand it to a platform binding: NFC engagement
 * is registered against Android's `ComponentActivity`, not a `Context`.
 *
 * Nullable for the same reason as [rememberPlatformContextOrNull], and with the same consequence:
 * `PlatformActivity` is uninhabited on iOS, so a screen skips the action there rather than pretending.
 * That is the honest answer for NFC engagement in particular — iOS does not let an app be an NFC card
 * emulator for mdoc engagement at all, so there is nothing to register.
 */
@Composable
expect fun rememberPlatformActivityOrNull(): PlatformActivity?

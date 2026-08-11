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

package eu.europa.ec.uilogic.extension

import androidx.compose.ui.Modifier

/**
 * Tags this node for UI tests.
 *
 * Per-platform because the *convention* differs, not just the API. On Android the tag is prefixed
 * with the application id and surfaced through [exposeTestTagsAsResourceId] so UI Automator sees it
 * as a `resource-id` — which is what every adb-driven test of this app relies on. iOS has no such
 * notion; XCUITest reads accessibility identifiers, so there the tag is applied plainly.
 */
expect fun Modifier.applyTestTag(testTag: String): Modifier

/**
 * Makes descendants' test tags visible to UI Automator as resource-ids. Android-only in effect; a
 * no-op on iOS, where there is nothing equivalent to opt into.
 */
expect fun Modifier.exposeTestTagsAsResourceId(): Modifier

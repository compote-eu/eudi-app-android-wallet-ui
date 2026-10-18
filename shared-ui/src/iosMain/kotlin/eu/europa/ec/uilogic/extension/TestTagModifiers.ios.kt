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
import androidx.compose.ui.platform.testTag

/**
 * The tag applied plainly — no application-id prefix, since that prefix exists only so Android's UI
 * Automator can read the tag as a `resource-id`. Compose UI tests find it either way.
 */
actual fun Modifier.applyTestTag(testTag: String): Modifier = this.testTag(testTag)

/** No-op: iOS has no resource-id channel to expose test tags through. */
actual fun Modifier.exposeTestTagsAsResourceId(): Modifier = this

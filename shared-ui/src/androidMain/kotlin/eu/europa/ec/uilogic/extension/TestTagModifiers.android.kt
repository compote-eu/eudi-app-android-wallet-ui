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

import android.annotation.SuppressLint
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

@SuppressLint("UnnecessaryComposedModifier")
actual fun Modifier.applyTestTag(testTag: String): Modifier = composed {
    val finalTestTag = createTestTag(
        applicationId = LocalContext.current.packageName,
        testTag = testTag
    )
    return@composed this.then(Modifier.testTag(finalTestTag))
}

actual fun Modifier.exposeTestTagsAsResourceId(): Modifier {
    return this
        .semantics {
            this.testTagsAsResourceId = true
        }
}

private fun createTestTag(applicationId: String, testTag: String): String {
    return "$applicationId:id/$testTag"
}

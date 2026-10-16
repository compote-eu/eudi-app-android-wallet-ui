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


package eu.europa.ec.ux.elements.templates

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import eu.europa.ec.ux.elements.LocalUxHeadingFocus

/**
 * TPL-progress: a step the system is working on. A centred [hero] (normally an `EL-status-hero` with tone pending,
 * which is the polite live region, so a step in progress is announced without moving focus; its title is the h1 and
 * takes [headingFocus]), an optional [appbar] and [steps] above, and at most one bottom action ([actions]: a secondary
 * button that leaves the step; omit it when the step cannot be undone). Test tags: [id], `<id>.appbar`, `<id>.steps`,
 * `<id>.hero`, `<id>.actions`.
 */
@Composable
fun TplProgress(
    id: String,
    hero: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    headingFocus: FocusRequester? = null,
    appbar: (@Composable () -> Unit)? = null,
    steps: (@Composable () -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalUxHeadingFocus provides headingFocus) {
        PageRoot(id, modifier) {
            if (appbar != null) Box(Modifier.testTag("$id.appbar").fillMaxWidth()) { appbar() }
            if (steps != null) Box(Modifier.testTag("$id.steps").fillMaxWidth()) { steps() }
            CenteredScroll("$id.hero", hero)
            if (actions != null) ActionsRegion("$id.actions", actions)
        }
    }
}

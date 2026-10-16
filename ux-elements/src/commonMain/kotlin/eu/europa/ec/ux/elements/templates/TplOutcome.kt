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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import eu.europa.ec.ux.elements.LocalUxHeadingFocus

/**
 * TPL-outcome: the result of a task. One centred [hero] (an `EL-status-hero`; its title is the screen's h1, there is
 * no separate header, and it takes [headingFocus] so the runtime can move focus to it on entry), optional supporting
 * [body] in the same scroll region, an optional [appbar], and the bottom [actions] (primary first). Test tags: [id],
 * `<id>.appbar`, `<id>.hero`, `<id>.body`, `<id>.actions`.
 */
@Composable
fun TplOutcome(
    id: String,
    hero: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    headingFocus: FocusRequester? = null,
    appbar: (@Composable () -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalUxHeadingFocus provides headingFocus) {
        PageRoot(id, modifier) {
            if (appbar != null) Box(Modifier.testTag("$id.appbar").fillMaxWidth()) { appbar() }
            CenteredScroll("$id.hero") {
                hero()
                if (body != null) Column(Modifier.testTag("$id.body").fillMaxWidth(), content = body)
            }
            ActionsRegion("$id.actions", actions)
        }
    }
}

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

package eu.europa.ec.dashboardfeature.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import eu.europa.ec.dashboardfeature.util.TestTag
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.documents_screen_title
import eu.europa.ec.shared.resources.home_screen_title
import eu.europa.ec.shared.resources.transactions_screen_title
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.IconDataUi
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.wrap.WrapIcon
import eu.europa.ec.uilogic.extension.applyTestTag
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The dashboard's three bottom-navigation tabs.
 *
 * These used to be navigation-compose destinations of a nested `NavHost` (the last user of that
 * library after the Nav3 cutover). They are not app destinations: they never appeared on the app's
 * back stack, and — because every tab screen goes through `ContentScreen`, which installs an
 * unconditional `BackHandler`, deeper in the composition than any nav host's — the tab back stack
 * was never popped by a back press either. So a tab is just a selection, and [DashboardScreen]
 * switches on it directly instead of routing to it.
 *
 * [id] is a stable identifier, used to build test tags and to key per-tab saved UI state. Its values
 * are the lowercased former route strings, so the tags they feed are unchanged.
 */
enum class BottomNavigationItem(
    val id: String,
    val titleRes: StringResource,
    val icon: IconDataUi,
) {
    Home(
        id = "home",
        titleRes = Res.string.home_screen_title,
        icon = AppIcons.Home,
    ),
    Documents(
        id = "documents",
        titleRes = Res.string.documents_screen_title,
        icon = AppIcons.Documents,
    ),
    Transactions(
        id = "transactions",
        titleRes = Res.string.transactions_screen_title,
        icon = AppIcons.Transactions,
    );

    companion object {
        /**
         * Persists the selected tab as its [id] rather than relying on JVM serialization of the enum,
         * so the state survives configuration change and process death without an Android-only
         * `Bundle` capability. An unrecognised [id] restores as `null`, which makes `rememberSaveable`
         * fall back to its initial value.
         */
        val Saver: Saver<BottomNavigationItem, String> = Saver(
            save = { it.id },
            restore = { id -> entries.firstOrNull { it.id == id } },
        )
    }
}

@Composable
fun BottomNavigationBar(
    selectedItem: BottomNavigationItem,
    onItemSelected: (BottomNavigationItem) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        BottomNavigationItem.entries.forEach { item ->
            val selected = item == selectedItem

            NavigationBarItem(
                modifier = Modifier.applyTestTag(
                    TestTag.DashboardScreen.bottomNavigationItem(
                        navItem = item.id
                    )
                ),
                icon = {
                    WrapIcon(
                        iconData = item.icon,
                    )
                },
                label = { Text(text = stringResource(item.titleRes)) },
                colors = NavigationBarItemDefaults.colors()
                    .copy(
                        selectedIndicatorColor = MaterialTheme.colorScheme.primary,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
                selected = selected,
                onClick = {
                    if (!selected) {
                        onItemSelected(item)
                    }
                }
            )
        }
    }
}

@ThemeModePreviews
@Composable
private fun BottomNavigationBarPreview() {
    PreviewTheme {
        BottomNavigationBar(
            selectedItem = BottomNavigationItem.Home,
            onItemSelected = {},
        )
    }
}

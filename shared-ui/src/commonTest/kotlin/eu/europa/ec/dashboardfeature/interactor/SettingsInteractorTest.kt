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

// The shared settings-list builder, on the branches a platform *chooses* rather than a user.
//
// Android's `TestSettingsInteractor` covers the list in full against the real
// `AndroidSettingsPlatformBridge` — including the biometrics and changelog conditionals — and those
// cases stayed there when the interactor moved, because they assert behaviour over ConfigLogic,
// PrefKeys and BiometricInteractor. What it cannot reach is a bridge that reports *no logs*:
// `canRetrieveLogs` is a constant per platform, true on Android. That branch and the resulting
// iOS-shaped list are what these cases pin down, and they run on both platforms.
package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsMenuItemType
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.settings_screen_option_biometrics_authentication
import eu.europa.ec.shared.resources.settings_screen_option_changelog
import eu.europa.ec.shared.resources.settings_screen_option_retrieve_logs
import eu.europa.ec.shared.resources.settings_screen_option_show_batch_issuance_counter
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeSettingsPlatformBridge(
    override val appVersion: String = "1.2.3",
    override val changelogUrl: String? = null,
    override val canRetrieveLogs: Boolean = true,
    private val availability: BiometricsAvailability = BiometricsAvailability.CanAuthenticate,
    private val biometricsEnabled: Boolean = false,
    private var batchCounterShown: Boolean = true,
) : SettingsPlatformBridge {

    var batchCounterWrites: Int = 0
        private set
    var lastBatchCounterWrite: Boolean? = null
        private set

    override fun biometricsAvailability(): BiometricsAvailability = availability
    override suspend fun isBiometricsEnabled(): Boolean = biometricsEnabled
    override suspend fun setBiometricsEnabled(enabled: Boolean) = Unit

    override fun authenticateWithBiometrics(
        context: PlatformContext,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit
    ) = Unit

    override fun launchBiometricSystemScreen() = Unit

    override suspend fun isBatchIssuanceCounterShown(): Boolean = batchCounterShown

    override suspend fun setBatchIssuanceCounterShown(shown: Boolean) {
        batchCounterWrites++
        lastBatchCounterWrite = shown
        batchCounterShown = shown
    }

    override fun logShareIntent(): PlatformIntent? = null
}

private class FakeStringCatalog(private val values: Map<StringResource, String>) : StringCatalog {
    override fun get(resource: StringResource): String = values.getValue(resource)
    override fun get(resource: StringResource, vararg args: Any): String = get(resource)
    override suspend fun warm() = Unit
}

private val strings = FakeStringCatalog(
    mapOf(
        Res.string.settings_screen_option_biometrics_authentication to "Biometrics",
        Res.string.settings_screen_option_show_batch_issuance_counter to "Batch counter",
        Res.string.settings_screen_option_retrieve_logs to "Retrieve logs",
        Res.string.settings_screen_option_changelog to "Changelog",
    )
)

class SettingsInteractorTest {

    @Test
    fun a_platform_with_no_logs_omits_the_logs_row() = runTest {
        val interactor = SettingsInteractorImpl(
            strings = strings,
            platform = FakeSettingsPlatformBridge(canRetrieveLogs = false),
        )

        val types = interactor.getSettingsItemsUi(changelogUrl = null).map { it.type }

        assertFalse(SettingsMenuItemType.RETRIEVE_LOGS in types)
        // A row that did nothing when tapped would be worse than no row: on a platform with no log
        // files, `logShareIntent()` can only ever return null.
        assertTrue(SettingsMenuItemType.SHOW_BATCH_ISSUANCE_COUNTER in types)
    }

    @Test
    fun the_logs_row_is_offered_when_the_platform_collects_logs() = runTest {
        val interactor = SettingsInteractorImpl(
            strings = strings,
            platform = FakeSettingsPlatformBridge(canRetrieveLogs = true),
        )

        val types = interactor.getSettingsItemsUi(changelogUrl = null).map { it.type }

        assertTrue(SettingsMenuItemType.RETRIEVE_LOGS in types)
    }

    @Test
    fun the_ios_shaped_bridge_leaves_exactly_the_batch_counter_row() = runTest {
        // No biometrics, no logs, no changelog — what `IosSettingsPlatformBridge` reports today.
        val interactor = SettingsInteractorImpl(
            strings = strings,
            platform = FakeSettingsPlatformBridge(
                availability = BiometricsAvailability.Failure("not here"),
                canRetrieveLogs = false,
                changelogUrl = null,
                batchCounterShown = false,
            ),
        )

        val items = interactor.getSettingsItemsUi(changelogUrl = null)

        assertEquals(1, items.size)
        assertEquals(SettingsMenuItemType.SHOW_BATCH_ISSUANCE_COUNTER, items[0].type)
        val label = assertIs<ListItemMainContentDataUi.Text>(items[0].data.mainContentData)
        assertEquals("Batch counter", label.text)
        // The switch reflects the platform's stored preference, not a default.
        val switch = assertIs<ListItemTrailingContentDataUi.Switch>(items[0].data.trailingContentData)
        assertFalse(switch.switchData.isChecked)
    }

    @Test
    fun toggling_the_batch_counter_writes_the_negation_of_what_the_platform_holds() = runTest {
        val platform = FakeSettingsPlatformBridge(batchCounterShown = true)
        val interactor = SettingsInteractorImpl(strings = strings, platform = platform)

        interactor.toggleShowBatchIssuanceCounter()

        assertEquals(1, platform.batchCounterWrites)
        assertEquals(false, platform.lastBatchCounterWrite)
    }
}

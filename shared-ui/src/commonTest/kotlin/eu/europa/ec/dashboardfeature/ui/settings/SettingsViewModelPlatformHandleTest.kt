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

// The `ItemClicked` branches of SettingsViewModel, split out because the event carries a
// `PlatformContext`. The log-sharing branch used to need an Android `Intent` too; it carries
// plain paths now, so the context is the only platform handle left in here.
//
// The branch worth the most here is the biometrics toggle, which must only flip AFTER a successful
// prompt: flipping first would let a failed or cancelled authentication silently turn the wallet's
// login protection off.
package eu.europa.ec.dashboardfeature.ui.settings

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsMenuItemType
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.testPlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelPlatformHandleTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val context: PlatformContext = testPlatformContext()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun toggling_biometrics_prompts_first_and_flips_only_on_success() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor(authResult = BiometricsAuthenticate.Success)
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        viewModel.setEvent(
            Event.ItemClicked(SettingsMenuItemType.BIOMETRICS_AUTHENTICATION, context)
        )
        advanceUntilIdle()

        assertEquals(1, fake.authPrompts)
        assertEquals(1, fake.biometricsToggles)
    }

    @Test
    fun a_failed_prompt_leaves_biometrics_untouched() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor(authResult = BiometricsAuthenticate.Failed("nope"))
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        viewModel.setEvent(
            Event.ItemClicked(SettingsMenuItemType.BIOMETRICS_AUTHENTICATION, context)
        )
        advanceUntilIdle()

        assertEquals(1, fake.authPrompts)
        // The whole point: a failed prompt must NOT disable login protection.
        assertEquals(0, fake.biometricsToggles)
    }

    @Test
    fun a_cancelled_prompt_leaves_biometrics_untouched() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor(authResult = BiometricsAuthenticate.Cancelled)
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        viewModel.setEvent(
            Event.ItemClicked(SettingsMenuItemType.BIOMETRICS_AUTHENTICATION, context)
        )
        advanceUntilIdle()

        assertEquals(0, fake.biometricsToggles)
    }

    @Test
    fun no_enrolled_biometrics_sends_the_user_to_the_system_screen() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor(availability = BiometricsAvailability.NonEnrolled)
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(
            Event.ItemClicked(SettingsMenuItemType.BIOMETRICS_AUTHENTICATION, context)
        )
        advanceUntilIdle()

        assertIs<Effect.Navigation.LaunchBiometricsSystemScreen>(effect.await())
        assertEquals(0, fake.authPrompts)
    }

    @Test
    fun a_biometrics_failure_surfaces_as_a_snackbar() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor(
            availability = BiometricsAvailability.Failure("hardware unavailable")
        )
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(
            Event.ItemClicked(SettingsMenuItemType.BIOMETRICS_AUTHENTICATION, context)
        )
        advanceUntilIdle()

        val snackbar = assertIs<Effect.ShowSnackbar>(effect.await())
        assertEquals("hardware unavailable", snackbar.message)
    }

    @Test
    fun toggling_the_batch_issuance_counter_rebuilds_the_list() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor()
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()
        val buildsAfterInit = fake.itemsBuilds

        viewModel.setEvent(
            Event.ItemClicked(SettingsMenuItemType.SHOW_BATCH_ISSUANCE_COUNTER, context)
        )
        advanceUntilIdle()

        assertEquals(1, fake.batchCounterToggles)
        // The row's own label reflects the toggle, so the list must be rebuilt.
        assertEquals(buildsAfterInit + 1, fake.itemsBuilds)
    }

    @Test
    fun retrieving_logs_hands_the_paths_the_interactor_reported_to_the_platform() =
        runTest(mainDispatcher) {
            // The effect used to carry an Android `Intent` the interactor had built. It carries paths
            // now, so the same assertion is possible without a platform type — the test stays here
            // only because `Event.ItemClicked` needs a `PlatformContext`, which iOS cannot construct.
            val paths = listOf("/tmp/eudi-ios-wallet-logs.txt", "/tmp/eudi-android-wallet-logs0.txt")
            val fake = FakeSettingsInteractor(logFilePaths = paths)
            val viewModel = SettingsViewModel(fake)
            advanceUntilIdle()

            val effect = async { viewModel.effect.first() }
            viewModel.setEvent(Event.ItemClicked(SettingsMenuItemType.RETRIEVE_LOGS, context))
            advanceUntilIdle()

            val share = assertIs<Effect.ShareLogFiles>(effect.await())
            assertEquals(paths, share.paths)
        }

    @Test
    fun retrieving_logs_with_nothing_to_share_emits_nothing() = runTest(mainDispatcher) {
        // No paths means this platform collects no logs, or none yet. The emptiness check is in the
        // view-model again, now that the value it inspects is something shared code can hold.
        val fake = FakeSettingsInteractor(logFilePaths = emptyList())
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.ItemClicked(SettingsMenuItemType.RETRIEVE_LOGS, context))
        advanceUntilIdle()

        // Nothing was emitted, so a following event is what the collector must see.
        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()
        assertIs<Effect.Navigation.Pop>(effect.await())
    }

    @Test
    fun the_changelog_opens_externally_when_a_url_is_configured() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor(changelogUrl = "https://cl.test/notes")
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.ItemClicked(SettingsMenuItemType.CHANGELOG, context))
        advanceUntilIdle()

        val open = assertIs<Effect.Navigation.OpenUrlExternally>(effect.await())
        // Still a String; the screen parses it at the point of use.
        assertEquals("https://cl.test/notes", open.url)
    }

    @Test
    fun the_changelog_does_nothing_when_no_url_is_configured() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor(changelogUrl = null)
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.ItemClicked(SettingsMenuItemType.CHANGELOG, context))
        advanceUntilIdle()

        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()
        assertIs<Effect.Navigation.Pop>(effect.await())
    }
}

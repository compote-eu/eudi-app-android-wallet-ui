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

// SettingsViewModel. Everything that does not need a `PlatformContext` is here; the `ItemClicked`
// branches carry one, so they live in SettingsViewModelAndroidTest.
//
// `FakeBiometricInteractor` below is shared with BiometricViewModelTest — `SettingsInteractor`
// extends `BiometricInteractor`, so one fake covers both view-models.
package eu.europa.ec.dashboardfeature.ui.settings

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.authenticationlogic.provider.PinLockoutState
import eu.europa.ec.authenticationlogic.secure.SecurePin
import eu.europa.ec.commonfeature.interactor.QuickPinInteractorPinValidPartialState
import eu.europa.ec.dashboardfeature.interactor.SettingsInteractor
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsItemUi
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsMenuItemType
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [eu.europa.ec.commonfeature.interactor.BiometricInteractor] in full, so both this test and
 * BiometricViewModelTest can build on it. Everything is a plain overridable default.
 */
internal open class FakeBiometricInteractor(
    override val maxFailedPinAttempts: Int = 3,
    private val availability: BiometricsAvailability = BiometricsAvailability.CanAuthenticate,
    private val authResult: BiometricsAuthenticate = BiometricsAuthenticate.Success,
    private val biometricUserSelection: Boolean = true,
    private val pinValidResult: QuickPinInteractorPinValidPartialState =
        QuickPinInteractorPinValidPartialState.Success,
    private val lockoutOnEntry: PinLockoutState = PinLockoutState.Idle,
    private val lockoutAfterFailure: PinLockoutState = PinLockoutState.Idle,
) : eu.europa.ec.commonfeature.interactor.BiometricInteractor {

    var authPrompts: Int = 0
        private set
    var systemScreenLaunches: Int = 0
        private set
    var throttleResets: Int = 0
        private set
    var storedDecision: Boolean? = null
        private set

    override fun getBiometricsAvailability(): BiometricsAvailability = availability

    override suspend fun getBiometricUserSelection(): Boolean = biometricUserSelection

    override suspend fun storeBiometricsUsageDecision(shouldUseBiometrics: Boolean) {
        storedDecision = shouldUseBiometrics
    }

    override fun authenticateWithBiometrics(
        context: PlatformContext,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit,
    ) {
        authPrompts++
        listener(authResult)
    }

    override fun launchBiometricSystemScreen() {
        systemScreenLaunches++
    }

    override fun isPinValid(pin: SecurePin): Flow<QuickPinInteractorPinValidPartialState> =
        flow { emit(pinValidResult) }

    override suspend fun getPinLockoutState(): PinLockoutState = lockoutOnEntry

    override suspend fun recordPinFailure(): PinLockoutState = lockoutAfterFailure

    override suspend fun resetPinThrottle() {
        throttleResets++
    }
}

internal class FakeSettingsInteractor(
    private val appVersion: String = "1.2.3",
    private val changelogUrl: String? = "https://changelog.test",
    private val logShareIntent: PlatformIntent? = null,
    availability: BiometricsAvailability = BiometricsAvailability.CanAuthenticate,
    authResult: BiometricsAuthenticate = BiometricsAuthenticate.Success,
) : FakeBiometricInteractor(availability = availability, authResult = authResult),
    SettingsInteractor {

    var itemsBuilds: Int = 0
        private set
    var lastChangelogUrlAsked: String? = null
        private set
    var biometricsToggles: Int = 0
        private set
    var batchCounterToggles: Int = 0
        private set

    override fun getAppVersion(): String = appVersion
    override fun getChangelogUrl(): String? = changelogUrl
    override fun getLogShareIntent(): PlatformIntent? = logShareIntent

    override suspend fun getSettingsItemsUi(changelogUrl: String?): List<SettingsItemUi> {
        itemsBuilds++
        lastChangelogUrlAsked = changelogUrl
        return SettingsMenuItemType.entries.map { type ->
            SettingsItemUi(
                type = type,
                data = ListItemDataUi(
                    itemId = type.itemId,
                    mainContentData = ListItemMainContentDataUi.Text(type.name),
                ),
            )
        }
    }

    override suspend fun toggleBiometricsAuthentication() {
        biometricsToggles++
    }

    override suspend fun toggleShowBatchIssuanceCounter() {
        batchCounterToggles++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(mainDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_settings_list_is_built_on_construction() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor()
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        // From `init`, not an event — otherwise the list came back empty after process death.
        assertEquals(1, fake.itemsBuilds)
        val state = viewModel.viewState.value
        assertFalse(state.isLoading)
        assertEquals(SettingsMenuItemType.entries.size, state.settingsItems.size)
    }

    @Test
    fun the_app_version_and_changelog_url_come_from_the_interactor() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor(appVersion = "9.9.9", changelogUrl = "https://cl.test")
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertEquals("9.9.9", state.appVersion)
        assertEquals("https://cl.test", state.changelogUrl)
        // ...and the same URL is what the item builder is asked for.
        assertEquals("https://cl.test", fake.lastChangelogUrlAsked)
    }

    @Test
    fun a_null_changelog_url_is_carried_through_as_null() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor(changelogUrl = null)
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        assertNull(viewModel.viewState.value.changelogUrl)
        assertNull(fake.lastChangelogUrlAsked)
    }

    @Test
    fun an_explicit_init_rebuilds_the_list() = runTest(mainDispatcher) {
        val fake = FakeSettingsInteractor()
        val viewModel = SettingsViewModel(fake)
        advanceUntilIdle()

        viewModel.setEvent(Event.Init)
        advanceUntilIdle()

        // Unlike the request/loading screens, this one has no once-only guard: rebuilding is cheap
        // and the list must reflect toggles made elsewhere.
        assertEquals(2, fake.itemsBuilds)
    }

    @Test
    fun popping_navigates_back() = runTest(mainDispatcher) {
        val viewModel = SettingsViewModel(FakeSettingsInteractor())
        advanceUntilIdle()

        val effect = async { viewModel.effect.first() }
        viewModel.setEvent(Event.Pop)
        advanceUntilIdle()

        assertIs<Effect.Navigation.Pop>(effect.await())
    }

    @Test
    fun launching_the_biometric_system_screen_delegates_to_the_interactor() =
        runTest(mainDispatcher) {
            val fake = FakeSettingsInteractor()
            val viewModel = SettingsViewModel(fake)
            advanceUntilIdle()

            viewModel.setEvent(Event.LaunchBiometricSystemScreen)
            advanceUntilIdle()

            // No effect here: the interactor owns the Android intent.
            assertEquals(1, fake.systemScreenLaunches)
        }

    @Test
    fun every_menu_item_type_is_rendered() = runTest(mainDispatcher) {
        val viewModel = SettingsViewModel(FakeSettingsInteractor())
        advanceUntilIdle()

        val types = viewModel.viewState.value.settingsItems.map { it.type }
        assertTrue(types.containsAll(SettingsMenuItemType.entries.toList()))
    }
}

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

// Moved to commonMain with `SettingsScreen`. The list building is the substance and it is identical on
// both platforms — the same rows, icons and switches out of four strings, two booleans and a nullable
// URL — so it is shared, and everything it cannot know for itself sits behind
// [SettingsPlatformBridge]. `ResourceProvider` became `StringCatalog`, as with the other interactors.
package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAuthenticate
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsItemUi
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsMenuItemType
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.settings_screen_option_biometrics_authentication
import eu.europa.ec.shared.resources.settings_screen_option_changelog
import eu.europa.ec.shared.resources.settings_screen_option_retrieve_logs
import eu.europa.ec.shared.resources.settings_screen_option_registration_check
import eu.europa.ec.shared.resources.settings_screen_option_registration_check_restart
import eu.europa.ec.shared.resources.settings_screen_option_show_batch_issuance_counter
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemLeadingContentDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.wrap.SwitchDataUi

class SettingsInteractorImpl(
    private val strings: StringCatalog,
    private val platform: SettingsPlatformBridge,
) : SettingsInteractor {

    override fun getAppVersion(): String = platform.appVersion

    override fun getChangelogUrl(): String? = platform.changelogUrl

    override fun getLogFilePaths(): List<String> = platform.logFilePaths()

    override fun getBiometricsAvailability(): BiometricsAvailability =
        platform.biometricsAvailability()

    override fun authenticateWithBiometrics(
        context: PlatformContext?,
        notifyOnAuthenticationFailure: Boolean,
        listener: (BiometricsAuthenticate) -> Unit
    ) = platform.authenticateWithBiometrics(context, notifyOnAuthenticationFailure, listener)

    override val canOpenBiometricEnrolment: Boolean get() = platform.canOpenBiometricEnrolment

    override fun launchBiometricSystemScreen() = platform.launchBiometricSystemScreen()

    override suspend fun getSettingsItemsUi(changelogUrl: String?): List<SettingsItemUi> {
        val deviceSupportsBiometrics = deviceSupportsBiometrics()

        return buildList {
            if (deviceSupportsBiometrics) {
                add(
                    SettingsItemUi(
                        type = SettingsMenuItemType.BIOMETRICS_AUTHENTICATION,
                        data = ListItemDataUi(
                            itemId = SettingsMenuItemType.BIOMETRICS_AUTHENTICATION.itemId,
                            mainContentData = ListItemMainContentDataUi.Text(
                                text = strings[Res.string.settings_screen_option_biometrics_authentication]
                            ),
                            leadingContentData = ListItemLeadingContentDataUi.Icon(
                                iconData = AppIcons.TouchId
                            ),
                            trailingContentData = ListItemTrailingContentDataUi.Switch(
                                switchData = SwitchDataUi(
                                    isChecked = platform.isBiometricsEnabled(),
                                    enabled = true,
                                )
                            )
                        )
                    )
                )
            }

            add(
                SettingsItemUi(
                    type = SettingsMenuItemType.SHOW_BATCH_ISSUANCE_COUNTER,
                    data = ListItemDataUi(
                        itemId = SettingsMenuItemType.SHOW_BATCH_ISSUANCE_COUNTER.itemId,
                        mainContentData = ListItemMainContentDataUi.Text(
                            text = strings[Res.string.settings_screen_option_show_batch_issuance_counter]
                        ),
                        leadingContentData = ListItemLeadingContentDataUi.Icon(
                            iconData = AppIcons.BatchIssuanceCounter
                        ),
                        trailingContentData = ListItemTrailingContentDataUi.Switch(
                            switchData = SwitchDataUi(
                                isChecked = platform.isBatchIssuanceCounterShown(),
                                enabled = true,
                            )
                        )
                    )
                )
            )

            if (platform.canCheckRegistrations) {
                add(
                    SettingsItemUi(
                        type = SettingsMenuItemType.REGISTRATION_CHECK,
                        data = ListItemDataUi(
                            itemId = SettingsMenuItemType.REGISTRATION_CHECK.itemId,
                            mainContentData = ListItemMainContentDataUi.Text(
                                text = strings[Res.string.settings_screen_option_registration_check]
                            ),
                            leadingContentData = ListItemLeadingContentDataUi.Icon(
                                iconData = AppIcons.Verified
                            ),
                            trailingContentData = ListItemTrailingContentDataUi.Switch(
                                switchData = SwitchDataUi(
                                    isChecked = platform.isRegistrationCheckEnabled(),
                                    enabled = true,
                                )
                            )
                        )
                    )
                )
            }

            if (platform.canRetrieveLogs) {
                add(
                    SettingsItemUi(
                        type = SettingsMenuItemType.RETRIEVE_LOGS,
                        data = ListItemDataUi(
                            itemId = SettingsMenuItemType.RETRIEVE_LOGS.itemId,
                            mainContentData = ListItemMainContentDataUi.Text(
                                text = strings[Res.string.settings_screen_option_retrieve_logs]
                            ),
                            leadingContentData = ListItemLeadingContentDataUi.Icon(
                                iconData = AppIcons.OpenNew
                            ),
                            trailingContentData = ListItemTrailingContentDataUi.Icon(
                                iconData = AppIcons.KeyboardArrowRight
                            )
                        )
                    )
                )
            }

            if (changelogUrl != null) {
                add(
                    SettingsItemUi(
                        type = SettingsMenuItemType.CHANGELOG,
                        data = ListItemDataUi(
                            itemId = SettingsMenuItemType.CHANGELOG.itemId,
                            mainContentData = ListItemMainContentDataUi.Text(
                                text = strings[Res.string.settings_screen_option_changelog]
                            ),
                            leadingContentData = ListItemLeadingContentDataUi.Icon(
                                iconData = AppIcons.OpenInBrowser
                            ),
                            trailingContentData = ListItemTrailingContentDataUi.Icon(
                                iconData = AppIcons.KeyboardArrowRight
                            )
                        )
                    )
                )
            }
        }
    }

    override suspend fun isBiometricsEnabled(): Boolean = platform.isBiometricsEnabled()

    override suspend fun setBiometricsAuthentication(enabled: Boolean) {
        platform.setBiometricsEnabled(enabled = enabled)
    }

    override suspend fun toggleShowBatchIssuanceCounter() {
        platform.setBatchIssuanceCounterShown(shown = !platform.isBatchIssuanceCounterShown())
    }

    override suspend fun toggleRegistrationCheck() {
        platform.setRegistrationCheckEnabled(enabled = !platform.isRegistrationCheckEnabled())
    }

    override val registrationCheckRestartMessage: String
        get() = strings[Res.string.settings_screen_option_registration_check_restart]

    private fun deviceSupportsBiometrics(): Boolean {
        return when (platform.biometricsAvailability()) {
            is BiometricsAvailability.CanAuthenticate,
            is BiometricsAvailability.NonEnrolled -> true

            is BiometricsAvailability.Failure -> false
        }
    }
}

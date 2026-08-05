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

// Phase 3b: the interactor *contract* moves to commonMain so `SettingsViewModel` can live there, per the
// `SplashInteractor` pattern; `SettingsInteractorImpl` keeps ConfigLogic, LogController, PrefKeys and
// ResourceProvider in :dashboard-feature.
//
// This one needed a real change rather than a retype. The view-model used to BUILD the log-sharing
// intent itself — `Intent().apply { action = ACTION_SEND_MULTIPLE; putParcelableArrayListExtra(...) }` —
// and an opaque `PlatformIntent` cannot be constructed from common code. So [getLogShareIntent] moves
// that construction to the implementation, which already owned the log files; it returns null when there
// is nothing to share, which is the emptiness check the view-model used to do. `retrieveLogFileUris`
// (an `ArrayList<Uri>`) is deliberately NOT on this contract any more: it is a platform detail that only
// the implementation needs now.
package eu.europa.ec.dashboardfeature.interactor

import eu.europa.ec.commonfeature.interactor.BiometricInteractor
import eu.europa.ec.dashboardfeature.ui.settings.model.SettingsItemUi
import eu.europa.ec.shared.platform.PlatformIntent

interface SettingsInteractor : BiometricInteractor {
    fun getAppVersion(): String
    fun getChangelogUrl(): String?

    /**
     * An intent that shares the collected log files, or null when there are none to share.
     */
    fun getLogShareIntent(): PlatformIntent?
    suspend fun getSettingsItemsUi(changelogUrl: String?): List<SettingsItemUi>
    suspend fun toggleBiometricsAuthentication()
    suspend fun toggleShowBatchIssuanceCounter()
}

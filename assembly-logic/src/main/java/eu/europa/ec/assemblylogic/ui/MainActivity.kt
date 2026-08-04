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

package eu.europa.ec.assemblylogic.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.europa.ec.commonfeature.router.featureCommonEntries
import eu.europa.ec.dashboardfeature.router.featureDashboardEntries
import eu.europa.ec.issuancefeature.router.featureIssuanceEntries
import eu.europa.ec.presentationfeature.router.presentationEntries
import eu.europa.ec.proximityfeature.router.featureProximityEntries
import eu.europa.ec.startupfeature.router.featureStartupEntries
import eu.europa.ec.uilogic.container.EudiComponentActivity

class MainActivity : EudiComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Content(intent) { navigator ->
                featureStartupEntries(navigator)
                featureCommonEntries(navigator)
                featureDashboardEntries(navigator)
                presentationEntries(navigator)
                featureProximityEntries(navigator)
                featureIssuanceEntries(navigator)
            }
        }
    }
}

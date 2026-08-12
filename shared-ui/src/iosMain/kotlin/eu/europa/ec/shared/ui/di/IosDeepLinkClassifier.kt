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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.uilogic.navigation.helper.DeepLinkClassifier
import eu.europa.ec.uilogic.navigation.helper.DeepLinkKind

/**
 * Classifies nothing, because iOS receives no deep links yet — they would arrive through the app
 * delegate's URL handling, which is unbuilt.
 *
 * A stub rather than a port: `DeepLinkClassifierImpl` parses with `androidx.core.net.toUri`, and
 * hand-rolling a second parser here would be logic that drifts from Android's for no present benefit.
 * When iOS gains deep links, make the *shared* classifier multiplatform (a KMP URL parser) rather than
 * writing a rival implementation here.
 */
internal class IosDeepLinkClassifier : DeepLinkClassifier {
    override fun classify(link: String): DeepLinkKind? = null
}

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

package eu.europa.ec.uilogic.navigation.helper

/**
 * Answers with a fixed [DeepLinkKind] — or null for "not a deep link" — and records what it was
 * asked. Shared by every view-model test that dispatches on an incoming link, since the real
 * classification is Android's (an `android.net.Uri` parse against per-flavour BuildConfig schemes).
 */
internal class FakeDeepLinkClassifier(
    private val kind: DeepLinkKind? = null,
) : DeepLinkClassifier {

    var classifiedLinks: MutableList<String> = mutableListOf()
        private set

    override fun classify(link: String): DeepLinkKind? {
        classifiedLinks.add(link)
        return kind
    }
}

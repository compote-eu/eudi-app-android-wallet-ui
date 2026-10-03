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

// Upstream bf514519's Trust Mark interactor, in shared code: strings through [StringCatalog], the URL rules
// through [ParsedUri] (java.net.URI's own rules, which upstream's cases pin), and the introduction flag
// through the shared [TrustMarkIntroductionStore]. Package unchanged.
package eu.europa.ec.commonfeature.interactor

import eu.europa.ec.businesslogic.controller.storage.TrustMarkIntroductionStore
import eu.europa.ec.businesslogic.extension.ioDispatcher
import eu.europa.ec.businesslogic.util.ParsedUri
import eu.europa.ec.commonfeature.ui.trustmark.model.TrustMarkParagraphUi
import eu.europa.ec.commonfeature.ui.trustmark.model.TrustMarkUi
import eu.europa.ec.corelogic.controller.WalletCoreTrustMarkController
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_message
import eu.europa.ec.shared.resources.trust_mark_certification_description
import eu.europa.ec.shared.resources.trust_mark_certification_information_description
import eu.europa.ec.shared.resources.trust_mark_certification_information_link
import eu.europa.ec.shared.resources.trust_mark_certified_wallets_link
import eu.europa.ec.shared.resources.trust_mark_load_error
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource

sealed interface LoadTrustMarkPartialState {
    data class Success(val trustMark: TrustMarkUi) : LoadTrustMarkPartialState
    data class Failure(val error: String) : LoadTrustMarkPartialState
}

sealed interface CompleteTrustMarkIntroductionPartialState {
    data object Success : CompleteTrustMarkIntroductionPartialState
    data class Failure(val error: String) : CompleteTrustMarkIntroductionPartialState
}

interface TrustMarkInteractor {
    suspend fun getTrustMark(): LoadTrustMarkPartialState
    suspend fun completeIntroduction(): CompleteTrustMarkIntroductionPartialState
}

class TrustMarkInteractorImpl(
    private val walletCoreTrustMarkController: WalletCoreTrustMarkController,
    private val introductionStore: TrustMarkIntroductionStore,
    private val strings: StringCatalog,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) : TrustMarkInteractor {

    private val genericErrorMsg
        get() = strings[Res.string.generic_error_message]

    override suspend fun getTrustMark(): LoadTrustMarkPartialState =
        withContext(dispatcher) {
            runCatching<LoadTrustMarkPartialState> {
                val trustMark = walletCoreTrustMarkController.getTrustMark().getOrThrow()
                LoadTrustMarkPartialState.Success(
                    trustMark = TrustMarkUi(
                        imageUrl = resolveImageUrl(trustMark.imageUrl, trustMark.resourceUrl),
                        text = trustMark.localisedText,
                        certifiedWalletsUrl = usableWebUrl(trustMark.certifiedWalletsUrl),
                        walletSolutionUrl = usableWebUrl(trustMark.walletSolutionUrl),
                        certificationDescription = linkedParagraph(
                            textRes = Res.string.trust_mark_certification_description,
                            linkRes = Res.string.trust_mark_certified_wallets_link,
                        ),
                        certificationInformationDescription = linkedParagraph(
                            textRes = Res.string.trust_mark_certification_information_description,
                            linkRes = Res.string.trust_mark_certification_information_link,
                        )
                    )
                )
            }.getOrElse {
                LoadTrustMarkPartialState.Failure(
                    error = strings[Res.string.trust_mark_load_error]
                )
            }
        }

    override suspend fun completeIntroduction(): CompleteTrustMarkIntroductionPartialState =
        withContext(dispatcher) {
            runCatching<CompleteTrustMarkIntroductionPartialState> {
                introductionStore.setTrustMarkIntroductionCompleted(value = true)
                CompleteTrustMarkIntroductionPartialState.Success
            }.getOrElse {
                CompleteTrustMarkIntroductionPartialState.Failure(
                    error = it.message ?: genericErrorMsg
                )
            }
        }

    private fun linkedParagraph(textRes: StringResource, linkRes: StringResource): TrustMarkParagraphUi {
        val label = strings[linkRes]
        val text = strings.get(textRes, label)
        val start = text.indexOf(label)
        return TrustMarkParagraphUi(
            text = text,
            linkRange = if (label.isNotBlank() && start >= 0) {
                start until start + label.length
            } else {
                null
            }
        )
    }

    private fun resolveImageUrl(imageUrl: String, resourceUrl: String): String? {
        if (imageUrl.isBlank()) return null
        val resolved = ParsedUri.parseOrNull(resourceUrl)?.resolve(imageUrl)
        return resolved?.let { url -> usableWebUrl(value = url) }
    }

    private fun usableWebUrl(value: String): String? {
        val uri = ParsedUri.parseOrNull(value) ?: return null
        return value.takeIf {
            (uri.scheme.equals("https", ignoreCase = true)
                    || uri.scheme.equals("http", ignoreCase = true))
                    && !uri.host.isNullOrBlank()
                    && uri.rawUserInfo == null
        }
    }
}

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

package eu.europa.ec.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import eu.europa.ec.commonfeature.ui.request.model.RequestCombinationUi
import eu.europa.ec.commonfeature.ui.request.model.RequestDocumentItemUi
import eu.europa.ec.shared.ui.di.SharedUiModule
import eu.europa.ec.uilogic.extension.toggleCheckboxState
import eu.europa.ec.resourceslogic.theme.ThemeManager
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.request_combination_option_title
import eu.europa.ec.shared.ui.di.keptDocuments
import eu.europa.ec.shared.ui.di.module as sharedUiDefinitions
import eu.europa.ec.shared.ui.di.toCombinationsUi
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentDisclosure
import eu.europa.ec.shared.wallet.multipaz.IosPresentmentRequest
import eu.europa.ec.uilogic.component.wrap.ButtonConfig
import eu.europa.ec.uilogic.component.wrap.ButtonType
import eu.europa.ec.uilogic.component.wrap.WrapButton
import eu.europa.ec.uilogic.component.wrap.WrapExpandableListItem
import eu.europa.ec.uilogic.component.wrap.WrapSelectableCard
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

/**
 * The consent screen the **document-provider extension** shows, as a `UIViewController` Swift can host.
 *
 * ## Why this exists rather than a SwiftUI screen
 *
 * The extension is a separate process and Apple hands it a SwiftUI scene, which made a hand-written
 * native consent UI look unavoidable — it was scoped for weeks as this feature's one architectural
 * cost. It is not: Compose Multiplatform renders inside an ExtensionKit extension, and our build
 * already copies `compose-resources` into the extension bundle. So the wallet asks for consent in the
 * *same* screen everywhere, and a change to how claims are presented cannot drift between the app and
 * the extension.
 *
 * ## Why not `RequestScreen`
 *
 * The app's consent screen takes a `RequestViewModel` and an `AppNavigator` — a whole navigation graph
 * the extension does not have and should not start. What is shared instead is everything *below* that:
 * the same [toCombinationsUi] mapping, the same [WrapExpandableListItem] component, the same
 * [toggleCheckboxState] toggle, and the same [keptDocuments] conversion the remote and proximity
 * coordinators use to turn ticks into disclosures. **The pixels and the selection semantics are shared;
 * only the scaffolding is not.**
 *
 * ## The rule this screen must not break
 *
 * Returning `null` refuses. So does returning disclosures that keep nothing — multipaz builds the
 * response from exactly the claims a selection carries, so an empty one would mean "share a document
 * with none of its claims". [onDecision] is called **exactly once**.
 */
@Suppress("FunctionNaming", "Unused")
fun DcApiConsentViewController(
    request: IosPresentmentRequest,
    onDecision: (List<IosPresentmentDisclosure>?) -> Unit,
): UIViewController {
    // The extension process has no Koin graph: the app started one at launch, in a process this is not.
    // Starting it here is what makes the string catalog and the theme resolvable, and it is the same
    // thing `IosAppRoot` does for the app.
    startKoinIfNeeded()
    val strings = KoinPlatform.getKoin().get<StringCatalog>()
    // Warmed synchronously for the same reason the app warms it: the catalog resolves without a
    // coroutine once warm, which is what lets the mapping below read strings while composing.
    runBlocking { strings.warm() }

    return ComposeUIViewController {
        ThemeManager.instance.Theme {
            Surface(modifier = Modifier.fillMaxSize()) {
                DcApiConsentScreen(
                    request = request,
                    strings = strings,
                    onDecision = onDecision,
                )
            }
        }
    }
}

@Composable
private fun DcApiConsentScreen(
    request: IosPresentmentRequest,
    strings: StringCatalog,
    onDecision: (List<IosPresentmentDisclosure>?) -> Unit,
) {
    // Every combination is offered, as `RequestScreen` does for the app's own request screens: one
    // `WrapSelectableCard` per alternative, titled "Option n of N", contents rendered inside.
    //
    // 📌 This used to take only the first, on the reasoning that more than one combination was "the
    // uncommon case". **Measured 2026-09-04 and it is not**: multipaz produces one combination per
    // candidate document, so a probe run with 4 documents reported `combinations=4` and one with 6
    // reported `combinations=6`. A wallet holding two documents of the requested type already has a
    // choice to make, and taking the first made it silently.
    //
    // ⚠️ The options are labelled but not described — every row reads the document's name over the
    // literal string "View details", so two PIDs from the same issuer look identical. That is
    // upstream's row construction (`RequestTransformer.kt:167` on `main`, mirrored by
    // `IosPresentmentConsentMapping`), shared by the app's own screens on both platforms, and it is
    // deliberately matched here rather than improved in one place only.
    val combinations: List<RequestCombinationUi> = remember(request) {
        request.toCombinationsUi(strings)
    }
    var selected: Int by remember(combinations) { mutableStateOf(0) }
    // One editable document list per combination: claims are toggled per option, so switching options
    // must not carry the previous option's checkboxes across.
    var documentsPerCombination: List<List<RequestDocumentItemUi>> by remember(combinations) {
        mutableStateOf(combinations.map { it.documents })
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = request.requesterName ?: "")

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (combinations.size > 1) {
                combinations.forEachIndexed { option, _ ->
                    WrapSelectableCard(
                        modifier = Modifier.fillMaxWidth(),
                        title = strings.get(
                            Res.string.request_combination_option_title,
                            option + 1,
                            combinations.size,
                        ),
                        isSelected = option == selected,
                        onSelected = { selected = option },
                    ) {
                        DocumentRows(
                            documents = documentsPerCombination[option],
                            onDocumentsChange = { updated ->
                                documentsPerCombination = documentsPerCombination.mapIndexed { at, existing ->
                                    if (at == option) updated else existing
                                }
                            },
                        )
                    }
                }
            } else {
                DocumentRows(
                    documents = documentsPerCombination.firstOrNull().orEmpty(),
                    onDocumentsChange = { updated -> documentsPerCombination = listOf(updated) },
                )
            }
        }

        ShareAndCancel(
            combination = combinations.getOrNull(selected),
            documents = documentsPerCombination.getOrNull(selected).orEmpty(),
            onDecision = onDecision,
        )
    }
}

/** The document rows of one option, with their claim checkboxes. */
@Composable
private fun DocumentRows(
    documents: List<RequestDocumentItemUi>,
    onDocumentsChange: (List<RequestDocumentItemUi>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        documents.forEachIndexed { index, document ->
            WrapExpandableListItem(
                modifier = Modifier.fillMaxWidth(),
                header = document.headerUi.header,
                data = document.headerUi.nestedItems,
                onItemClick = { item ->
                    onDocumentsChange(
                        documents.map { candidate ->
                            candidate.copy(
                                headerUi = candidate.headerUi.copy(
                                    nestedItems = candidate.headerUi.nestedItems.map {
                                        it.toggleCheckboxState(id = item.itemId)
                                    },
                                ),
                            )
                        }
                    )
                },
                isExpanded = document.headerUi.isExpanded,
                onExpandedChange = {
                    onDocumentsChange(
                        documents.mapIndexed { position, candidate ->
                            if (position != index) {
                                candidate
                            } else {
                                candidate.copy(
                                    headerUi = candidate.headerUi.copy(
                                        isExpanded = !candidate.headerUi.isExpanded,
                                    ),
                                )
                            }
                        }
                    )
                },
            )
        }
    }
}

/**
 * The two decisions, over whichever option is selected.
 *
 * Null is a refusal, and so is a selection that keeps nothing — the disclosures are what the response
 * is built from, so an empty list would otherwise send an empty response rather than declining.
 */
@Composable
private fun ShareAndCancel(
    combination: RequestCombinationUi?,
    documents: List<RequestDocumentItemUi>,
    onDecision: (List<IosPresentmentDisclosure>?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        WrapButton(
            modifier = Modifier.fillMaxWidth(),
            buttonConfig = ButtonConfig(
                type = ButtonType.PRIMARY,
                onClick = {
                    val kept = combination
                        ?.copy(documents = documents)
                        ?.keptDocuments()
                        .orEmpty()

                    onDecision(
                        kept.map { document ->
                            IosPresentmentDisclosure(
                                documentId = document.match.documentId,
                                credentialId = document.match.credentialId,
                                claims = document.payload.docClaimsDomain.map { it.path }.toSet(),
                            )
                        }.takeIf { it.isNotEmpty() },
                    )
                },
            ),
        ) { Text(text = "Share") }

        WrapButton(
            modifier = Modifier.fillMaxWidth(),
            buttonConfig = ButtonConfig(
                type = ButtonType.SECONDARY,
                onClick = { onDecision(null) },
            ),
        ) { Text(text = "Cancel") }
    }
}

/** The extension's own graph. See [IosAppRoot] — same reasoning, different process. */
private fun startKoinIfNeeded() {
    if (KoinPlatform.getKoinOrNull() != null) return
    startKoin { modules(SharedUiModule().sharedUiDefinitions()) }
}

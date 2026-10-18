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

// Extracted from IssuerDetails.kt so `DocumentDetailsViewModel` can hold this in commonMain; the
// composable that renders it stays in :ui-logic. `issuerLogo` becomes a String for the same reason
// `RelyingPartyDataUi.logo` did in f7f104d1 — java.net.URI is JVM-only, and the only consumer already
// stringified it. Package unchanged.
package eu.europa.ec.uilogic.component

import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.document_details_issuer_card_expired_action_btn_text
import eu.europa.ec.shared.resources.document_details_issuer_card_expired_message_text
import eu.europa.ec.shared.resources.document_details_issuer_card_issued_action_btn_text
import eu.europa.ec.shared.resources.document_details_issuer_card_issued_message_text
import eu.europa.ec.shared.resources.document_details_issuer_card_revoked_message_text
import org.jetbrains.compose.resources.StringResource

/**
 * Data class representing the UI state for the issuer details card.
 *
 * @property issuerName The name of the entity that issued the document.
 * @property issuerLogo URI of the issuer's logo image, as a String.
 * @property documentState The current state of the document (e.g., Issued or Revoked),
 * containing relevant dates.
 * @property isExpanded Boolean flag indicating whether the card is currently in its expanded state.
 */
data class IssuerDetailsCardDataUi(
    val issuerName: String?,
    val issuerLogo: String?,
    val documentState: DocumentState,
    val isExpanded: Boolean,
) {
    sealed class DocumentState {
        data class Issued(
            val issuanceDate: String,
            val expirationDate: String?
        ) : DocumentState()

        data class Expired(
            val issuanceDate: String,
            val expirationDate: String?
        ) : DocumentState()

        data object Revoked : DocumentState()
    }

    /**
     * The shared string resource for the message text displayed when the card is expanded.
     * The value depends on whether the document is in an [DocumentState.Issued] or [DocumentState.Revoked] state.
     */
    val expandedMessageTextRes: StringResource
        get() {
            return when (documentState) {
                is DocumentState.Issued -> {
                    Res.string.document_details_issuer_card_issued_message_text
                }

                is DocumentState.Expired -> {
                    Res.string.document_details_issuer_card_expired_message_text
                }

                is DocumentState.Revoked -> {
                    Res.string.document_details_issuer_card_revoked_message_text
                }
            }
        }

    /**
     * The shared string resource for the text displayed on the action button in the expanded state.
     * Returns a resource for [DocumentState.Issued] or null if the document is [DocumentState.Revoked].
     */
    val expandedActionButtonTextRes: StringResource?
        get() {
            return when (documentState) {
                is DocumentState.Issued -> {
                    Res.string.document_details_issuer_card_issued_action_btn_text
                }

                is DocumentState.Expired -> {
                    Res.string.document_details_issuer_card_expired_action_btn_text
                }

                is DocumentState.Revoked -> {
                    null
                }
            }
        }


    /**
     * Indicates whether the card should bypass its expandable behavior and only display its
     * expanded content. This occurs when the collapsed header would be empty (no issuer name
     * and no expiration date for an issued document). Documents in Expired or Revoked states
     * always show status information, so this property remains false for those states.
     */
    val showsExpandedContentOnly: Boolean
        get() {
            val state = documentState
            return issuerName == null
                    && state is DocumentState.Issued
                    && state.expirationDate == null
        }
}

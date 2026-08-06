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

package eu.europa.ec.corelogic.model

/**
 * A document the wallet has found to be revoked, as the dashboard view-model receives it.
 *
 * Plain data: `Parcelable` was never part of the model's meaning, only of how the Android revocation
 * worker ships it to the activity in a broadcast extra. That transport keeps its own parcelable
 * mirror in :core-logic (`RevokedDocumentParcel`), so this side stays KMP.
 */
data class RevokedDocumentDataDomain(
    val name: String,
    val id: String,
)

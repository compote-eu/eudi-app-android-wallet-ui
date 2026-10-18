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

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Android transport for [RevokedDocumentDataDomain].
 *
 * The revocation worker broadcasts its findings to the activity in an Intent extra, which needs a
 * `Parcelable`; `@Parcelize` has no multiplatform counterpart. Keeping the parcelable form here — and
 * the domain model in commonMain — means the shared dashboard view-model can consume the model
 * without the transport following it across the seam. Both ends of the broadcast are Android-only.
 */
@Parcelize
data class RevokedDocumentParcel(
    val name: String,
    val id: String,
) : Parcelable

fun RevokedDocumentParcel.toDomain(): RevokedDocumentDataDomain =
    RevokedDocumentDataDomain(name = name, id = id)

fun RevokedDocumentDataDomain.toParcel(): RevokedDocumentParcel =
    RevokedDocumentParcel(name = name, id = id)

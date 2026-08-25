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

import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.document_category_education
import eu.europa.ec.shared.resources.document_category_finance
import eu.europa.ec.shared.resources.document_category_government
import eu.europa.ec.shared.resources.document_category_health
import eu.europa.ec.shared.resources.document_category_other
import eu.europa.ec.shared.resources.document_category_retail
import eu.europa.ec.shared.resources.document_category_social_security
import eu.europa.ec.shared.resources.document_category_travel
import org.jetbrains.compose.resources.StringResource
import kotlin.jvm.JvmInline

/**
 * Represents a collection of document categories and their associated document identifiers.
 *
 * This class is a value class, meaning it provides a type-safe wrapper around a `Map<DocumentCategory, List<DocumentIdentifier>>`.
 * It allows you to organize documents into categories, where each category is associated with a list of unique document identifiers.
 *
 * The primary purpose of this class is to provide a structured and type-safe way to manage documents categorized by different criteria.
 * It ensures that each category is associated with a distinct list of document identifiers, avoiding potential conflicts or ambiguities.
 *
 * @property value A map where:
 * - The keys are [DocumentCategory] instances, representing the different categories.
 * - The values are lists of [DocumentIdentifier] instances, representing the documents belonging to each category.
 * Each list contains unique identifiers.
 *
 * @constructor Creates a [DocumentCategories] instance from a map of [DocumentCategory] to a list of [DocumentIdentifier].
 * It is recommended to ensure that document identifiers within each list are unique, though this is not enforced by the class itself.
 *
 */
@JvmInline
value class DocumentCategories(
    val value: Map<DocumentCategory, List<DocumentIdentifier>>,
) {
    companion object {
        /**
         * The wallet's categorisation of every document type it knows about.
         *
         * **One definition for both platforms, deliberately.** This used to live inline in Android's
         * `WalletCoreConfig` while iOS carried its own two-entry map, and the two drifted: iOS
         * categorised PID only, so an mDL or a tax credential — both obtainable there through a
         * credential offer — fell through to [DocumentCategory.Other] where Android filed them under
         * [DocumentCategory.Government]. A categorisation is a property of the document type, not of
         * the platform, so there is now one list and neither side can drift from it again.
         *
         * An identifier that appears here nowhere still resolves to [DocumentCategory.Other], which is
         * what `toDocumentCategory` falls back to.
         */
        val Default: DocumentCategories = DocumentCategories(
            value = mapOf(
                DocumentCategory.Government to listOf(
                    DocumentIdentifier.MdocPid,
                    DocumentIdentifier.SdJwtPid,
                    DocumentIdentifier.OTHER(
                        formatType = "org.iso.18013.5.1.mDL"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.tax.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "urn:eu.europa.ec.eudi:tax:1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.pseudonym.age_over_18.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "urn:eu.europa.ec.eudi:pseudonym_age_over_18:1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.pseudonym.age_over_18.deferred_endpoint"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.cor.1"
                    ),
                ),
                DocumentCategory.Travel to listOf(
                    DocumentIdentifier.OTHER(
                        formatType = "org.iso.23220.2.photoid.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "org.iso.23220.photoID.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "org.iso.18013.5.1.reservation"
                    ),
                ),
                DocumentCategory.Finance to listOf(
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.iban.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "urn:eu.europa.ec.eudi:iban:1"
                    ),
                ),
                DocumentCategory.Education to emptyList(),
                DocumentCategory.Health to listOf(
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.hiid.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "urn:eu.europa.ec.eudi:hiid:1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.ehic.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "urn:eu.europa.ec.eudi:ehic:1"
                    ),
                ),
                DocumentCategory.SocialSecurity to listOf(
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.pda1.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "urn:eu.europa.ec.eudi:pda1:1"
                    ),
                ),
                DocumentCategory.Retail to listOf(
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.loyalty.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.msisdn.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "urn:eu.europa.ec.eudi:msisdn:1"
                    ),
                ),
                DocumentCategory.Other to listOf(
                    DocumentIdentifier.OTHER(
                        formatType = "eu.europa.ec.eudi.por.1"
                    ),
                    DocumentIdentifier.OTHER(
                        formatType = "urn:eu.europa.ec.eudi:por:1"
                    ),
                ),
            )
        )
    }
}

/**
 * Represents the category of a document.
 * Each category is associated with a string resource for localization, a unique ID, and an order value for sorting.
 * This sealed class provides a type-safe way to represent document categories.
 *
 * @property nameRes The shared string resource holding this category's display name. A
 * compose-resources [StringResource] rather than an Android `@StringRes Int` so the model stays
 * KMP-clean — `:core-logic` no longer depends on the Android resource table.
 * @property id A unique integer identifier for the category.
 * @property order An integer representing the desired display order of the category. Categories with lower order values are displayed first.
 */
sealed class DocumentCategory(
    val nameRes: StringResource,
    val id: Int,
    val order: Int,
) {
    data object Government : DocumentCategory(
        nameRes = Res.string.document_category_government, id = 1, order = 1
    )

    data object Travel : DocumentCategory(
        nameRes = Res.string.document_category_travel, id = 2, order = 2
    )

    data object Finance : DocumentCategory(
        nameRes = Res.string.document_category_finance, id = 3, order = 3
    )

    data object Education : DocumentCategory(
        nameRes = Res.string.document_category_education, id = 4, order = 4
    )

    data object Health : DocumentCategory(
        nameRes = Res.string.document_category_health, id = 5, order = 5
    )

    data object SocialSecurity : DocumentCategory(
        nameRes = Res.string.document_category_social_security, id = 6, order = 6
    )

    data object Retail : DocumentCategory(
        nameRes = Res.string.document_category_retail, id = 7, order = 7
    )

    data object Other : DocumentCategory(
        nameRes = Res.string.document_category_other, id = 8, order = 8
    )
}
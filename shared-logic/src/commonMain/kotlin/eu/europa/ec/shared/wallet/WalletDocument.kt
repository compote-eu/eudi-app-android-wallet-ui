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

package eu.europa.ec.shared.wallet

/**
 * App-owned, platform-neutral document model exposed across the [WalletEngine] seam, so
 * shared/presentation code no longer depends on the Android-only wallet-core
 * `Document` / `IssuedDocument` types. It is intentionally minimal (seeded with [id]) and
 * grows field-by-field as consumers migrate — each platform's WalletEngine maps its native
 * document into this type.
 */
data class WalletDocument(
    val id: String,
    /**
     * Top-level claim values keyed by claim identifier (the wallet-core `identifierString`),
     * stringified. A first, deliberately-flat claims representation — sufficient for simple
     * by-key lookups; it will gain structure (namespaces / nested claims / typed values) as
     * richer consumers migrate.
     */
    val claims: Map<String, String> = emptyMap(),
)

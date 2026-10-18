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

// Lifted out of WalletCoreDocumentsController.kt so `AddDocumentViewModel` can name it from
// commonMain. It is a bare enum — it was only co-located with that file's wallet-core code, never
// coupled to it. Package unchanged.
package eu.europa.ec.corelogic.controller

/** How a document is obtained from its issuer. */
enum class IssuanceMethod {
    OPENID4VCI
}

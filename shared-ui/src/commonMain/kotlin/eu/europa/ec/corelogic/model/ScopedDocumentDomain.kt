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

// One offerable credential configuration, as an issuer advertises it. Moved here from :core-logic
// unchanged so `AddDocumentInteractorImpl` can name it from commonMain: every field is a String, an Int
// or a Boolean — it was co-located with wallet-core, never coupled to it. Package unchanged.
package eu.europa.ec.corelogic.model

data class ScopedDocumentDomain(
    val name: String,
    val configurationId: String,
    val credentialIssuerId: String,
    val credentialIssuerOrder: Int,
    val formatType: FormatType?,
    val isPid: Boolean
)
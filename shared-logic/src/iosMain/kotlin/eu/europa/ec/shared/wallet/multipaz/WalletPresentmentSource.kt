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

package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata

/**
 * The wallet's answer to *"what may be presented, does the user agree, and who is asking"* — one
 * definition, shared by all three presentment paths.
 *
 * ## Why this is a function and not three constructor calls
 *
 * There are three ways a request reaches this wallet — ISO 18013-5 proximity
 * ([IosProximityPresenter]), OpenID4VP over a URI scheme ([IosRemotePresenter]) and the Digital
 * Credentials API ([IosDcApiPresenter]) — and every one of them needs the same
 * [SimplePresentmentSource] with the same defaults. Written out three times, they drifted in the way
 * duplicated *rules* always do: `resolveTrustFn` was supplied by none of them, so multipaz fell back
 * to its own default of `{ null }` and **every verifier showed as untrusted on every path**. Three
 * separate omissions of the same argument, and no test could see it, because each site was
 * individually consistent.
 *
 * So the decisions live here and the presenters keep only what genuinely differs between them.
 * Android reaches the same end differently — wallet-core owns this configuration there, and
 * `readerAuthPolicy(ReaderAuthPolicy.EnforceIfPresent)` is a single setting — so a single definition
 * here is also what makes the two platforms comparable.
 *
 * @param store where the documents are; the caller decides whether that is the engine's or its own.
 * @param credentialDomain the wallet's own credential domain, so a document some other component put
 *   in the same store is never offered — the same scoping the reader applies when listing documents.
 * @param readerTrust who the verifier is, or null to answer "unknown" without asking. Null is for
 *   tests: a presentment test has no business reaching the EU trust lists, and one that did would
 *   pass or fail with the network.
 * @param offersSdJwt whether SD-JWT VCs may answer this request. **False only for proximity**: ISO
 *   18013-5 has no SD-JWT, so offering one there would be meaningless. It must be true everywhere
 *   else — a DCQL query may name an SD-JWT VC as readily as an mdoc, and leaving it out made every
 *   SD-JWT request report "you have nothing this verifier asked for" while the credential sat in the
 *   wallet.
 * @param showConsent publishes the request and suspends until a screen answers. The whole reason the
 *   app supplies a source at all: multipaz's default answers immediately, which would release
 *   documents without asking anyone.
 */
internal suspend fun walletPresentmentSource(
    store: MultipazWalletStore,
    credentialDomain: String,
    readerTrust: ReaderTrustSource?,
    offersSdJwt: Boolean,
    showConsent: suspend (
        requester: Requester,
        trustMetadata: TrustMetadata?,
        data: CredentialPresentmentData,
    ) -> CredentialPresentmentSelection?,
): SimplePresentmentSource = SimplePresentmentSource(
    documentStore = store.documentStore,
    documentTypeRepository = documentTypeRepository,
    // What makes a successful exchange show up in the History tab: multipaz logs the event itself
    // once the response is out, so supplying the logger *is* the whole write side.
    eventLogger = store.eventLogger(),
    // Reader trust, matching Android's `readerAuthPolicy(EnforceIfPresent)`. multipaz's default is
    // `{ null }`, which reads as "not trusted" for every verifier that ever asks.
    resolveTrustFn = { requester -> readerTrust?.trustMetadataFor(requester) },
    domainsMdocSignature = listOf(credentialDomain),
    domainsKeyBoundSdJwt = if (offersSdJwt) listOf(credentialDomain) else emptyList(),
    showConsentPromptFn = { requester, trustMetadata, data, _, _ ->
        showConsent(requester, trustMetadata, data)
    },
)

/**
 * Empty on purpose. Localized claim names live in multipaz's separate `multipaz-doctypes` artifact;
 * without it a claim shows its data-element identifier, which is exactly what iOS already does on the
 * documents and details screens. Adding the artifact for the consent screens alone would make
 * presentment the only place iOS speaks in display names — the fix is to give the whole app localized
 * claim names at once, not to special-case consent.
 *
 * One instance, because it holds no per-request state and each presenter previously kept an identical
 * copy of both the object and this comment.
 */
private val documentTypeRepository = DocumentTypeRepository()

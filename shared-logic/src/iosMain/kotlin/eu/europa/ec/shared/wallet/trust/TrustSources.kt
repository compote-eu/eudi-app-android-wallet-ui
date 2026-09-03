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

package eu.europa.ec.shared.wallet.trust

import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata
import platform.Foundation.NSData

/**
 * What a trust check concluded — and, importantly, whether it concluded anything.
 *
 * The three cases exist because a caller must not treat *"the trust list was unreachable"* as *"this
 * party is not trusted"*. Collapsing them into a boolean is what would make a network blip
 * indistinguishable from an attack.
 *
 * The two callers then weigh [UNDETERMINED] in **opposite** directions, and both are deliberate:
 * issuance lets it through (a wallet that cannot add a document offline is worse than one that
 * trusts TLS for a moment), presentation shows it as untrusted (vouching for a stranger is worse
 * than saying nothing). See the two call sites, each of which says so.
 */
internal enum class TrustVerdict { TRUSTED, NOT_TRUSTED, UNDETERMINED }

/**
 * Whether a certificate chain is trusted for a purpose — the issuance side of trust.
 *
 * An interface rather than the concrete [IosEtsiTrust] for one reason: it is what lets a test pin
 * the behaviour of the *callers* without reaching the EU trust lists. The rule that a definite
 * refusal fails issuance while an undetermined one does not is a decision worth a test, and a test
 * that had to download four lists to make it would prove nothing repeatable.
 */
internal fun interface IssuerTrustSource {
    suspend fun verdict(chain: List<NSData>, context: VerificationContext): TrustVerdict
}

/**
 * What to tell multipaz about the verifier that just sent a request, or null when it is not trusted
 * — the presentation side of trust.
 *
 * Same reasoning as [IssuerTrustSource]: this is the seam that made *"a trusted verifier reaches the
 * consent screen as trusted"* checkable, which is precisely the property that was silently false on
 * all three presentment paths.
 */
internal fun interface ReaderTrustSource {
    suspend fun trustMetadataFor(requester: Requester): TrustMetadata?
}

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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.multipaz.mdoc.credential.MdocCredential

/**
 * Which stored documents carry an `x5chain` in their issuer signature, and which do not.
 *
 * A live verifier refused a presentation with
 * `X5CNotTrusted(cause=Missing x5Chain)` while an earlier one succeeded from the same wallet, so the
 * property differs **per document**. This says which, and prints enough about each to correlate with
 * how it was issued — the question being whether deferred-collected credentials lose the chain that
 * ordinary issuance keeps, which would make every deferred document unpresentable.
 *
 * Read-only; it decodes what is already stored and changes nothing.
 */
fun probeIssuerChains(onResult: (String) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch { runIssuerChainProbe(onResult) }
}

private suspend fun runIssuerChainProbe(onResult: (String) -> Unit) {
    val store = MultipazWalletStore.open()
    val documents = store.documentStore.listDocuments()
    onResult("== issuer chains across ${documents.size} document(s)")

    var withChain = 0
    var withoutChain = 0
    for (document in documents) {
        val metadata = document.eudiMetadata
        val credentials = document.getCertifiedCredentials()
        if (credentials.isEmpty()) {
            onResult("  ${document.identifier} ${metadata?.format?.identifier} — no certified credentials")
            continue
        }
        val verdicts = credentials.map { credential ->
            when (credential) {
                is MdocCredential -> credential.hasIssuerChain()
                else -> null
            }
        }
        val yes = verdicts.count { it == true }
        val no = verdicts.count { it == false }
        withChain += yes
        withoutChain += no
        onResult(
            "  ${document.identifier} ${metadata?.format?.identifier} " +
                    "deferredHandle=${metadata?.deferredTransactionId != null} " +
                    "credentials=${credentials.size} x5chain: yes=$yes no=$no" +
                    (if (no > 0) "   <-- UNPRESENTABLE" else "")
        )
    }
    onResult("TOTAL credentials with x5chain: $withChain, without: $withoutChain")
}

/**
 * Whether this credential's `issuerAuth` carries an `x5chain`.
 *
 * Uses multipaz's own accessor rather than poking at CBOR: [MdocCredential.issuerCertChain] reads
 * `COSE_LABEL_X5CHAIN` from the unprotected header and throws when it is absent, which is exactly the
 * condition the verifier reported as `X5CNotTrusted(cause=Missing x5Chain)`.
 */
private fun MdocCredential.hasIssuerChain(): Boolean =
    runCatching { issuerCertChain.certificates.isNotEmpty() }.getOrElse { false }

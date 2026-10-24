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

// Measures, over the mdoc credentials this wallet already holds, what a stricter issuance check would
// refuse, and changes nothing. multipaz presents a data element by decoding the issuer's
// `IssuerSignedItemBytes` and encoding it again, and the verifier digests what it is sent; so an element
// whose bytes do not come back the same can never be presented. Duplicate element identifiers within a
// namespace are counted too (PAR_ISO_DATA_MODEL_3).
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.multipaz.cbor.Cbor
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.util.Logger

/** Reports, per stored mdoc credential, the data elements that do not re-encode as sent; reads only. */
fun probeElementEncodings(onResult: (String) -> Unit) {
    val report: (String) -> Unit = { line ->
        Logger.i(TAG, line)
        onResult(line)
    }
    CoroutineScope(Dispatchers.Main).launch {
        try {
            val store = MultipazWalletStore.open()
            var credentials = 0
            var elements = 0
            var mismatches = 0
            var duplicates = 0
            val documents = store.documentStore.listDocuments()
                .filter { it.eudiMetadata?.documentManagerId == store.documentManagerId }
            for (document in documents) {
                val mdocs = document.getCertifiedCredentials().filterIsInstance<MdocCredential>()
                if (mdocs.isEmpty()) continue
                var documentElements = 0
                val documentMismatches = mutableSetOf<String>()
                val documentDuplicates = mutableSetOf<String>()
                for (mdoc in mdocs) {
                    credentials++
                    val nameSpaces = Cbor.decode(mdoc.issuerProvidedData.toByteArray())["nameSpaces"]
                    for ((nameSpace, items) in nameSpaces.asMap) {
                        val seen = mutableSetOf<String>()
                        for (itemBytes in items.asArray) {
                            val sent = itemBytes.asTagged.asBstr
                            val item = Cbor.decode(sent)
                            val identifier = item["elementIdentifier"].asTstr
                            documentElements++
                            if (!seen.add(identifier)) documentDuplicates += "${nameSpace.asTstr}/$identifier"
                            if (!Cbor.encode(item).contentEquals(sent)) {
                                documentMismatches += "${nameSpace.asTstr}/$identifier"
                            }
                        }
                    }
                }
                elements += documentElements
                mismatches += documentMismatches.size
                duplicates += documentDuplicates.size
                report(
                    "${document.identifier} ${document.displayName} ${mdocs.first().docType}: " +
                        "${mdocs.size} credential(s), $documentElements element(s) read, " +
                        "not re-encoding as sent: ${documentMismatches.ifEmpty { "none" }}, " +
                        "duplicate identifiers: ${documentDuplicates.ifEmpty { "none" }}"
                )
            }
            report(
                "SUMMARY: ${documents.size} document(s), $credentials mdoc credential(s), $elements element(s); " +
                    "not re-encoding as sent: $mismatches, duplicate identifiers: $duplicates"
            )
        } catch (t: Throwable) {
            report("FAILED: ${t::class.simpleName}: ${t.message}")
        }
    }
}

private const val TAG = "ElementEncodingProbe"

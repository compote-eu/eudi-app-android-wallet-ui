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

// The one PID predicate, which four call sites across both platforms had been writing out by hand.
package eu.europa.ec.corelogic.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentIdentifierPidTest {

    @Test
    fun both_pid_formats_are_pids() {
        // Both, and this is the whole point: the wallet issues a PID as mdoc *and* as SD-JWT VC, and a
        // caller that checks only one silently treats half the PIDs as ordinary documents.
        assertTrue(DocumentIdentifier.MdocPid.isPid)
        assertTrue(DocumentIdentifier.SdJwtPid.isPid)
    }

    @Test
    fun an_unrecognised_identifier_is_not_a_pid() {
        // `formatType.toDocumentIdentifier()` returns null for a type the wallet does not know, and the
        // nullable receiver exists so those callers need no null branch of their own.
        assertFalse((null as DocumentIdentifier?).isPid)
    }

    @Test
    fun another_document_type_is_not_a_pid() {
        val other = "org.iso.18013.5.1.mDL".toDocumentIdentifier()

        assertFalse(other.isPid)
    }
}

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

import eu.europa.ec.shared.wallet.document.WalletCredentialPolicy
import eu.europa.ec.shared.wallet.multipaz.harness.MDOC_PID_DOC_TYPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The credential policy is decided by document *format*, not by batch size.
 *
 * Android configures this per document type — both PID formats are `OnceOnly`, everything else is a
 * rotating batch — and iOS used to derive it from how many credentials were requested, which stamped
 * every real PID as `RotatingBatch`. That mattered because `usableCredentials` only drops spent
 * credentials under `OnceOnly`, and `isLowOnCredentials` is only ever true under it.
 */
class CredentialPoliciesTest {

    private val sdJwtPidVct = "urn:eudi:pid:1"
    private val mdlDocType = "org.iso.18013.5.1.mDL"

    @Test
    fun both_pid_formats_are_once_only() {
        for (formatType in listOf(MDOC_PID_DOC_TYPE, sdJwtPidVct)) {
            val policy = assertIs<WalletCredentialPolicy.OnceOnly>(
                credentialPolicyFor(formatType, numberOfCredentials = 20),
                message = formatType,
            )
            assertEquals(20, policy.numberOfCredentials)
            assertEquals(2, policy.reissueTriggerUnused, "Android's reissueTriggerUnused")
        }
    }

    @Test
    fun anything_else_is_a_rotating_batch() {
        val policy = assertIs<WalletCredentialPolicy.RotatingBatch>(
            credentialPolicyFor(mdlDocType, numberOfCredentials = 20)
        )
        assertEquals(20, policy.numberOfCredentials)
    }
}

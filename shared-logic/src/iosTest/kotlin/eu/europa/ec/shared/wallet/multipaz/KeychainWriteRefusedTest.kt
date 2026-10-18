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

import eu.europa.ec.shared.wallet.platform.IosDevicePasscode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * The message is the feature here.
 *
 * A Keychain refusal on a device with no passcode is the one storage failure a *user* can fix, and
 * the whole point of naming it is that the text says so instead of quoting an `OSStatus`. The
 * Keychain itself cannot be exercised from a test binary — every `SecItem` call returns
 * `errSecNotAvailable`, because a test binary is not an app — so what is asserted is the part that
 * carries the claim.
 */
class KeychainWriteRefusedTest {

    @Test
    fun a_refusal_with_no_passcode_tells_the_user_what_to_do() {
        val message = KeychainWriteRefused(status = -25293, passcodeSet = false).message.orEmpty()

        assertContains(message, "no passcode")
        assertContains(message, "Set a device passcode")
        assertContains(message, "-25293", message = "the status still has to be there for a log reader")
    }

    @Test
    fun a_refusal_with_a_passcode_set_does_not_blame_the_passcode() {
        val message = KeychainWriteRefused(status = -34018, passcodeSet = true).message.orEmpty()

        assertContains(message, "-34018")
        assertTrue(
            "A passcode is set" in message,
            "with a passcode set the message must rule that cause out rather than repeat it: $message",
        )
    }

    /**
     * Not an assertion about this machine so much as about the fail-open contract: whatever
     * `LAContext` reports in a test binary, [IosDevicePasscode.isSet] must not answer `false` unless
     * it is certain, because a false negative would put the passcode-required screen in front of a
     * wallet that works.
     */
    @Test
    fun the_passcode_check_fails_open() {
        assertTrue(
            IosDevicePasscode.isSet(),
            "isSet() must only report false on a definite LAErrorPasscodeNotSet",
        )
    }
}

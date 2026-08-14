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

// How iOS sequences an issuance across the configurations one option asks for — "PID Combined" is two —
// and what it reports when only some of them make it. The per-configuration step is faked here: driving
// the real one needs an issuer that mints signed credentials, and none of what these cases are about
// happens inside it. The real one is verified live against the EU dev issuer.
package eu.europa.ec.shared.wallet.multipaz

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IosCredentialIssuerTest {

    private val issuer = IosVciIssuer(
        issuerUrl = "https://issuer.test",
        clientId = "eudiw-abca",
        redirectUri = "eu.europa.ec.euidi://authorization",
        order = 0,
    )

    private val attempted = mutableListOf<String>()

    /**
     * An issuer whose per-configuration step is scripted. [walletEngine] is never touched: the fake
     * replaces the only path that would open a store.
     */
    private fun issuerWith(
        answers: Map<String, Result<String>>,
    ) = IosCredentialIssuer(
        walletEngine = IosWalletEngine(),
        issuers = listOf(issuer),
        issueConfiguration = { _, configurationId ->
            attempted += configurationId
            answers[configurationId] ?: Result.failure(IllegalStateException("unscripted"))
        },
    )

    @Test
    fun the_documents_of_every_requested_configuration_are_reported_in_order() = runTest {
        val issuing = issuerWith(
            answers = mapOf(
                "pid_mdoc" to Result.success("doc-mdoc"),
                "pid_sd_jwt" to Result.success("doc-sdjwt"),
            ),
        )

        val progress = issuing.issue(issuer.issuerUrl, listOf("pid_mdoc", "pid_sd_jwt")).first()

        val issued = assertIs<IosIssuanceProgress.Issued>(progress)
        assertEquals(listOf("doc-mdoc", "doc-sdjwt"), issued.documentIds)
        assertTrue(issued.failures.isEmpty())
        // One flow per configuration, in the order asked for — the PID-combined option depends on it.
        assertEquals(listOf("pid_mdoc", "pid_sd_jwt"), attempted)
    }

    @Test
    fun a_configuration_that_fails_after_one_succeeded_is_a_partial_result() = runTest {
        val issuing = issuerWith(
            answers = mapOf(
                "pid_mdoc" to Result.success("doc-mdoc"),
                "pid_sd_jwt" to Result.failure(IllegalStateException("issuer said no")),
            ),
        )

        val progress = issuing.issue(issuer.issuerUrl, listOf("pid_mdoc", "pid_sd_jwt")).first()

        // The screen shows what was added and says the rest was not; reporting a plain failure would hide
        // a document that is now in the wallet.
        val issued = assertIs<IosIssuanceProgress.Issued>(progress)
        assertEquals(listOf("doc-mdoc"), issued.documentIds)
        assertEquals(mapOf("pid_sd_jwt" to "issuer said no"), issued.failures)
    }

    @Test
    fun nothing_further_is_attempted_after_a_failure() = runTest {
        val issuing = issuerWith(
            answers = mapOf(
                "first" to Result.failure(IllegalStateException("declined")),
                "second" to Result.success("doc-2"),
            ),
        )

        val progress = issuing.issue(issuer.issuerUrl, listOf("first", "second")).first()

        // Each configuration needs its own authorization, so carrying on would open another browser
        // window for a user who just declined one.
        assertEquals(listOf("first"), attempted)
        assertEquals("declined", assertIs<IosIssuanceProgress.Failure>(progress).message)
    }

    @Test
    fun a_failure_with_no_message_still_says_something() = runTest {
        val issuing = issuerWith(answers = mapOf("only" to Result.failure(IllegalStateException())))

        val progress = issuing.issue(issuer.issuerUrl, listOf("only")).first()

        assertTrue(assertIs<IosIssuanceProgress.Failure>(progress).message.isNotBlank())
    }

    @Test
    fun an_issuer_that_is_not_in_the_catalogue_is_refused_without_a_network_call() = runTest {
        val issuing = issuerWith(answers = mapOf("pid_mdoc" to Result.success("doc")))

        val progress = issuing.issue("https://not-configured.test", listOf("pid_mdoc")).first()

        assertTrue(attempted.isEmpty(), "nothing should be attempted for an unknown issuer")
        assertTrue("not-configured" in assertIs<IosIssuanceProgress.Failure>(progress).message)
    }

    @Test
    fun asking_for_no_configuration_fails_rather_than_reporting_an_empty_success() = runTest {
        val issuing = issuerWith(answers = emptyMap())

        val progress = issuing.issue(issuer.issuerUrl, emptyList()).first()

        // An empty `Success` would send the screen to the issuance-success list with nothing in it.
        assertIs<IosIssuanceProgress.Failure>(progress)
        assertTrue(attempted.isEmpty())
    }
}

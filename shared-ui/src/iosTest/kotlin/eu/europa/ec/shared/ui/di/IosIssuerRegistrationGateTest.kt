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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.corelogic.model.IssuerRegistrationDomain
import eu.europa.ec.corelogic.model.RegistrationDetailsDomain
import eu.europa.ec.corelogic.model.RegistrationFailureReasonDomain
import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain
import eu.europa.ec.issuancefeature.interactor.PlatformOfferResolution
import eu.europa.ec.shared.wallet.multipaz.IosCredentialIssuer
import eu.europa.ec.shared.wallet.multipaz.IosCredentialOfferReader
import eu.europa.ec.shared.wallet.multipaz.IosVciIssuer
import eu.europa.ec.shared.wallet.multipaz.IosWalletEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Whether a registration outcome actually stops an issuance, driven through the real bridge.
 *
 * 🚨 **The case that matters most is the one that must NOT block.** `isBlockedForIssuance` answers
 * `true` for `NotEvaluated`, because for a wallet with the check *on* an unestablished registration is a
 * refusal — but `NotEvaluated` is also exactly what a wallet with the check *off* produces, and that is
 * the default. A gate that consulted the rule alone would refuse every issuance in a stock build.
 */
class IosIssuerRegistrationGateTest {

    private val issuerUrl = "https://issuer.test"
    private val offerUri = "openid-credential-offer://?credential_offer=" +
            """{"credential_issuer":"$issuerUrl","credential_configuration_ids":["pid_mdoc"]}"""
                .encodeUrlParameterForTest()

    private val issuerMetadata = """
        {"credential_issuer":"$issuerUrl",
         "credential_endpoint":"$issuerUrl/credential",
         "batch_credential_issuance":{"batch_size":10},
         "credential_configurations_supported":{
           "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","scope":"pid",
             "display":[{"name":"PID (MSO MDoc)","locale":"en"}]}}}
    """.trimIndent()

    private val details = RegistrationDetailsDomain(
        tradeName = "Test PID Provider",
        uniqueId = "LEIXG-123456789",
        logoUri = null,
        intendedUse = null,
        privacyPolicyUrl = null,
        serviceDescription = null,
    )

    private fun bridge(
        checkEnabled: Boolean,
        outcome: IssuerRegistrationDomain,
        onChecked: () -> Unit = {},
    ): IosDocumentOfferPlatformBridge {
        val engine = MockEngine { request ->
            if (request.url.toString() == "$issuerUrl/.well-known/openid-credential-issuer") {
                respond(issuerMetadata, headers = headersOf("Content-Type", "application/json"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
        return IosDocumentOfferPlatformBridge(
            offers = IosCredentialOfferReader(
                engine = engine,
                issuers = listOf(
                    IosVciIssuer(
                        issuerUrl = issuerUrl,
                        clientId = "eudiw-abca",
                        redirectUri = "eu.europa.ec.euidi://authorization",
                        order = 0,
                    )
                ),
            ),
            // Never reached in these tests: the gate returns before issuance is attempted, and the
            // proceed cases stop at the assertion rather than running a real flow.
            credentialIssuer = IosCredentialIssuer(walletEngine = IosWalletEngine()),
            isRegistrationCheckEnabled = { checkEnabled },
            checkRegistration = { _, _ -> onChecked(); outcome },
        )
    }

    @Test
    fun a_blocked_issuer_never_reaches_the_offer_screen_at_all() = runTest {
        val bridge = bridge(
            checkEnabled = true,
            outcome = IssuerRegistrationDomain.Blocked(
                reason = IssuerRegistrationDomain.BlockedReasonDomain.ATTESTATION_OVER_PROVIDED,
                details = details,
            ),
        )

        val resolution = bridge.resolveOffer(offerUri, locale = "en")

        // ⚠️ Refused at RESOLVE, matching Android. An earlier version of this let the offer screen
        // render without a badge and refused on tap, which shows the user an offer they cannot accept
        // and invents an error string where the shared screens already word one.
        val refused = assertIs<PlatformOfferResolution.IssuerNotTrusted>(resolution)
        assertEquals(UntrustedIssuerReasonDomain.REGISTRATION_CERTIFICATE, refused.reason)
    }

    @Test
    fun a_blocked_issuer_cannot_issue_either_even_if_something_reached_issuance() = runTest {
        val bridge = bridge(
            checkEnabled = true,
            outcome = IssuerRegistrationDomain.NotVerified(
                reason = RegistrationFailureReasonDomain.REVOKED,
                details = details,
            ),
        )
        bridge.resolveOffer(offerUri, locale = "en")

        val state = bridge.issueResolvedOffer(offerUri, txCode = null).first()

        // Defence in depth, and Android carries both gates for the same reason: a deep link or a
        // resumed flow can reach issuance without passing the resolve that refused.
        val refused = assertIs<IssueDocumentsPartialState.IssuerNotTrusted>(state)
        assertEquals(UntrustedIssuerReasonDomain.REGISTRATION_CERTIFICATE, refused.reason)
    }

    @Test
    fun with_the_check_off_nothing_is_evaluated_and_nothing_is_blocked() = runTest {
        var checked = false
        val bridge = bridge(
            checkEnabled = false,
            outcome = IssuerRegistrationDomain.Verified(details),
            onChecked = { checked = true },
        )

        val resolution = bridge.resolveOffer(offerUri, locale = "en")

        // 🚨 The default build. `NotEvaluated` is blocking *as a rule* — so if the gate consulted the
        // rule without the flag, this would refuse, and every stock issuance with it.
        val success = assertIs<PlatformOfferResolution.Success>(resolution)
        assertIs<IssuerRegistrationDomain.NotEvaluated>(success.issuerRegistration)
        assertTrue(!checked, "a disabled check must not reach the network at all")

        val state = bridge.issueResolvedOffer(offerUri, txCode = null).first()
        assertTrue(
            state !is IssueDocumentsPartialState.IssuerNotTrusted,
            "an unevaluated registration must not refuse issuance",
        )
    }

    @Test
    fun a_verified_issuer_reaches_the_screen_and_is_not_blocked() = runTest {
        val bridge = bridge(checkEnabled = true, outcome = IssuerRegistrationDomain.Verified(details))

        val resolution = bridge.resolveOffer(offerUri, locale = "en")

        val success = assertIs<PlatformOfferResolution.Success>(resolution)
        val verified = assertIs<IssuerRegistrationDomain.Verified>(success.issuerRegistration)
        assertEquals("Test PID Provider", verified.details.tradeName)

        val state = bridge.issueResolvedOffer(offerUri, txCode = null).first()
        assertTrue(state !is IssueDocumentsPartialState.IssuerNotTrusted)
    }

    @Test
    fun an_offer_nobody_resolved_is_refused_before_the_registration_is_even_consulted() = runTest {
        val bridge = bridge(checkEnabled = true, outcome = IssuerRegistrationDomain.Verified(details))

        val state = bridge.issueResolvedOffer("openid-credential-offer://?never-seen", txCode = null)
            .first()

        val failure = assertIs<IssueDocumentsPartialState.Failure>(state)
        assertEquals(IosDocumentOfferPlatformBridge.OFFER_NOT_RESOLVED, failure.errorMessage)
    }
}

/** The same encoding the offer reader's own tests use; duplicated because it is test-local there. */
private fun String.encodeUrlParameterForTest(): String =
    buildString {
        for (byte in this@encodeUrlParameterForTest.encodeToByteArray()) {
            val ch = byte.toInt().toChar()
            if (ch.isLetterOrDigit() || ch in "-_.~") append(ch) else append('%').append(
                byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
            )
        }
    }

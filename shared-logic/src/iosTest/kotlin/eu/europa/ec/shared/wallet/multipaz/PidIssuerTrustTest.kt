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

import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import eu.europa.ec.shared.wallet.multipaz.harness.issuerNamespacesOf
import eu.europa.ec.shared.wallet.multipaz.harness.issuerSignedDataFor
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.trust.IssuerTrustSource
import eu.europa.ec.shared.wallet.trust.TrustVerdict
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** A self-signed certificate standing in for an issuer's document signer. */
internal suspend fun testSignerCertificate(name: String = "CN=Document Signer"): X509Cert {
    val key = Crypto.createEcPrivateKey(EcCurve.P256)
    val subject = X500Name.fromName(name)
    return X509Cert.Builder(
        publicKey = key.publicKey,
        signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
        serialNumber = ASN1Integer(1L),
        subject = subject,
        issuer = subject,
        validFrom = Clock.System.now() - 1.days,
        validUntil = Clock.System.now() + 30.days,
    ).build()
}

/** An mdoc as an issuer sends it: base64url `IssuerSigned`, with [chain] in its `x5chain` header. */
internal suspend fun testMdocCredential(docType: String, chain: X509CertChain?): String = issuerSignedDataFor(
    docType = docType,
    issuerNamespaces = issuerNamespacesOf(docType, samplePidElements(), Random.Default),
    deviceKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey,
    validFrom = Clock.System.now() - 1.days,
    validUntil = Clock.System.now() + 30.days,
    issuerCertChain = chain,
).toByteArray().toBase64Url()

/**
 * The PID issuer-trust rule, Android's `forContext(PID, ENFORCE)`: a PID is stored only when the EU list
 * of PID providers names its signer, and anything short of that refuses it — including no answer.
 */
class PidIssuerTrustTest {

    private val pidDocType = "eu.europa.ec.eudi.pid.1"
    private val mdlDocType = "org.iso.18013.5.1.mDL"

    /** An SD-JWT VC as an issuer sends it. The signature is not read here, so it is not a real one. */
    private fun sdJwt(vct: String, chain: X509CertChain): String {
        val header = buildJsonObject {
            put("alg", "ES256")
            put("typ", "dc+sd-jwt")
            put("x5c", chain.toX5c(excludeRoot = false))
        }
        val body = buildJsonObject {
            put("iss", "https://issuer.test")
            put("vct", vct)
        }
        return listOf(header, body)
            .joinToString(".") { it.toString().encodeToByteArray().toBase64Url() } +
            ".${"signature".encodeToByteArray().toBase64Url()}~"
    }

    private class Asked(val chainSize: Int, val context: VerificationContext)

    private fun trustAnswering(verdict: TrustVerdict, asked: MutableList<Asked> = mutableListOf()) =
        IssuerTrustSource { chain, context ->
            asked += Asked(chain.size, context)
            verdict
        }

    @Test
    fun a_pid_whose_signer_is_on_the_list_is_let_through_after_asking_in_the_pid_context() = runTest {
        val asked = mutableListOf<Asked>()
        val chain = X509CertChain(listOf(testSignerCertificate("CN=Leaf"), testSignerCertificate("CN=Root")))

        trustAnswering(TrustVerdict.TRUSTED, asked)
            .enforcePidSigners(listOf(testMdocCredential(pidDocType, chain)), requestedPid = true)

        // The whole chain, as PKIX needs it, and the context whose list names PID providers.
        assertEquals(1, asked.size)
        assertEquals(2, asked.single().chainSize)
        assertEquals(VerificationContext.PID, asked.single().context)
    }

    @Test
    fun a_pid_whose_signer_is_not_on_the_list_is_refused() = runTest {
        val credential = testMdocCredential(pidDocType, X509CertChain(listOf(testSignerCertificate())))

        assertFailsWith<IssuerNotTrustedException> {
            trustAnswering(TrustVerdict.NOT_TRUSTED).enforcePidSigners(listOf(credential), requestedPid = true)
        }
    }

    @Test
    fun a_pid_is_refused_when_trust_cannot_be_established() = runTest {
        // The opposite of the metadata check, on purpose: Android's ENFORCE fails closed, so an
        // unreachable list must not let an unchecked PID into the wallet. If this starts passing, that
        // decision was reversed by accident.
        val credential = testMdocCredential(pidDocType, X509CertChain(listOf(testSignerCertificate())))

        assertFailsWith<IssuerNotTrustedException> {
            trustAnswering(TrustVerdict.UNDETERMINED).enforcePidSigners(listOf(credential), requestedPid = true)
        }
    }

    @Test
    fun a_pid_with_no_signer_chain_is_refused_without_asking() = runTest {
        val asked = mutableListOf<Asked>()

        assertFailsWith<IssuerNotTrustedException> {
            trustAnswering(TrustVerdict.TRUSTED, asked)
                .enforcePidSigners(listOf(testMdocCredential(pidDocType, chain = null)), requestedPid = true)
        }
        assertTrue(asked.isEmpty(), "with nothing to anchor, there is nothing to ask about")
    }

    @Test
    fun a_document_that_is_not_a_pid_is_never_asked_about() = runTest {
        val asked = mutableListOf<Asked>()
        val credential = testMdocCredential(mdlDocType, X509CertChain(listOf(testSignerCertificate())))

        // Android's policy is INFORM for everything but a PID: nothing would act on the answer.
        trustAnswering(TrustVerdict.NOT_TRUSTED, asked).enforcePidSigners(listOf(credential), requestedPid = false)

        assertTrue(asked.isEmpty())
    }

    @Test
    fun a_credential_requested_as_a_pid_is_checked_whatever_type_it_claims() = runTest {
        // An issuer could otherwise fill a PID document with a credential typed as something else.
        val credential = testMdocCredential(mdlDocType, X509CertChain(listOf(testSignerCertificate())))

        assertFailsWith<IssuerNotTrustedException> {
            trustAnswering(TrustVerdict.NOT_TRUSTED).enforcePidSigners(listOf(credential), requestedPid = true)
        }
    }

    @Test
    fun a_pid_is_checked_even_when_the_request_did_not_say_it_was_one() = runTest {
        val credential = testMdocCredential(pidDocType, X509CertChain(listOf(testSignerCertificate())))

        assertFailsWith<IssuerNotTrustedException> {
            trustAnswering(TrustVerdict.NOT_TRUSTED).enforcePidSigners(listOf(credential), requestedPid = false)
        }
    }

    @Test
    fun an_sd_jwt_pid_is_judged_by_its_x5c_and_its_vct() = runTest {
        val asked = mutableListOf<Asked>()
        val credential = sdJwt("urn:eudi:pid:1", X509CertChain(listOf(testSignerCertificate())))

        assertFailsWith<IssuerNotTrustedException> {
            trustAnswering(TrustVerdict.NOT_TRUSTED, asked).enforcePidSigners(listOf(credential), requestedPid = false)
        }
        assertEquals(VerificationContext.PID, asked.single().context)
    }

    @Test
    fun a_batch_from_one_signer_is_asked_about_once() = runTest {
        val asked = mutableListOf<Asked>()
        val chain = X509CertChain(listOf(testSignerCertificate()))
        val batch = List(5) { testMdocCredential(pidDocType, chain) }

        trustAnswering(TrustVerdict.TRUSTED, asked).enforcePidSigners(batch, requestedPid = true)

        assertEquals(1, asked.size)
    }

    @Test
    fun the_registry_knows_which_configurations_an_issuer_publishes_as_a_pid() = runTest {
        val endpoint = "https://registry-a.test/credential"
        val metadata = Json.parseToJsonElement(
            """
            {"credential_issuer":"https://registry-a.test","credential_endpoint":"$endpoint",
             "credential_configurations_supported":{
               "pid_mdoc":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1"},
               "pid_sd_jwt":{"format":"dc+sd-jwt","vct":"urn:eudi:pid:1"},
               "mdl":{"format":"mso_mdoc","doctype":"org.iso.18013.5.1.mDL"}}}
            """.trimIndent()
        ).jsonObject

        PidConfigurationRegistry.record(metadata)

        assertTrue(PidConfigurationRegistry.isPid(endpoint, "pid_mdoc"))
        assertTrue(PidConfigurationRegistry.isPid(endpoint, "pid_sd_jwt"))
        assertFalse(PidConfigurationRegistry.isPid(endpoint, "mdl"))
        // Keyed by issuer: the same configuration id at another issuer says nothing.
        assertFalse(PidConfigurationRegistry.isPid("https://registry-b.test/credential", "pid_mdoc"))
    }

    @Test
    fun a_refusal_is_recognised_through_whatever_wrapped_it() {
        val wrapped = IllegalStateException("provisioning failed", IssuerNotTrustedException("not trusted"))

        assertTrue(wrapped.isIssuerNotTrusted())
        assertFalse(IllegalStateException("issuer said no").isIssuerNotTrusted())
    }
}

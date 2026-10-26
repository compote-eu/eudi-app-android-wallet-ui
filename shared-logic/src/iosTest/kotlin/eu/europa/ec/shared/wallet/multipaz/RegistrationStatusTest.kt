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

import eu.europa.ec.shared.wallet.revocation.StatusSignerTrustDomain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.revocation.StatusList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * A registration certificate's status, as both validators read it: believed only from a signer the lists trust for that
 * status (wallet-core #454 found the same gap on its side; ours computed the verdict and then ignored it).
 */
class RegistrationStatusTest {

    private val reference = StatusReference(uri = "https://registrar.test/status/1", index = 7)

    /** A list where [reference]'s index is revoked or not, signed by a leaf certified by its own root, as registrars publish it. */
    private suspend fun token(revoked: Boolean): String {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val builder = StatusList.Builder(1)
        if (revoked) builder.addStatus(reference.index, 1)
        return builder.build().compress().serializeAsJwt(
            key = AsymmetricKey.X509CertifiedExplicit(certChain = leafCertifiedBy(key, rootKey), privateKey = key),
            subject = reference.uri,
        )
    }

    private suspend fun leafCertifiedBy(key: EcPrivateKey, rootKey: EcPrivateKey): X509CertChain {
        val rootName = X500Name.fromName("CN=Status List Root")
        val root = X509Cert.Builder(
            publicKey = rootKey.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = rootKey),
            serialNumber = ASN1Integer(10L),
            subject = rootName,
            issuer = rootName,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        ).build()
        val leaf = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = rootKey),
            serialNumber = ASN1Integer(11L),
            subject = X500Name.fromName("CN=Status List Signer"),
            issuer = rootName,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        ).build()
        return X509CertChain(listOf(leaf, root))
    }

    private fun serving(token: String) = HttpClient(MockEngine { respond(token) })

    @Test
    fun a_list_from_a_trusted_signer_is_read() = runTest {
        val asked = mutableListOf<String>()
        val trusted: suspend (X509CertChain) -> Boolean = { chain ->
            asked += chain.certificates.first().subject.name
            true
        }

        assertEquals(RevocationOutcome.Valid(StatusSignerTrustDomain.Trusted), registrationStatusOf(reference, serving(token(revoked = false)), trusted))
        assertEquals(RevocationOutcome.Invalid(StatusSignerTrustDomain.Trusted), registrationStatusOf(reference, serving(token(revoked = true)), trusted))
        assertEquals(listOf("CN=Status List Signer", "CN=Status List Signer"), asked)
    }

    /** Anyone can sign "valid" with a certificate of their own: from a signer the lists do not trust, it says nothing. */
    @Test
    fun a_valid_status_from_an_untrusted_signer_is_not_believed() = runTest {
        assertIs<RevocationOutcome.Unknown>(registrationStatusOf(reference, serving(token(revoked = false))) { false })
    }

    @Test
    fun nor_is_a_revoked_one() = runTest {
        assertIs<RevocationOutcome.Unknown>(registrationStatusOf(reference, serving(token(revoked = true))) { false })
    }
}

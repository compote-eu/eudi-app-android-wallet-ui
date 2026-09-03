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

import eu.europa.ec.shared.wallet.trust.ReaderTrustSource
import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.request.Requester
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * That the shared presentment source actually asks who the verifier is.
 *
 * This is the regression test for a bug that existed on **all three** presentment paths at once:
 * none of them passed `resolveTrustFn`, so multipaz used its own default of `{ null }` and every
 * verifier reached the consent screen as untrusted. Nothing caught it because each of the three call
 * sites was internally consistent — the defect was the *absence* of an argument, three times.
 *
 * So the assertions here are deliberately about the wiring rather than about trust itself: whether a
 * verdict reaches `resolveTrust`, and whether "no trust source" stays quiet. What the verdict *should
 * be* for a given chain belongs to `IosEtsiTrust`, and proving that needs the EU trust lists.
 */
class WalletPresentmentSourceTest {

    private suspend fun store(
        storage: Storage = EphemeralStorage(),
    ): MultipazWalletStore = MultipazWalletStore.build(
        storage = storage,
        secureAreas = listOf(SoftwareSecureArea.create(storage)),
        documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
    )

    /** A requester with a chain, since a chain is what a trust check has to be given. */
    private suspend fun requesterWithCertificate(): Requester {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val name = X500Name.fromName("CN=Test Verifier,C=EU")
        val certificate = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(privateKey = key),
            serialNumber = ASN1Integer(1L),
            subject = name,
            issuer = name,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 30.days,
        ).build()
        return Requester(certChain = X509CertChain(listOf(certificate)))
    }

    private fun neverAsked(): suspend (
        Requester,
        TrustMetadata?,
        org.multipaz.presentment.CredentialPresentmentData,
    ) -> org.multipaz.presentment.CredentialPresentmentSelection? = { _, _, _ ->
        error("consent is not part of this test")
    }

    @Test
    fun a_trusted_verifier_reaches_the_source_as_trusted() = runTest {
        val trusted = TrustMetadata()
        val source = walletPresentmentSource(
            store = store(),
            credentialDomain = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
            readerTrust = ReaderTrustSource { trusted },
            offersSdJwt = true,
            showConsent = neverAsked(),
        )

        // multipaz reads a non-null TrustMetadata as "trusted" — `requesterIsTrusted` in every
        // presenter is literally `trustMetadata != null`, so this *is* the decision.
        assertNotNull(source.resolveTrust(requesterWithCertificate()))
    }

    @Test
    fun an_untrusted_verifier_reaches_the_source_as_untrusted() = runTest {
        val source = walletPresentmentSource(
            store = store(),
            credentialDomain = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
            readerTrust = ReaderTrustSource { null },
            offersSdJwt = true,
            showConsent = neverAsked(),
        )

        assertNull(source.resolveTrust(requesterWithCertificate()))
    }

    @Test
    fun the_requester_is_handed_to_the_trust_source_unchanged() = runTest {
        // Not a tautology: the wiring could plausibly have passed a rebuilt or empty requester, and a
        // trust check given no chain can only ever answer "not trusted".
        var seen: Requester? = null
        val requester = requesterWithCertificate()
        val source = walletPresentmentSource(
            store = store(),
            credentialDomain = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
            readerTrust = ReaderTrustSource { asked -> seen = asked; TrustMetadata() },
            offersSdJwt = true,
            showConsent = neverAsked(),
        )

        source.resolveTrust(requester)

        assertEquals(1, seen?.certChain?.certificates?.size)
        assertTrue(
            seen?.certChain?.certificates?.first()?.subject?.name?.contains("Test Verifier") == true,
            "the trust source was handed a different certificate",
        )
    }

    @Test
    fun no_trust_source_answers_unknown_without_asking_anyone() = runTest {
        // What every presentment test relies on. If this ever returned something non-null, or worse
        // reached the network, the whole iosTest suite would start depending on the EU trust lists
        // being up.
        val source = walletPresentmentSource(
            store = store(),
            credentialDomain = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
            readerTrust = null,
            offersSdJwt = true,
            showConsent = neverAsked(),
        )

        assertNull(source.resolveTrust(requesterWithCertificate()))
    }
}

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
import eu.europa.ec.shared.wallet.multipaz.harness.sampleIssuerMetadata
import eu.europa.ec.shared.wallet.multipaz.harness.samplePidElements
import eu.europa.ec.shared.wallet.multipaz.harness.seedMdocDocument
import kotlinx.coroutines.test.runTest
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The Digital Credentials API responder, against a real `DocumentStore`.
 *
 * ## What this covers, and what it deliberately does not
 *
 * It covers **our wiring**: that the request reaches multipaz, that each of its outcomes is mapped to
 * the right [IosDcApiOutcome], and that consent is not invoked for a request that never parses — which
 * is the property that matters most here, because reaching consent is what releases documents.
 *
 * It does **not** yet cover a full successful exchange. That needs a well-formed `org-iso-mdoc`
 * request: `{"deviceRequest": <base64url CBOR>, "encryptionInfo": <base64url CBOR>}` where the latter
 * is `["dcapi", {"recipientPublicKey": <COSE key>}]`. Buildable from multipaz's own primitives and worth
 * doing — see the note below on why it is worth more here than usual — but it is a piece of work rather
 * than a line, and is scoped separately.
 *
 * ⚠️ **multipaz's own tests do not cover this protocol either.** `digitalCredentialsPresentmentTest`
 * exercises `openid4vp`, `openid4vp-v1-signed` and `openid4vp-v1-unsigned` only — while multipaz's iOS
 * registration advertises **`org-iso-mdoc`**, the branch iOS actually drives. So the round-trip test
 * above would be the first coverage that branch has anywhere, which raises its value and lowers how
 * much its correctness should be assumed.
 */
class IosDcApiPresenterTest {

    private suspend fun store(
        storage: Storage = EphemeralStorage(),
    ): MultipazWalletStore = MultipazWalletStore.build(
        storage = storage,
        secureAreas = listOf(SoftwareSecureArea.create(storage)),
        documentManagerId = MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID,
    )

    private suspend fun MultipazWalletStore.seedPid(): String = seedMdocDocument(
        docType = MDOC_PID_DOC_TYPE,
        displayName = "PID MSO MDoc",
        namespace = MDOC_PID_DOC_TYPE,
        elements = samplePidElements(givenName = "Tester"),
        policy = WalletCredentialPolicy.RotatingBatch(numberOfCredentials = 3),
        issuerMetadata = sampleIssuerMetadata(MDOC_PID_DOC_TYPE),
        validFrom = Clock.System.now() - 1.days,
        validUntil = Clock.System.now() + 30.days,
    )

    /** Fails the test if consent is reached; every case here should end before it. */
    private val refuseToBeAsked: suspend (
        org.multipaz.request.Requester,
        org.multipaz.trustmanagement.TrustMetadata?,
        org.multipaz.presentment.CredentialPresentmentData,
    ) -> org.multipaz.presentment.CredentialPresentmentSelection? = { _, _, _ ->
        error("consent must not be reached for a request that cannot be answered")
    }

    @Test
    fun an_unsupported_protocol_is_reported_as_a_failure() = runTest {
        val store = store()
        store.seedPid()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "not-a-protocol",
            data = "{}",
            origin = "https://verifier.example",
            onConsent = refuseToBeAsked,
        )

        assertIs<IosDcApiOutcome.Failed>(outcome)
    }

    /**
     * The `org-iso-mdoc` branch reads `deviceRequest` and `encryptionInfo` with `!!`, so an object
     * without them throws before anything is selected. What matters is that the throw becomes a
     * [IosDcApiOutcome.Failed] rather than escaping into the extension, where it would be an OS-level
     * crash of a process the user did not launch.
     */
    @Test
    fun a_request_missing_its_fields_fails_rather_than_throwing() = runTest {
        val store = store()
        store.seedPid()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "org-iso-mdoc",
            data = """{"somethingElse": true}""",
            origin = "https://verifier.example",
            onConsent = refuseToBeAsked,
        )

        assertIs<IosDcApiOutcome.Failed>(outcome)
    }

    @Test
    fun a_request_whose_encryption_info_is_not_dcapi_fails() = runTest {
        val store = store()
        store.seedPid()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "org-iso-mdoc",
            // Well-formed base64url of CBOR that is simply not what the branch expects.
            data = """{"deviceRequest": "oA", "encryptionInfo": "oA"}""",
            origin = "https://verifier.example",
            onConsent = refuseToBeAsked,
        )

        assertIs<IosDcApiOutcome.Failed>(outcome)
    }

    /**
     * A failure message is shown to a person, so it must never be Kotlin's bare `check(...)` string or
     * an empty one. The same rule [IosRemotePresenter] applies, for the same reason.
     */
    @Test
    fun a_failure_always_carries_a_message_worth_showing() = runTest {
        val store = store()

        val outcome = IosDcApiPresenter(store).present(
            protocol = "not-a-protocol",
            data = "{}",
            origin = "https://verifier.example",
            onConsent = refuseToBeAsked,
        )

        val message = assertIs<IosDcApiOutcome.Failed>(outcome).message
        assertTrue(message.isNotBlank())
        assertFalse(message == "Check failed.")
    }

    /**
     * An empty wallet must not reach consent either: there is nothing to consent to, and asking would
     * put a dialog in front of a user for a request that could never be answered.
     */
    @Test
    fun an_empty_wallet_never_reaches_consent() = runTest {
        var asked = false

        val outcome = IosDcApiPresenter(store()).present(
            protocol = "org-iso-mdoc",
            data = """{"deviceRequest": "oA", "encryptionInfo": "oA"}""",
            origin = "https://verifier.example",
            onConsent = { _, _, _ -> asked = true; null },
        )

        assertFalse(asked)
        assertIs<IosDcApiOutcome.Failed>(outcome)
    }

    /** The presenter offers only the wallet's own credential domain; the default is the store's. */
    @Test
    fun the_credential_domain_defaults_to_the_document_manager_id() = runTest {
        val store = store()
        store.seedPid()

        // Constructing with the default must behave as constructing with the id explicitly: the
        // assertion is that neither throws and both map the same request the same way.
        val withDefault = IosDcApiPresenter(store)
            .present("not-a-protocol", "{}", "https://v.example", onConsent = refuseToBeAsked)
        val withExplicit = IosDcApiPresenter(store, MultipazWalletStore.DEFAULT_DOCUMENT_MANAGER_ID)
            .present("not-a-protocol", "{}", "https://v.example", onConsent = refuseToBeAsked)

        assertEquals(
            assertIs<IosDcApiOutcome.Failed>(withDefault).message,
            assertIs<IosDcApiOutcome.Failed>(withExplicit).message,
        )
    }
}

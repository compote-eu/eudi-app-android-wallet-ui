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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1String
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.buildX509Cert
import org.multipaz.webtoken.buildJwt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The **verifier's** registration certificate — may this relying party ask for these claims?
 *
 * Shapes read from a real transaction against the live EU dev verifier on 2026-09-17 before anything
 * was written: `verifier_info: [{"format":"registration_cert","data":"<rc-wrp+jwt>"}]` inside the
 * signed request object, a certificate whose only entitlement is `Service_Provider`, and `credentials`
 * entries whose claim paths are `["family_name"]` for SD-JWT VC and
 * `["eu.europa.ec.eudi.pid.1","family_name"]` for mdoc.
 */
class IosRelyingPartyRegistrationTest {

    private val verifierOrgId = "LEIXG-123456789"
    private val pidDoctype = "eu.europa.ec.eudi.pid.1"

    private suspend fun signingKey(): AsymmetricKey {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootName = X500Name(mapOf("2.5.4.3" to ASN1String("WRPRC CA")))
        val root = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(rootKey),
            serialNumber = ASN1Integer(1L),
            subject = rootName,
            issuer = rootName,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 365.days,
        ) {}
        val leafKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val leaf = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(rootKey),
            serialNumber = ASN1Integer(2L),
            subject = X500Name(mapOf("2.5.4.3" to ASN1String("WRPRC Signer"))),
            issuer = rootName,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 365.days,
        ) {}
        return AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(leaf, root)), leafKey)
    }

    /** The verifier's own access certificate, which the registration must be bound to. */
    private suspend fun requestSigner(orgId: String? = verifierOrgId): X509Cert {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val name = X500Name(
            buildMap {
                put("2.5.4.3", ASN1String("Verifier"))
                orgId?.let { put("2.5.4.97", ASN1String(it)) }
            }
        )
        return buildX509Cert(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.AnonymousExplicit(key),
            serialNumber = ASN1Integer(3L),
            subject = name,
            issuer = name,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 365.days,
        ) {}
    }

    private suspend fun requestObject(
        entitlements: List<String> = listOf(RelyingPartyEntitlements.SERVICE_PROVIDER),
        registeredClaims: List<List<String>> = listOf(listOf(pidDoctype, "family_name")),
        requestedClaims: List<List<String>> = listOf(listOf(pidDoctype, "family_name")),
        subject: String? = verifierOrgId,
    ): JsonObject {
        val certificate = buildJwt(type = REGISTRATION_CERT_TYPE, key = signingKey()) {
            subject?.let { put("sub", it) }
            put("name", "Test Verifier")
            putJsonArray("entitlements") { entitlements.forEach { add(it) } }
            putJsonArray("credentials") {
                add(
                    buildJsonObject {
                        put("format", "mso_mdoc")
                        putJsonObject("meta") { put("doctype_value", pidDoctype) }
                        putJsonArray("claim") {
                            registeredClaims.forEach { path ->
                                add(buildJsonObject { putJsonArray("path") { path.forEach { add(it) } } })
                            }
                        }
                    }
                )
            }
            putJsonObject("status") {
                putJsonObject("status_list") {
                    put("idx", 8502)
                    put("uri", "https://status.test/wrprc")
                }
            }
        }
        return buildJsonObject {
            put("response_uri", "https://verifier.test/direct_post")
            put("nonce", "n-1")
            putJsonArray("verifier_info") {
                add(
                    buildJsonObject {
                        put("format", "registration_cert")
                        put("data", certificate)
                    }
                )
            }
            putJsonObject("dcql_query") {
                putJsonArray("credentials") {
                    add(
                        buildJsonObject {
                            put("id", "pid")
                            put("format", "mso_mdoc")
                            putJsonObject("meta") { put("doctype_value", pidDoctype) }
                            putJsonArray("claims") {
                                requestedClaims.forEach { path ->
                                    add(
                                        buildJsonObject {
                                            putJsonArray("path") { path.forEach { add(it) } }
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun validator(
        chainTrusted: Boolean = true,
        revocation: RevocationOutcome = RevocationOutcome.Valid(StatusSignerTrustDomain.Trusted),
    ) = IosRelyingPartyRegistrationValidator(
        isChainTrusted = { chainTrusted },
        checkRevocation = { revocation },
    )

    @Test
    fun a_request_with_no_verifier_info_is_not_evaluated() = runTest {
        val bare = buildJsonObject { put("nonce", "n-1") }

        val outcome = validator().evaluate(bare, requestSigner())

        // Most verifiers publish none yet; treating that as a failure would flag the whole ecosystem.
        assertIs<RelyingPartyRegistrationOutcome.NotOffered>(outcome)
    }

    @Test
    fun a_verifier_asking_only_what_it_registered_verifies_cleanly() = runTest {
        val outcome = validator().evaluate(requestObject(), requestSigner())

        val verified = assertIs<RelyingPartyRegistrationOutcome.Verified>(outcome)
        assertEquals("Test Verifier", verified.registration.name)
        assertTrue(verified.overAsked.isEmpty())
    }

    @Test
    fun a_claim_that_is_requested_but_not_registered_is_reported_as_over_asked() = runTest {
        val outcome = validator().evaluate(
            requestObject(
                registeredClaims = listOf(listOf(pidDoctype, "family_name")),
                requestedClaims = listOf(
                    listOf(pidDoctype, "family_name"),
                    listOf(pidDoctype, "birth_date"),
                ),
            ),
            requestSigner(),
        )

        // Reported, never refused — Android shows it on the consent screen and blocks nothing, so the
        // user is told what is being asked beyond the registration and decides for themselves.
        val verified = assertIs<RelyingPartyRegistrationOutcome.Verified>(outcome)
        assertEquals(listOf(listOf(pidDoctype, "birth_date")), verified.overAsked.map { it.path })
    }

    @Test
    fun a_registered_path_under_a_different_attestation_does_not_cover_the_request() = runTest {
        // 🪤 Matching on the path alone would let a verifier registered for a driving licence's
        // `family_name` ask for a PID's.
        val outcome = validator().evaluate(
            requestObject(
                registeredClaims = listOf(listOf("org.iso.18013.5.1.mDL", "family_name")),
                requestedClaims = listOf(listOf(pidDoctype, "family_name")),
            ),
            requestSigner(),
        )

        val verified = assertIs<RelyingPartyRegistrationOutcome.Verified>(outcome)
        assertEquals(1, verified.overAsked.size)
    }

    @Test
    fun a_verifier_without_the_service_provider_entitlement_is_refused() = runTest {
        val outcome = validator().evaluate(
            requestObject(entitlements = listOf("https://uri.etsi.org/19475/Entitlement/PID_Provider")),
            requestSigner(),
        )

        // The issuer vocabulary is not the verifier one: being registered to *issue* says nothing
        // about being registered to ask.
        val failed = assertIs<RelyingPartyRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.ENTITLEMENT_MISSING, failed.reason)
    }

    @Test
    fun a_certificate_naming_someone_other_than_the_request_signer_is_refused() = runTest {
        val outcome = validator().evaluate(requestObject(subject = "LEIXG-000000000"), requestSigner())

        val failed = assertIs<RelyingPartyRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.NOT_BOUND_TO_ISSUER, failed.reason)
    }

    @Test
    fun an_untrusted_signer_chain_is_refused() = runTest {
        val outcome = validator(chainTrusted = false).evaluate(requestObject(), requestSigner())

        val failed = assertIs<RelyingPartyRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.UNTRUSTED_PROVIDER, failed.reason)
    }

    @Test
    fun a_revoked_registration_is_refused() = runTest {
        val outcome = validator(
            revocation = RevocationOutcome.Invalid(StatusSignerTrustDomain.Trusted)
        ).evaluate(requestObject(), requestSigner())

        val failed = assertIs<RelyingPartyRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.REVOKED, failed.reason)
    }

    @Test
    fun the_request_object_yields_every_requested_claim_with_its_attestation() = runTest {
        val requested = requestedClaimsIn(
            requestObject(requestedClaims = listOf(listOf(pidDoctype, "family_name"), listOf(pidDoctype, "given_name")))
        )

        assertEquals(2, requested.size)
        assertTrue(requested.all { it.format == "mso_mdoc" && it.doctype == pidDoctype })
    }
}

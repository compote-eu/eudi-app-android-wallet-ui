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
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The issuer's registration certificate — is this issuer registered to hand out what it is offering?
 *
 * Every expectation here was read from the **live EU dev issuer's own certificate** before it was
 * written down: `typ: rc-wrp+jwt`, ES256, a one-entry `x5c`, `sub` carrying an organization
 * identifier that matches the metadata signer's `organizationIdentifier`, ETSI 119 475 entitlement
 * URIs, a `status.status_list`, and **no `exp`**. That last one is not a detail — see
 * [a_certificate_issued_weeks_ago_is_not_expired].
 */
class IosIssuerRegistrationTest {

    private val pidVct = "urn:eudi:pid:1"
    private val issuerOrgId = "LEIXG-123456789"

    /** Ours to define: only this wallet knows which attestation it treats as the PID. */
    private val isPid: (OfferedAttestation) -> Boolean =
        { it.doctype == "eu.europa.ec.eudi.pid.1" || pidVct in it.vctValues }

    private val pidOffer = OfferedAttestation(format = "dc+sd-jwt", vctValues = listOf(pidVct))

    private suspend fun signerChain(): Pair<AsymmetricKey, X509CertChain> {
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootName = X500Name(mapOf("2.5.4.3" to ASN1String("Registration CA")))
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
            subject = X500Name(mapOf("2.5.4.3" to ASN1String("Registration Signer"))),
            issuer = rootName,
            validFrom = Clock.System.now() - 1.days,
            validUntil = Clock.System.now() + 365.days,
        ) {}

        // The root is dropped from `x5c` by multipaz, which is why the live certificate carries one
        // entry — build the chain the same way so the fixture matches the wire.
        val chain = X509CertChain(listOf(leaf, root))
        return AsymmetricKey.X509CertifiedExplicit(chain, leafKey) to chain
    }

    /** A certificate whose subject carries [orgId] as its `organizationIdentifier`. */
    private suspend fun metadataSigner(orgId: String? = issuerOrgId): X509Cert {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val name = X500Name(
            buildMap {
                put("2.5.4.3", ASN1String("Issuer Signer"))
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

    private suspend fun metadataWithCertificate(
        issuedAt: Instant = Clock.System.now(),
        subject: String? = issuerOrgId,
        entitlements: List<String> = listOf(IssuerEntitlements.PID),
        withStatus: Boolean = true,
        provides: List<Pair<String, String>> = listOf("dc+sd-jwt" to pidVct),
        type: String = REGISTRATION_CERT_TYPE,
    ): JsonObject {
        val (key, _) = signerChain()
        val compact = buildJwt(
            type = type,
            key = key,
            creationTime = issuedAt,
        ) {
            subject?.let { put("sub", it) }
            put("name", "Test PID Provider")
            putJsonArray("entitlements") { entitlements.forEach { add(it) } }
            putJsonArray("provides_attestations") {
                provides.forEach { (format, vct) ->
                    add(
                        buildJsonObject {
                            put("format", format)
                            putJsonObject("meta") { putJsonArray("vct_values") { add(vct) } }
                        }
                    )
                }
            }
            if (withStatus) {
                putJsonObject("status") {
                    putJsonObject("status_list") {
                        put("idx", 3930)
                        put("uri", "https://status.test/list")
                    }
                }
            }
        }
        return buildJsonObject {
            put("credential_issuer", "https://issuer.test")
            putJsonArray("issuer_info") {
                add(
                    buildJsonObject {
                        put("format", "registration_cert")
                        put("data", compact)
                    }
                )
            }
        }
    }

    private fun validator(
        chainTrusted: Boolean = true,
        revocation: RevocationOutcome = RevocationOutcome.Valid(StatusSignerTrustDomain.Trusted),
    ) = IosIssuerRegistrationValidator(
        isChainTrusted = { chainTrusted },
        checkRevocation = { revocation },
        isPid = isPid,
    )

    @Test
    fun an_issuer_that_publishes_no_certificate_is_not_a_failure() = runTest {
        val metadata = buildJsonObject { put("credential_issuer", "https://issuer.test") }

        val outcome = validator().evaluate(metadata, metadataSigner(), listOf(pidOffer))

        // Most issuers publish none today. Treating that as a failure would refuse the whole ecosystem.
        assertIs<IssuerRegistrationOutcome.NotOffered>(outcome)
    }

    @Test
    fun a_certificate_issued_weeks_ago_is_not_expired() = runTest {
        // 🪤 THE TRAP THIS TEST EXISTS FOR. multipaz's `validateJwt` defaults to `maxValidity = 10h`
        // and, when a token has no `exp`, derives one from `iat` — so it rejects a six-week-old
        // certificate as "expired". The live EU dev certificate has `iat` and NO `exp`, so the default
        // would refuse every real issuer. Mutating `maxValidity` back to the default fails only here.
        val metadata = metadataWithCertificate(issuedAt = Clock.System.now() - 42.days)

        val outcome = validator().evaluate(metadata, metadataSigner(), listOf(pidOffer))

        assertIs<IssuerRegistrationOutcome.Verified>(outcome)
    }

    @Test
    fun a_certificate_whose_signer_is_not_trusted_is_refused() = runTest {
        val metadata = metadataWithCertificate()

        val outcome = validator(chainTrusted = false)
            .evaluate(metadata, metadataSigner(), listOf(pidOffer))

        // And it must be distinguishable from a bad signature: one means "we cannot place this
        // issuer", the other means "this document was tampered with".
        val failed = assertIs<IssuerRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.UNTRUSTED_PROVIDER, failed.reason)
    }

    @Test
    fun a_certificate_naming_someone_other_than_the_metadata_signer_is_refused() = runTest {
        val metadata = metadataWithCertificate(subject = "LEIXG-999999999")

        val outcome = validator().evaluate(metadata, metadataSigner(), listOf(pidOffer))

        // Without this, any issuer could publish any other issuer's certificate and inherit its
        // entitlements. The binding is the whole point of the `sub`.
        val failed = assertIs<IssuerRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.NOT_BOUND_TO_ISSUER, failed.reason)
    }

    @Test
    fun unsigned_metadata_cannot_satisfy_the_binding() = runTest {
        val metadata = metadataWithCertificate()

        val outcome = validator().evaluate(metadata, metadataSigner = null, listOf(pidOffer))

        val failed = assertIs<IssuerRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.NOT_BOUND_TO_ISSUER, failed.reason)
    }

    @Test
    fun a_certificate_with_no_status_reference_is_refused() = runTest {
        val metadata = metadataWithCertificate(withStatus = false)

        val outcome = validator().evaluate(metadata, metadataSigner(), listOf(pidOffer))

        val failed = assertIs<IssuerRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.STATUS_MISSING, failed.reason)
    }

    @Test
    fun a_revoked_certificate_is_refused() = runTest {
        val metadata = metadataWithCertificate()

        val outcome = validator(revocation = RevocationOutcome.Invalid(StatusSignerTrustDomain.Trusted))
            .evaluate(metadata, metadataSigner(), listOf(pidOffer))

        val failed = assertIs<IssuerRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.REVOKED, failed.reason)
    }

    @Test
    fun a_status_that_cannot_be_read_fails_closed() = runTest {
        val metadata = metadataWithCertificate()

        val outcome = validator(revocation = RevocationOutcome.Unknown("the list did not answer"))
            .evaluate(metadata, metadataSigner(), listOf(pidOffer))

        // The opposite of our *document* revocation posture, deliberately: nothing has been issued
        // yet, so declining to vouch costs the user nothing.
        val failed = assertIs<IssuerRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.REVOCATION_STATUS_UNKNOWN, failed.reason)
    }

    @Test
    fun an_issuer_offering_a_pid_without_the_pid_entitlement_is_refused() = runTest {
        val metadata = metadataWithCertificate(entitlements = listOf(IssuerEntitlements.QEAA))

        val outcome = validator().evaluate(metadata, metadataSigner(), listOf(pidOffer))

        // A QEAA provider is registered — but not to issue a PID, which is the distinction the
        // entitlement list exists to draw.
        val failed = assertIs<IssuerRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.ENTITLEMENT_MISSING, failed.reason)
    }

    @Test
    fun any_of_the_three_eaa_entitlements_satisfies_a_non_pid_offer() = runTest {
        val eaaOffer = OfferedAttestation(format = "dc+sd-jwt", vctValues = listOf("urn:eudi:mdl:1"))
        val metadata = metadataWithCertificate(
            entitlements = listOf(IssuerEntitlements.NON_Q_EAA),
            provides = listOf("dc+sd-jwt" to "urn:eudi:mdl:1"),
        )

        val outcome = validator().evaluate(metadata, metadataSigner(), listOf(eaaOffer))

        assertIs<IssuerRegistrationOutcome.Verified>(outcome)
    }

    @Test
    fun over_providing_is_reported_and_never_refused() = runTest {
        val extra = OfferedAttestation(format = "dc+sd-jwt", vctValues = listOf("urn:eudi:mdl:1"))
        val metadata = metadataWithCertificate(
            entitlements = listOf(IssuerEntitlements.PID, IssuerEntitlements.QEAA),
            provides = listOf("dc+sd-jwt" to pidVct),
        )

        val outcome = validator().evaluate(metadata, metadataSigner(), listOf(pidOffer, extra))

        // The issuer is entitled to issue EAAs, so this is not an entitlement failure — but it is
        // handing out something it never registered, and the user is the one who should be told.
        val verified = assertIs<IssuerRegistrationOutcome.Verified>(outcome)
        assertEquals(listOf(extra), verified.overProvided)
        assertEquals(issuerOrgId, verified.registration.subject)
    }

    @Test
    fun a_certificate_of_the_wrong_media_type_is_malformed() = runTest {
        val metadata = metadataWithCertificate(type = "jwt")

        val outcome = validator().evaluate(metadata, metadataSigner(), listOf(pidOffer))

        val failed = assertIs<IssuerRegistrationOutcome.Failed>(outcome)
        assertEquals(IssuerRegistrationFailure.MALFORMED, failed.reason)
        assertTrue(failed.detail!!.contains(REGISTRATION_CERT_TYPE))
    }
}

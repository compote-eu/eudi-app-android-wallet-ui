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

// VERIFICATION FIXTURE for revocation, beside the document fixture and for the same reason: iOS cannot
// issue, and no test issuer observed so far puts a `status` element in its credentials — so without
// this there is nothing whose revocation could be checked, and the feature could only ever be
// unit-tested.
//
// What it provides is a document that points at a status list *we* can answer, and the tokens to
// answer it with. The signing key is fixed rather than random precisely so the two halves can be
// prepared in separate app launches: the document embeds the certificate on one run, and a token
// signed by the same key — printed by the probe, served from the developer's machine — satisfies it on
// the next. Delete all of this once a real issuer's credentials carry status lists.
package eu.europa.ec.shared.wallet.multipaz.harness

import eu.europa.ec.shared.wallet.multipaz.MultipazWalletStore
import eu.europa.ec.shared.wallet.multipaz.credentialPolicyFor
import kotlin.time.Clock
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.revocation.RevocationStatus
import org.multipaz.revocation.StatusList
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** The index this fixture's document occupies in its status list. Arbitrary; just not 0. */
const val REVOCATION_FIXTURE_INDEX = 4

/** Where the fixture document says its status list lives. Served by the developer's machine. */
const val REVOCATION_FIXTURE_URI = "http://localhost:8000/statuslist.jwt"

/**
 * The fixed status-list signing key.
 *
 * A hardcoded test keypair, generated once with `openssl ecparam -name prime256v1`. It exists only so
 * that a token signed on one run verifies against a certificate embedded on another — it signs
 * nothing but fixture status lists, and the credential it is bound to is itself a fixture.
 */
private val fixtureSigningKey: EcPrivateKey = EcPrivateKeyDoubleCoordinate(
    curve = EcCurve.P256,
    d = "7cf5061232fed52ceb408effe8553730713e9ff4f7a79f83f6bb4e0d2debf2e9".hexToByteArray(),
    x = "c2287fbd32cdfbefa45c6af6155995392cafcbe7232bb6b4033deba1eebfda9e".hexToByteArray(),
    y = "1c6f12634811d5dd75e69062970a2843805bb37bf317ec453ec71ceed0a2b1dc".hexToByteArray(),
)

/**
 * A status-list token this fixture's document will accept, saying its index is [revoked] or not.
 *
 * Built with multipaz's own `StatusList` — the same code path that reads it back — so the bytes are a
 * genuine `statuslist+jwt`: zlib-deflated bitstring, base64url, signed ES256. `expiresIn` is generous
 * because a person has to copy this into a file between two app launches; the default is ~20 minutes.
 */
suspend fun revocationFixtureToken(revoked: Boolean): String =
    StatusList.Builder(bitsPerItem = 1)
        .apply { if (revoked) addStatus(REVOCATION_FIXTURE_INDEX, 1) }
        .build()
        .compress()
        .serializeAsJwt(
            key = AsymmetricKey.AnonymousExplicit(privateKey = fixtureSigningKey),
            subject = REVOCATION_FIXTURE_URI,
            expiresIn = 120.minutes,
        )

/** The certificate the fixture document pins its status list to — the public half of the key above. */
private suspend fun fixtureSignerCertificate(): X509Cert {
    val name = X500Name.fromName("CN=EUDI iOS Revocation Fixture")
    return X509Cert.Builder(
        publicKey = fixtureSigningKey.publicKey,
        signingKey = AsymmetricKey.AnonymousExplicit(privateKey = fixtureSigningKey),
        serialNumber = ASN1Integer(1L),
        subject = name,
        issuer = name,
        validFrom = Clock.System.now() - 1.days,
        validUntil = Clock.System.now() + 3650.days,
    ).build()
}

/**
 * Seeds a document whose MSO carries a `status_list` entry, if the wallet has none yet.
 *
 * Public and store-opening for the same reason as [seedIosWalletFixture]: the console probe lives in
 * `:shared-ui` and must not name a multipaz type.
 *
 * @return the document's id, or null when one was already present.
 */
suspend fun seedIosRevocableFixture(): String? {
    val store = MultipazWalletStore.open()
    if (store.documentStore.listDocuments().any { it.displayName == REVOCATION_FIXTURE_NAME }) {
        return null
    }

    return store.seedMdocDocument(
        docType = MDOC_PID_DOC_TYPE,
        displayName = REVOCATION_FIXTURE_NAME,
        namespace = MDOC_PID_DOC_TYPE,
        elements = samplePidElements(givenName = "Revocable"),
        policy = credentialPolicyFor(MDOC_PID_DOC_TYPE, numberOfCredentials = 1),
        issuerMetadata = sampleIssuerMetadata(MDOC_PID_DOC_TYPE),
        revocationStatus = RevocationStatus.StatusList(
            idx = REVOCATION_FIXTURE_INDEX,
            uri = REVOCATION_FIXTURE_URI,
            certificate = fixtureSignerCertificate(),
        ),
    )
}

private const val REVOCATION_FIXTURE_NAME = "PID (revocation fixture)"

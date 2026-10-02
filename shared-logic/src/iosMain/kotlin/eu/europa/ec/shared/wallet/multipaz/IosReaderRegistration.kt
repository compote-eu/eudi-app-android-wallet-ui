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

import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.request.DeviceRequest

/**
 * A reader's registration certificate in an ISO 18013-5 request, read as Android's data-transfer
 * `DeviceRequestProcessor` reads it (0.30.2).
 *
 * ETSI TS 119 472-2 clause 5.3.2 repeats the certificate in the `requestInfo` of every `ItemsRequest`, as a
 * byte string under `euWrprc`. Identical copies are one certificate; two different ones make the request
 * ambiguous, and Android fails it.
 *
 * No reader sent one as of 2026-10-02 — no EU verifier app has the key — so with the registration check on
 * every reader is a [Absent] one, and the consent screen warns, as Android's does.
 */
internal sealed interface ReaderRegistrationCertificate {

    data object Absent : ReaderRegistrationCertificate

    /** The certificate as a compact JWS, the form [IosRelyingPartyRegistrationValidator] reads. */
    data class Jwt(val compact: String) : ReaderRegistrationCertificate

    /**
     * Anything else, which Android parses as a COSE_Sign1 (CWT) certificate. This wallet does not read that
     * form yet, so it is reported as unverified rather than taken on trust.
     */
    data object Cwt : ReaderRegistrationCertificate
}

/**
 * The certificate this request carries.
 *
 * @throws IllegalArgumentException when it carries two different ones — Android's message, as its
 *   request processor fails the request with it.
 */
internal fun DeviceRequest.readerRegistrationCertificate(): ReaderRegistrationCertificate {
    val certificates = docRequests
        .mapNotNull { it.docRequestInfo?.otherInfo?.get(EU_WRPRC_REQUEST_INFO_KEY) }
        .mapNotNull { runCatching { it.asBstr }.getOrNull() }
        .distinctBy { it.toList() }
    require(certificates.size <= 1) { "Device Request carries multiple relying party registration certificates" }
    val bytes = certificates.singleOrNull() ?: return ReaderRegistrationCertificate.Absent
    // wallet-core tells the two forms apart the same way: ASCII that is a compact JWS, or else a CWT.
    val text = bytes.decodeToString().trim()
    return if (COMPACT_JWS.matches(text)) ReaderRegistrationCertificate.Jwt(text) else ReaderRegistrationCertificate.Cwt
}

/** Every element this request asks for, keyed as a registration keys it. */
internal fun DeviceRequest.requestedClaims(): List<OverAskedClaim> = docRequests.flatMap { docRequest ->
    docRequest.nameSpaces.flatMap { (namespace, elements) ->
        elements.keys.map { element ->
            OverAskedClaim(format = MSO_MDOC, path = listOf(namespace, element), doctype = docRequest.docType)
        }
    }
}

/**
 * What the reader's [certificate] says about this request, bound to [reader] — the certificate that
 * authenticated the request, as Android binds it to the reader's access chain.
 *
 * @param requested what the request asks for ([requestedClaims]).
 */
internal suspend fun IosRelyingPartyRegistrationValidator.evaluateReader(
    certificate: ReaderRegistrationCertificate,
    requested: List<OverAskedClaim>,
    reader: X509Cert?,
): RelyingPartyRegistrationOutcome = when (certificate) {
    ReaderRegistrationCertificate.Absent ->
        RelyingPartyRegistrationOutcome.Failed(IssuerRegistrationFailure.CERTIFICATE_ABSENT)

    is ReaderRegistrationCertificate.Jwt -> evaluateCertificate(certificate.compact, reader, requested)

    ReaderRegistrationCertificate.Cwt -> RelyingPartyRegistrationOutcome.Failed(
        IssuerRegistrationFailure.MALFORMED,
        detail = "a CWT registration certificate, a form this wallet does not read yet",
    )
}

/** The `requestInfo` key ETSI TS 119 472-2 clause 5.3.2 carries the certificate under. */
internal const val EU_WRPRC_REQUEST_INFO_KEY = "euWrprc"

private const val MSO_MDOC = "mso_mdoc"

/** wallet-core's own test for a compact JWS. */
private val COMPACT_JWS = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

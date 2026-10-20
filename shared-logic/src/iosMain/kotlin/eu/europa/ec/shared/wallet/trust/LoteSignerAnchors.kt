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

package eu.europa.ec.shared.wallet.trust

import eu.europa.ec.eudi.etsi1196x2.consultation.NonEmptyList
import org.multipaz.crypto.X509Cert
import platform.Foundation.NSData

/**
 * The certificates a list of trusted entities has to be signed under — wallet-core's
 * `loteSignerTrustAnchors()` (eudi-lib-android-wallet-core #441, `release/0.32.0`).
 *
 * ⛔ **An anchor is never taken from a list.** A list carries its signer in its own `x5c`, so an anchor
 * read from there would vouch for whoever served the list — the very gap these anchors close.
 */
internal object LoteSignerAnchors {

    /**
     * The EU lists' signer and the CA that issued it.
     *
     * Measured 2026-10-20: all four lists at `trustedlist.serviceproviders.eudiw.dev` are signed by
     * [PY_PID_ISSUER_DEV], issued by [PID_ISSUER_CA_02_EU].
     * - **The CA** lets the EU replace its signer without a release: a new signer chains to it (PKIX).
     * - **The signer itself** matches by its bytes, with no PKIX. The simulator's `SecTrust` refuses chains
     *   a phone accepts (`OSStatus -26276`), so without it every EU list would be refused there. It is
     *   pinned only because it chains to the CA — `openssl verify` and macOS `security verify-cert` both
     *   said so on 2026-10-20, and `LoteJwtVerifierTest` checks the signature on every run.
     */
    val EU: NonEmptyList<NSData> by lazy { NonEmptyList(EU_CERTIFICATES.toTrustChain()) }

    /** [EU] as certificates, for the test that checks the signer chains to the CA. */
    internal val EU_CERTIFICATES: List<X509Cert> by lazy {
        listOf(X509Cert.fromPem(PID_ISSUER_CA_02_EU), X509Cert.fromPem(PY_PID_ISSUER_DEV))
    }

    /**
     * `CN=PID Issuer CA 02, O=EUDI Wallet Reference Implementation, C=EU`, valid to 2034-07-06.
     *
     * Copied byte for byte from upstream's `resources-logic/src/main/res/raw/pidissuerca02_eu.pem` (SHA-256 of
     * the DER `3b0a223e8748f3c42217298ad905ecf1a104e73cd2c54ff21da16fda8070b297`), the same file Android's RQES
     * configuration trusts, and identical to the copy the EU publishes at
     * `https://preprod.pki.eudiw.dev/aia/PIDIssuerCA02-EU.cacert.pem` (compared 2026-10-20).
     */
    private const val PID_ISSUER_CA_02_EU = """-----BEGIN CERTIFICATE-----
MIIC0zCCAnmgAwIBAgIUXRXxkLbUM6+njr/XT0IIw/HA/uowCgYIKoZIzj0EAwMw
VzEZMBcGA1UEAwwQUElEIElzc3VlciBDQSAwMjEtMCsGA1UECgwkRVVESSBXYWxs
ZXQgUmVmZXJlbmNlIEltcGxlbWVudGF0aW9uMQswCQYDVQQGEwJFVTAeFw0yNTA0
MDkwMDAzMzBaFw0zNDA3MDYwMDAzMjlaMFcxGTAXBgNVBAMMEFBJRCBJc3N1ZXIg
Q0EgMDIxLTArBgNVBAoMJEVVREkgV2FsbGV0IFJlZmVyZW5jZSBJbXBsZW1lbnRh
dGlvbjELMAkGA1UEBhMCRVUwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARkqdLm
wIlv+SSWr00tAIrt7EAMztgd3w9qA6qEm16yVfsLcyx2f4oIWuH45wa37J9GoNWp
deo27VoSoNMCzxOYo4IBITCCAR0wEgYDVR0TAQH/BAgwBgEB/wIBADAfBgNVHSME
GDAWgBRCUFC+ELgQ8J1EXI2/qxAI7ifcSTATBgNVHSUEDDAKBggrgQICAAABBzBD
BgNVHR8EPDA6MDigNqA0hjJodHRwczovL3ByZXByb2QucGtpLmV1ZGl3LmRldi9j
cmwvcGlkX0NBX0VVXzAyLmNybDAdBgNVHQ4EFgQUQlBQvhC4EPCdRFyNv6sQCO4n
3EkwDgYDVR0PAQH/BAQDAgEGMF0GA1UdEgRWMFSGUmh0dHBzOi8vZ2l0aHViLmNv
bS9ldS1kaWdpdGFsLWlkZW50aXR5LXdhbGxldC9hcmNoaXRlY3R1cmUtYW5kLXJl
ZmVyZW5jZS1mcmFtZXdvcmswCgYIKoZIzj0EAwMDSAAwRQIhAIavYfC5o0VVLKfg
TKkzzWgc09hzDMsCl3O2le2sQfG7AiA2soqAN5gtUOLQKWK00DUz22EW79rvaV+V
JPvfdQeokA==
-----END CERTIFICATE-----"""

    /** `CN=PY PID Issuer DEV, O=Niscy, C=EU`, valid to 2028-05-13: `x5c[0]` of all four EU lists on 2026-10-20. */
    private const val PY_PID_ISSUER_DEV = """-----BEGIN CERTIFICATE-----
MIIC+zCCAqGgAwIBAgIUW22xswATij4qQ9uk7kaDywVM3fUwCgYIKoZIzj0EAwIw
VzEZMBcGA1UEAwwQUElEIElzc3VlciBDQSAwMjEtMCsGA1UECgwkRVVESSBXYWxs
ZXQgUmVmZXJlbmNlIEltcGxlbWVudGF0aW9uMQswCQYDVQQGEwJFVTAeFw0yNjA1
MTQxMDM3NDVaFw0yODA1MTMxMDM3NDRaMFMxGjAYBgNVBAMMEVBZIFBJRCBJc3N1
ZXIgREVWMRgwFgYDVQRhDA9MRUlFVS0xMjM0NTY3ODkxDjAMBgNVBAoMBU5pc2N5
MQswCQYDVQQGEwJFVTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABBPjSsdghC2v
h4TXNR0qsZWy6uOguz8cFafO9LRVeQghAR/4kuREG3mh0bnKzd0f1n9bQuG41KkL
nT8cjo5El+CjggFNMIIBSTAMBgNVHRMBAf8EAjAAMB8GA1UdIwQYMBaAFEJQUL4Q
uBDwnURcjb+rEAjuJ9xJMFkGCCsGAQUFBwEBBE0wSzBJBggrBgEFBQcwAoY9aHR0
cHM6Ly9wcmVwcm9kLnBraS5ldWRpdy5kZXYvYWlhL1BJRElzc3VlckNBMDItRVUu
Y2FjZXJ0LnBlbTAuBgNVHSAEJzAlMCMGAyoDBDAcMBoGCCsGAQUFBwIBFg5leGFt
cGxlLnBvbGljeTBDBgNVHR8EPDA6MDigNqA0hjJodHRwczovL3ByZXByb2QucGtp
LmV1ZGl3LmRldi9jcmwvcGlkX0NBX0VVXzAyLmNybDAdBgNVHQ4EFgQU5I7+2at3
vmyxiVBtSD5wu+qMqHowDgYDVR0PAQH/BAQDAgeAMBkGCCsGAQUFBwEDBA0wCzAJ
BgcEAIvsTgEBMAoGCCqGSM49BAMCA0gAMEUCIAewVEHpqrz7JYrSAYQS+jcY5zY5
5DeziLSWbmKVHEKUAiEA1JCeihjOgTLnz1PkpoQpcmGnTrqof+lOrjvhyTMt3rE=
-----END CERTIFICATE-----"""
}

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

package eu.europa.ec.corelogic.config

import eu.europa.ec.corelogic.BuildConfig
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.eudi.etsi119602.datamodel.Uri
import eu.europa.ec.eudi.etsi1196x2.consultation.AttestationClassifications
import eu.europa.ec.eudi.etsi1196x2.consultation.AttestationIdentifier
import eu.europa.ec.eudi.etsi1196x2.consultation.AttestationIdentifierPredicate
import eu.europa.ec.eudi.etsi1196x2.consultation.SupportedLists
import eu.europa.ec.eudi.iso18013.transfer.response.ReaderAuthPolicy
import eu.europa.ec.eudi.openid4vci.CredentialReusePolicies
import eu.europa.ec.eudi.openid4vci.EudiReusePolicyType
import eu.europa.ec.eudi.wallet.EudiWalletConfig
import eu.europa.ec.eudi.wallet.dcapi.DCAPIProtocol
import eu.europa.ec.eudi.wallet.document.CreateDocumentSettings.CredentialPolicy
import eu.europa.ec.eudi.wallet.issue.openid4vci.OpenId4VciManager
import eu.europa.ec.eudi.wallet.issue.openid4vci.dpop.DPopConfig
import eu.europa.ec.eudi.wallet.transfer.openId4vp.ClientIdScheme
import eu.europa.ec.eudi.wallet.transfer.openId4vp.Format
import eu.europa.ec.eudi.wallet.trust.TrustPolicy
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `sk` flavor Wallet Core configuration.
 *
 * All four backend services are declared here. The two that are **necessary** to
 * actually issue a credential (Issuer and Wallet Provider) point at the developer's
 * machine (`BuildConfig.LOCAL_IP`, configurable via `localIp` in local.properties or
 * the LOCAL_IP env var). The **optional** services (Trusted list / LoTE and RQES —
 * RQES lives in [eu.europa.ec.businesslogic.config.RQESConfigImpl]) stay on the `dev`
 * reference infrastructure, with the local alternative provided as a commented-out
 * line. Flip any single service by swapping the active/commented lines.
 *
 * Port scheme for local services (adjust to whatever your local stack binds to):
 *   :8443 Issuer   ·   :8445 Wallet Provider   ·   :8446 Trusted list   ·   :8447 RQES
 */
internal class WalletCoreConfigImpl : WalletCoreConfig {

    private var _config: EudiWalletConfig? = null

    override val config: EudiWalletConfig
        get() {
            if (_config == null) {
                _config = EudiWalletConfig {
                    configureDocumentKeyCreation(
                        userAuthenticationRequired = false,
                        userAuthenticationTimeout = 30.seconds,
                        useStrongBoxForKeys = true
                    )
                    configureOpenId4Vp {
                        withClientIdSchemes(
                            listOf(
                                ClientIdScheme.X509SanDns,
                                ClientIdScheme.X509Hash
                            )
                        )
                        withSchemes(
                            listOf(
                                BuildConfig.OPENID4VP_SCHEME,
                                BuildConfig.EUDI_OPENID4VP_SCHEME,
                                BuildConfig.MDOC_OPENID4VP_SCHEME,
                                BuildConfig.HAIP_OPENID4VP_SCHEME
                            )
                        )
                        withFormats(
                            Format.MsoMdoc.ES256, Format.SdJwtVc.ES256
                        )
                    }

                    configureDCAPI {
                        withEnabled(true)
                        withSupportedProtocols(
                            DCAPIProtocol.ISO_MDOC,
                            DCAPIProtocol.OPENID4VP_V1_SIGNED,
                        )
                    }

                    configureEtsiTrust {
                        // OPTIONAL service: Trusted list (LoTE).
                        // Active = dev reference trusted list. The local Issuer above is
                        // NOT listed here, which is why issuer trust is relaxed to INFORM
                        // below. To run your own local LoTE that lists the local issuer,
                        // uncomment the local URLs and switch configureIssuerTrust to
                        // ENFORCE.
                        loteLocations(
                            SupportedLists(
                                pidProviders = Uri("https://trustedlist.serviceproviders.eudiw.dev/LOTE/json/PIDProviders.jwt"),
                                wrpacProviders = Uri("https://trustedlist.serviceproviders.eudiw.dev/LOTE/json/WRPACProviders.jwt"),
                                pubEaaProviders = Uri("https://trustedlist.serviceproviders.eudiw.dev/LOTE/json/PubEAAProviders.jwt"),
                                // Local alternative (run a trusted-list server on :8446):
                                // pidProviders = Uri("https://${BuildConfig.LOCAL_IP}:8446/LOTE/json/PIDProviders.jwt"),
                                // wrpacProviders = Uri("https://${BuildConfig.LOCAL_IP}:8446/LOTE/json/WRPACProviders.jwt"),
                                // pubEaaProviders = Uri("https://${BuildConfig.LOCAL_IP}:8446/LOTE/json/PubEAAProviders.jwt"),
                            )
                        )

                        classifications(
                            AttestationClassifications(
                                pids = AttestationIdentifierPredicate.any(
                                    identifiers = setOf(
                                        AttestationIdentifier.MDoc(
                                            docType = DocumentIdentifier.MdocPid.formatType
                                        ),
                                        AttestationIdentifier.SDJwtVc(
                                            vct = DocumentIdentifier.SdJwtPid.formatType
                                        ),
                                    )
                                )
                            )
                        )

                        relaxCertificateProfiles()
                        relaxPkixRevocation()
                    }

                    configureIssuerTrust {
                        // Relaxed to INFORM for local development: the local Issuer is not
                        // present in the dev trusted list above, so ENFORCE would block
                        // every issuance. Switch back to ENFORCE once you point LoTE at a
                        // local trusted list that lists your local issuer.
                        policy { default(TrustPolicy.Action.INFORM) }
                        // policy { default(TrustPolicy.Action.ENFORCE) }

                        // The SDK default is MetadataPolicyMode.REQUIRE, so NOT calling
                        // requireSignedMetadata() still REQUIRES it (resolution fails with
                        // "missing signed metadata"). Local issuers typically serve UNSIGNED
                        // metadata, so explicitly opt out here.
                        ignoreSignedMetadata()
                        // For a signed-metadata issuer use requireSignedMetadata(), or
                        // preferSignedMetadata() to accept both.
                    }

                    configureDocumentStatusResolver {
                        configureTrust {
                            policy {
                                default(TrustPolicy.Action.INFORM)
                            }
                        }
                    }

                    configureReaderTrustStore {
                        readerAuthPolicy(ReaderAuthPolicy.EnforceIfPresent)
                    }
                }
            }
            return _config!!
        }

    override val issuersConfig: List<VciConfig>
        get() = listOf(
            // NECESSARY service: Issuer (OpenID4VCI).
            // Active = local machine (:8443). Dev alternative is commented below.
            VciConfig(
                issuerUrl = "https://${BuildConfig.LOCAL_IP}:8443",
                // issuerUrl = "https://ec.dev.issuer.eudiw.dev", // dev fallback
                config = OpenId4VciManager.Config.Builder()
                    .withClientAuthenticationType(
                        // Local: public client (the local AS advertises "public" auth). Avoids
                        // the attestation-based client-auth chain (challenge endpoint, trusted
                        // attesters, wallet-instance-attestation). The credential proof still
                        // uses key attestation. For the attestation-based flow use:
                        // OpenId4VciManager.ClientAuthenticationType.AttestationBased(clientId = "eudiw-abca")
                        OpenId4VciManager.ClientAuthenticationType.None(
                            clientId = "eudiw-abca"
                        )
                    )
                    .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                    .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                    .withDPopConfig(DPopConfig.Default)
                    .withSupportedCredentialReusePolicies(
                        CredentialReusePolicies.Supported(
                            policyTypes = setOf(
                                EudiReusePolicyType.RotatingBatch,
                                EudiReusePolicyType.OnceOnly,
                                EudiReusePolicyType.LimitedTime,
                            )
                        )
                    )
                    .build(),
                order = 0
            )
            // Second dev issuer (issuer-backend) intentionally omitted for local.
            // Add another VciConfig here if you also run it locally.
        )

    override val documentIssuanceConfig: DocumentIssuanceConfig
        get() = DocumentIssuanceConfig(
            defaultPolicy = CredentialPolicy.RotatingBatch(
                numberOfCredentials = 1,
                reissueTriggerLifetimeLeft = 24.hours
            ),
            documentSpecificPolicies = mapOf(
                DocumentIdentifier.MdocPid to CredentialPolicy.OnceOnly(
                    numberOfCredentials = 60,
                    reissueTriggerUnused = 2
                ),
                DocumentIdentifier.SdJwtPid to CredentialPolicy.OnceOnly(
                    numberOfCredentials = 60,
                    reissueTriggerUnused = 2
                ),
            ),
            reissuanceRule = ReIssuanceRule(
                backgroundInterval = 15.minutes
            )
        )

    // NECESSARY service: Wallet Provider (wallet + key attestation).
    // Active = local machine (:8445). Dev alternative is commented below.
    override val walletProviderHost: String
        get() = "https://${BuildConfig.LOCAL_IP}:8445"
        // get() = "https://dev.wallet-provider.eudiw.dev" // dev fallback
}

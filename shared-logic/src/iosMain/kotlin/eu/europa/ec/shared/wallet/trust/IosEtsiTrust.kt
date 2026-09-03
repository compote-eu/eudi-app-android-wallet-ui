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

import eu.europa.ec.eudi.etsi119602.consultation.DownloadSingleLoTE
import eu.europa.ec.eudi.etsi119602.consultation.IosLoTEHttpClient
import eu.europa.ec.eudi.etsi119602.consultation.LoadLoTE
import eu.europa.ec.eudi.etsi119602.consultation.LoadLoTEAndPointers
import eu.europa.ec.eudi.etsi119602.consultation.LoadSingleLoTEWithFileCache
import eu.europa.ec.eudi.etsi119602.consultation.LotEMeta
import eu.europa.ec.eudi.etsi119602.consultation.ProvisionTrustAnchorsFromLoTEs
import eu.europa.ec.eudi.etsi119602.consultation.VerifyJwtSignature
import eu.europa.ec.eudi.etsi119602.consultation.eu
import eu.europa.ec.eudi.etsi119602.consultation.eudiwIos
import eu.europa.ec.eudi.etsi119602.datamodel.Uri
import eu.europa.ec.eudi.etsi1196x2.consultation.CertificationChainValidation
import eu.europa.ec.eudi.etsi1196x2.consultation.SupportedLists
import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import eu.europa.ec.shared.wallet.platform.IosAppGroup
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.hours

/**
 * Whether a certificate chain is trusted, according to the EU lists of trusted entities.
 *
 * The iOS counterpart of Android's `configureEtsiTrust`, and configured to match it rather than to be
 * stricter — the two platforms should reach the same verdict about the same issuer or verifier.
 *
 * **Four things are copied from Android's `WalletCoreConfigImpl`, not chosen here:**
 * - The **same four list URLs**, in both flavours. Android's `dev` and `demo` name identical values,
 *   so this is not per-variant configuration and does not belong in
 *   [eu.europa.ec.shared.wallet.config.IosWalletConfig].
 * - **`relaxCertificateProfiles()`** → [relaxedEndEntityProfiles]. Without it the EU dev PID issuer
 *   is rejected with *"Certificate does not contain any QCStatement"*, which is exactly the case that
 *   call exists for.
 * - **`relaxPkixRevocation()`** → free here: `ValidateCertificateChainUsingPKIXIos` already runs with
 *   revocation checking disabled, so there is nothing to switch off.
 * - **`DoNotLoadOtherPointers`**, so a list can only introduce the entities it names, never redirect
 *   the wallet to a further list of someone else's choosing.
 *
 * ⚠️ The list's own signature is checked by [LoteJwtVerifier], which — like Android's — verifies
 * against the certificate the list carries. That is integrity, not authenticity; see its
 * documentation, which is also why this class is not the last word on trust.
 *
 * ## Why not the library's own `EudiwIosTrust` façade
 *
 * `EudiwIosTrust.cached(urls, ttlHours, verifyJwtSignature)` exists in this same version and is
 * tempting: fewer moving parts, caching included. It is **not** used, for two reasons that both
 * matter here.
 *
 * 1. It applies the library's default certificate profiles, with no way to relax them — which is the
 *    one setting Android had to change to make the EU dev PID issuer verifiable at all.
 * 2. Its `IosValidationResult` collapses *"no list covers this context"* into `isTrusted = false`,
 *    and this wallet has to tell that apart from a decided refusal. See [TrustVerdict].
 *
 * So the pipeline is assembled here instead, from the same parts the façade uses.
 */
internal class IosEtsiTrust(
    private val listUrls: LoteListUrls = LoteListUrls.EU_DEV,
    private val verifier: VerifyJwtSignature = LoteJwtVerifier(),
    /**
     * Where downloaded lists are kept between checks; null downloads on every check.
     *
     * Injectable so a test can point it at a scratch directory — never at the real container.
     */
    private val cacheDirectory: Path? = defaultCacheDirectory(),
) : IssuerTrustSource, ReaderTrustSource {

    /** The four EU lists this wallet consults, matching Android's `loteLocations`. */
    data class LoteListUrls(
        val pidProviders: String,
        val wrpacProviders: String,
        val wrprcProviders: String,
        val pubEaaProviders: String,
    ) {
        companion object {
            /**
             * The hosts Android names in **both** flavours. Not a per-variant value — see the class
             * documentation — so a single constant is the faithful mirror, not a shortcut.
             */
            val EU_DEV = LoteListUrls(
                pidProviders = "$BASE/PIDProviders.jwt",
                wrpacProviders = "$BASE/WRPACProviders.jwt",
                wrprcProviders = "$BASE/WRPRCProviders.jwt",
                pubEaaProviders = "$BASE/PubEAAProviders.jwt",
            )

            private const val BASE = "https://trustedlist.serviceproviders.eudiw.dev/LOTE/json"
        }
    }

    private val validator by lazy {
        ProvisionTrustAnchorsFromLoTEs
            .eudiwIos(
                loadLoTEAndPointers = LoadLoTEAndPointers(
                    // A list may name entities; it may not send the wallet to another list. Android's
                    // configuration has no pointer-following either.
                    constraints = LoadLoTEAndPointers.Constraints.DoNotLoadOtherPointers,
                    verifyJwtSignature = verifier,
                    loadLoTE = loader(),
                ),
                svcTypePerCtx = SupportedLists.eu().relaxedEndEntityProfiles(),
            )
            // `nonCached` refers to the *anchor* set, not the lists: the caching that matters is
            // [loader]'s, which is on disk and survives process death. The in-memory `cached(...)`
            // variant would add a second, shorter-lived layer whose TTL could disagree with the
            // files' — and buys nothing in the document-provider extension, which is a fresh process
            // per request.
            .nonCached(
                SupportedLists(
                    pidProviders = Uri(listUrls.pidProviders),
                    wrpacProviders = Uri(listUrls.wrpacProviders),
                    wrprcProviders = Uri(listUrls.wrprcProviders),
                    pubEaaProviders = Uri(listUrls.pubEaaProviders),
                )
            )
    }

    /**
     * Downloads a list, or reads a recent copy from disk.
     *
     * The cache is the difference between a usable check and a slow one: without it every trust
     * decision costs an HTTPS round trip on the presentation path, while the user waits at a consent
     * screen. Measured warm, a second verdict takes ~6ms.
     *
     * 📌 One list per *context*, not four per check — observed, not assumed: a run that asked about
     * PID and WRPAC fetched exactly those two files. So naming a list this wallet does not yet
     * consult (`wrprcProviders`) costs nothing until something asks for that context.
     *
     * It lives in the app group so the **extension shares it** — that process is started fresh for
     * each Digital Credentials request and would otherwise never have a warm cache at all.
     */
    private fun loader(): LoadLoTE<String> {
        val download = DownloadSingleLoTE(IosLoTEHttpClient.create())
        val directory = cacheDirectory ?: return download
        return LoadSingleLoTEWithFileCache(
            cacheDirectory = directory,
            downloadSingleLoTE = download,
            // A list carrying its own `nextUpdate` overrides this; it applies only to one that does
            // not. Short enough that a withdrawn entity stops being trusted the same day, long
            // enough that an offline wallet keeps working.
            fileCacheExpiration = CACHE_EXPIRATION,
        )
    }

    /**
     * Whether [chain] is trusted for [context].
     *
     * Never throws: a trust check that cannot complete must not take down the flow that asked it.
     * Such a failure is [TrustVerdict.UNDETERMINED], logged, and left for the caller to weigh.
     */
    override suspend fun verdict(
        chain: List<NSData>,
        context: VerificationContext,
    ): TrustVerdict {
        if (chain.isEmpty()) return TrustVerdict.NOT_TRUSTED
        return runCatching { validator.invoke(chain, context) }
            .fold(
                onSuccess = { validation ->
                    when {
                        validation is CertificationChainValidation.Trusted -> TrustVerdict.TRUSTED
                        validation == null -> {
                            // No list covers this context, so nothing can be concluded either way.
                            Logger.i(TAG, "no trust list covers $context")
                            TrustVerdict.UNDETERMINED
                        }

                        else -> {
                            Logger.i(TAG, "chain not trusted for $context: $validation")
                            TrustVerdict.NOT_TRUSTED
                        }
                    }
                },
                onFailure = {
                    Logger.w(TAG, "trust check for $context could not complete", it)
                    TrustVerdict.UNDETERMINED
                },
            )
    }

    /** Trusted, and only trusted — for callers that just want to show a badge. */
    suspend fun isTrusted(chain: List<NSData>, context: VerificationContext): Boolean =
        verdict(chain, context) == TrustVerdict.TRUSTED

    /**
     * What multipaz should be told about a verifier that just sent a request, or null when it is not
     * trusted.
     *
     * multipaz reads a non-null [TrustMetadata] as "trusted" — `requesterIsTrusted` in every
     * presenter is literally `trustMetadata != null` — so returning one is the whole decision. No
     * `displayName` is supplied because the trust list does not carry one the presenters could use;
     * they already fall back to the certificate's common name, which is the same string Android
     * shows.
     *
     * A verifier is checked against **WRPAC** (wallet relying-party *access* certificates), which is
     * the list Android points `wrpacProviders` at for exactly this.
     *
     * ⚠️ [TrustVerdict.UNDETERMINED] returns null here, and that is the right way round: an unverified
     * verifier must not be *shown as* trusted. It is the opposite choice from issuance, where an
     * unreachable list must not block the flow — the asymmetry is deliberate, because the cost of
     * being wrong runs the other way. Nothing is refused on this path; the consent screen still
     * opens, it simply does not vouch for who is asking.
     */
    @OptIn(ExperimentalEncodingApi::class, BetaInteropApi::class)
    override suspend fun trustMetadataFor(requester: Requester): TrustMetadata? {
        val chain = requester.certChain?.certificates?.map { certificate ->
            // Round-tripping through base64 rather than pinning bytes with `memScoped`: `NSData` needs
            // a stable buffer, and `NSData(base64Encoded:)` gives one without cinterop lifetime rules.
            NSData.create(
                base64EncodedString = Base64.Default.encode(certificate.encoded.toByteArray()),
                options = 0uL,
            )
        }?.filterNotNull().orEmpty()

        if (chain.isEmpty()) {
            // A URI-scheme presentation can arrive with no chain at all; "unknown" is the honest
            // answer.
            return null
        }
        return if (isTrusted(chain, VerificationContext.WalletRelyingPartyAccessCertificate)) {
            TrustMetadata()
        } else {
            null
        }
    }

    internal companion object {
        private const val TAG = "IosEtsiTrust"

        /** See [loader]. */
        private val CACHE_EXPIRATION = 12.hours

        /** Subdirectory of the app group; a sibling of `wallet/`, not inside it. */
        private const val CACHE_DIRECTORY = "trust-lists"

        /**
         * The shared cache directory, created if absent, or null when there is no app group.
         *
         * Null degrades to downloading rather than failing — the same reasoning
         * [eu.europa.ec.shared.wallet.multipaz.MultipazWalletStore] applies to the wallet itself, and
         * with less at stake here, since a missing cache costs latency and not data.
         */
        @OptIn(ExperimentalForeignApi::class)
        fun defaultCacheDirectory(): Path? {
            val directory: NSURL = IosAppGroup.containerUrl()
                ?.URLByAppendingPathComponent(CACHE_DIRECTORY, isDirectory = true)
                ?: return null
            NSFileManager.defaultManager.createDirectoryAtURL(
                url = directory,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            // `kotlinx.io` wants a filesystem path, not a file URL — `absoluteString` would hand it
            // `file:///…` and every read would miss.
            return directory.path?.let { Path(it) }
        }
    }
}

/**
 * The same lists with every end-entity certificate profile dropped — Android's
 * `relaxCertificateProfiles()`.
 *
 * A null `endEntityProfile` is how this library expresses "do not apply an ETSI end-entity profile";
 * the mDL context upstream is configured exactly that way, with the comment that the advertised lists
 * do not satisfy the strict profiles. The same is true of the EU dev PID issuer, which is why Android
 * relaxes them too. The service-type identifiers and the anchors themselves are untouched, so *which*
 * entities are trusted does not change — only whether their leaf certificate must additionally match
 * a qualified-certificate profile.
 */
private fun SupportedLists<LotEMeta<VerificationContext>>.relaxedEndEntityProfiles():
    SupportedLists<LotEMeta<VerificationContext>> {
    fun LotEMeta<VerificationContext>.relaxed() = copy(
        svcTypePerCtx = svcTypePerCtx.mapValues { (_, svc) -> svc.copy(endEntityProfile = null) },
    )
    return SupportedLists(
        pidProviders = pidProviders?.relaxed(),
        walletProviders = walletProviders?.relaxed(),
        wrpacProviders = wrpacProviders?.relaxed(),
        wrprcProviders = wrprcProviders?.relaxed(),
        pubEaaProviders = pubEaaProviders?.relaxed(),
        qeaProviders = qeaProviders?.relaxed(),
        eaaProviders = eaaProviders.mapValues { (_, meta) -> meta.relaxed() },
    )
}

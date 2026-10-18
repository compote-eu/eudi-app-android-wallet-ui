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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

/**
 * Reading ETSI TS 119 472-3's `credential_reuse_policy`, which is where the batch size and the
 * re-issuance threshold are supposed to come from.
 */
class IssuerReusePolicyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String) = json.decodeFromString(IssuerReusePolicy.serializer(), text)

    @Test
    fun the_eu_dev_issuers_published_policy_is_read_as_published() {
        // Verbatim from https://dev.issuer-backend.eudiw.dev/.well-known/openid-credential-issuer,
        // configuration eu.europa.ec.eudi.pid_vc_sd_jwt.
        val policy = parse(
            """
            {"id":"arf_annex_ii","options":[
              {"details":["once_only"],"batch_size":7,"reissue_trigger_unused":6}
            ]}
            """.trimIndent()
        )

        val selected = policy.firstSupportedOption()!!
        assertEquals("once_only", selected.detail)

        val credentialPolicy = assertIs<WalletCredentialPolicy.OnceOnly>(
            selected.toCredentialPolicy(maxBatchSize = 20)
        )
        // Seven, because the issuer said seven — not the wallet's own preference.
        assertEquals(7, credentialPolicy.numberOfCredentials)
        assertEquals(6, credentialPolicy.reissueTriggerUnused)
    }

    @Test
    fun the_issuers_maximum_still_caps_the_batch() {
        val policy = parse(
            """{"options":[{"details":["once_only"],"batch_size":50,"reissue_trigger_unused":2}]}"""
        )
        val credentialPolicy = policy.firstSupportedOption()!!.toCredentialPolicy(maxBatchSize = 20)
        assertEquals(20, credentialPolicy!!.numberOfCredentials)
    }

    @Test
    fun the_first_supported_option_wins_in_the_issuers_own_order() {
        // per-relying-party is skipped rather than failing the issuance; rotating-batch is next.
        val policy = parse(
            """
            {"options":[
              {"details":["per-relying-party"],"batch_size":5,"reissue_trigger_lifetime_left":3600,
               "reissue_trigger_unused":1},
              {"details":["rotating-batch"],"batch_size":3,"reissue_trigger_lifetime_left":86400},
              {"details":["once_only"],"batch_size":9,"reissue_trigger_unused":2}
            ]}
            """.trimIndent()
        )

        val selected = policy.firstSupportedOption()!!
        assertEquals("rotating-batch", selected.detail)
        val credentialPolicy = assertIs<WalletCredentialPolicy.RotatingBatch>(
            selected.toCredentialPolicy(maxBatchSize = 20)
        )
        assertEquals(3, credentialPolicy.numberOfCredentials)
        assertEquals(24.hours, credentialPolicy.reissueTriggerLifetimeLeft)
    }

    @Test
    fun a_policy_offering_nothing_this_wallet_supports_selects_nothing() {
        val policy = parse(
            """{"options":[{"details":["per-relying-party"],"batch_size":5,
               "reissue_trigger_lifetime_left":60,"reissue_trigger_unused":1}]}"""
        )
        assertNull(policy.firstSupportedOption())
    }

    @Test
    fun a_half_published_option_is_refused_rather_than_guessed() {
        // once_only without its required reissue_trigger_unused: the issuer's mistake, and falling back
        // to the wallet's own rule is more honest than inventing a threshold.
        val policy = parse("""{"options":[{"details":["once_only"],"batch_size":7}]}""")
        assertNull(policy.firstSupportedOption()!!.toCredentialPolicy(maxBatchSize = 20))
    }

    @Test
    fun settings_follow_the_policys_meaning() = runTest {
        val storage = EphemeralStorage()
        val store = MultipazWalletStore.build(
            storage = storage,
            secureAreas = listOf(SoftwareSecureArea.create(storage)),
        )

        val onceOnly = IosDocumentProvisioningHandler.settingsForPolicy(
            store = store,
            policy = WalletCredentialPolicy.OnceOnly(numberOfCredentials = 7, reissueTriggerUnused = 6),
        )
        assertEquals(7, onceOnly.keyBoundCredentialNumPerDomain)
        assertEquals(1, onceOnly.keyBoundCredentialMaxUses, "a once-only credential is spent by one use")

        val rotating = IosDocumentProvisioningHandler.settingsForPolicy(
            store = store,
            policy = WalletCredentialPolicy.RotatingBatch(
                numberOfCredentials = 3,
                reissueTriggerLifetimeLeft = 24.hours,
            ),
        )
        assertEquals(3, rotating.keyBoundCredentialNumPerDomain)
        assertEquals(Int.MAX_VALUE, rotating.keyBoundCredentialMaxUses, "a rotating credential survives")
        assertEquals(24.hours, rotating.minValidTime, "replaced when the issuer's threshold is reached")
    }
}

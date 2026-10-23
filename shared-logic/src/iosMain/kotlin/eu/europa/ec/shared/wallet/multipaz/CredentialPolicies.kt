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
import kotlin.time.Duration.Companion.hours

/**
 * The PID format identifiers, duplicated from `DocumentIdentifier.MdocPid` / `SdJwtPid` in
 * `:shared-ui`'s `eu.europa.ec.corelogic.model`, which this module cannot import — the dependency runs
 * shared-ui -> shared-logic, not the other way. Keep the two in step.
 *
 * One home on purpose: this set had been written out three times in this package before, and the
 * credential policy below is the third thing to need it.
 */
internal val PidFormatTypes = setOf("eu.europa.ec.eudi.pid.1", "urn:eudi:pid:1")

/**
 * How many unused credentials Android's configuration considers "running low": a PID with this many or
 * fewer is re-issued, by `isDueForReIssuance` here and by `ReIssuanceWorkManager` on Android.
 */
private const val PID_REISSUE_TRIGGER_UNUSED = 2

/**
 * Android's `defaultPolicy` for everything that is not a PID, in both flavours: a single credential,
 * re-issued 24 hours before it expires (`WalletCoreConfigImpl.documentIssuanceConfig`).
 */
private const val DEFAULT_NUMBER_OF_CREDENTIALS = 1
private val DEFAULT_REISSUE_TRIGGER_LIFETIME_LEFT = 24.hours

/**
 * The credential policy for a newly created document, keyed by its format.
 *
 * **This is a policy decision, not a fact about the issuance**, which is the bug this replaced. iOS has
 * no per-issuer configuration, so the policy used to be *derived from the batch size*: more than one
 * credential meant [WalletCredentialPolicy.RotatingBatch], exactly one meant
 * [WalletCredentialPolicy.OnceOnly]. Android decides it per document type instead — its
 * `documentSpecificPolicies` map both PID formats to `OnceOnly(reissueTriggerUnused = 2)` and its
 * `defaultPolicy` is `RotatingBatch` — so with a batch size above one, a PID was stamped
 * `RotatingBatch` here and `OnceOnly` there.
 *
 * That is not cosmetic. `StoredDocument.usableCredentials` drops spent credentials only under
 * `OnceOnly`, and `isLowOnCredentials` is *only ever true* for `OnceOnly` — so a PID on iOS counted
 * credentials it had already spent, and could never tell the user it was running out.
 *
 * A PID asks for [pidBatchSize] credentials, the flavour's batch already clamped to the issuer's
 * `maxBatchSize`. Anything else is Android's `defaultPolicy`: one credential, re-issued 24 hours before
 * it expires. iOS used to give those the PID's batch and no lifetime threshold, so a non-PID document
 * took 10 or 60 Secure Enclave keys and was re-issued only once it had already expired.
 */
internal fun credentialPolicyFor(
    formatType: String,
    pidBatchSize: Int,
): WalletCredentialPolicy =
    if (formatType in PidFormatTypes) {
        WalletCredentialPolicy.OnceOnly(
            numberOfCredentials = pidBatchSize,
            reissueTriggerUnused = PID_REISSUE_TRIGGER_UNUSED,
        )
    } else {
        WalletCredentialPolicy.RotatingBatch(
            numberOfCredentials = DEFAULT_NUMBER_OF_CREDENTIALS,
            reissueTriggerLifetimeLeft = DEFAULT_REISSUE_TRIGGER_LIFETIME_LEFT,
        )
    }

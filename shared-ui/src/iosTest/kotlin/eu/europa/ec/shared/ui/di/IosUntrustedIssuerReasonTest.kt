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

package eu.europa.ec.shared.ui.di

import eu.europa.ec.corelogic.model.UntrustedIssuerReasonDomain
import eu.europa.ec.shared.wallet.multipaz.IosIssuanceProgress
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which "issuer not trusted" sheet an iOS refusal shows — the one Android shows for the same refusal. */
class IosUntrustedIssuerReasonTest {

    @Test
    fun each_refusing_certificate_picks_android_s_sheet() {
        assertEquals(
            UntrustedIssuerReasonDomain.ACCESS_CERTIFICATE,
            IosIssuanceProgress.UntrustedCertificate.Access.toUntrustedIssuerReason(),
        )
        assertEquals(
            UntrustedIssuerReasonDomain.REGISTRATION_CERTIFICATE,
            IosIssuanceProgress.UntrustedCertificate.Registration.toUntrustedIssuerReason(),
        )
    }
}

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

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The queue between the iOS app shell and the coroutine waiting for an OAuth redirect.
 *
 * Small but worth pinning: it is shared mutable state reached from two worlds (a Swift delegate and a
 * Kotlin coroutine), and the interesting behaviour is what happens when they arrive out of order — the
 * redirect can land before anything is waiting, because the URL open is what brings the app forward.
 */
class IosAuthorizationRedirectsTest {

    @BeforeTest
    fun setUp() = IosAuthorizationRedirects.clear()

    @AfterTest
    fun tearDown() = IosAuthorizationRedirects.clear()

    @Test
    fun a_redirect_delivered_before_anyone_waits_is_still_received() = runTest {
        val url = "${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=abc&state=xyz"

        assertTrue(IosAuthorizationRedirects.deliver(url))

        // The app shell can win the race with the flow; the value has to wait rather than be dropped.
        assertEquals(url, IosAuthorizationRedirects.await())
    }

    @Test
    fun a_waiting_caller_is_woken_by_a_later_delivery() = runTest {
        val waiting = async { IosAuthorizationRedirects.await() }

        IosAuthorizationRedirects.deliver("${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=1")

        assertEquals("${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=1", waiting.await())
    }

    @Test
    fun only_the_newest_redirect_survives() = runTest {
        IosAuthorizationRedirects.deliver("${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=old")
        IosAuthorizationRedirects.deliver("${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=new")

        // An older redirect carries a spent authorization code, so keeping it would guarantee a
        // rejected token request.
        assertEquals(
            "${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=new",
            IosAuthorizationRedirects.await(),
        )
    }

    @Test
    fun urls_that_are_not_authorization_redirects_are_ignored() = runTest {
        assertFalse(IosAuthorizationRedirects.deliver("https://example.test/callback"))
        assertFalse(IosAuthorizationRedirects.deliver("openid-credential-offer://issuer?x=1"))

        // Nothing was queued, so a waiter times out rather than receiving someone else's URL.
        assertNull(IosAuthorizationRedirects.await(timeout = 50.milliseconds))
    }

    @Test
    fun clearing_drops_a_queued_redirect() = runTest {
        IosAuthorizationRedirects.deliver("${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=stale")

        IosAuthorizationRedirects.clear()

        assertNull(IosAuthorizationRedirects.await(timeout = 50.milliseconds))
    }
}

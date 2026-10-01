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

import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.shared.platform.IosBroadcasts
import eu.europa.ec.shared.platform.PlatformIntent
import eu.europa.ec.shared.platform.platformAction
import eu.europa.ec.shared.platform.platformStringExtra
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    //region the resume announcement — what turns the screen's spinner back on

    /** Everything announced as a resume from now on, collected as it is sent. */
    private fun TestScope.resumeAnnouncements(): List<PlatformIntent> {
        val announced = mutableListOf<PlatformIntent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            IosBroadcasts.receive(listOf(CoreActions.VCI_RESUME_ACTION)).toList(announced)
        }
        return announced
    }

    @Test
    fun a_redirect_for_a_waiting_flow_announces_the_resume_with_the_redirect() = runTest {
        val announced = resumeAnnouncements()
        // Undispatched, so the flow is inside `await` when the redirect lands — the ordinary order.
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { IosAuthorizationRedirects.await() }
        val url = "${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=1"

        IosAuthorizationRedirects.deliver(url)

        assertEquals(url, waiting.await())
        val resume = announced.single()
        assertEquals(CoreActions.VCI_RESUME_ACTION, resume.platformAction())
        assertEquals(url, resume.platformStringExtra("uri"))
    }

    /**
     * On a device the flow waits on another thread, and the queued redirect can wake it, hand it the
     * redirect and end its wait before `deliver` has finished. Unconfined reproduces exactly that here:
     * the waiter resumes inside `deliver`. Whether to announce must not depend on who wins.
     */
    @Test
    fun a_flow_that_takes_the_redirect_at_once_still_gets_the_resume_announced() = runTest {
        val announced = resumeAnnouncements()
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            IosAuthorizationRedirects.await()
        }

        IosAuthorizationRedirects.deliver("${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=fast")

        assertTrue(waiting.isCompleted, "the waiter did not take the redirect inside deliver — no race exercised")
        assertEquals(1, announced.size)
    }

    @Test
    fun a_redirect_nobody_waits_for_is_queued_but_not_announced() = runTest {
        val announced = resumeAnnouncements()
        val url = "${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=early"

        IosAuthorizationRedirects.deliver(url)

        // No flow would act on it now, so a spinner switched on here would never be switched off.
        assertTrue(announced.isEmpty())
        assertEquals(url, IosAuthorizationRedirects.await())
    }

    @Test
    fun a_redirect_after_the_wait_gave_up_is_not_announced() = runTest {
        val announced = resumeAnnouncements()
        assertNull(IosAuthorizationRedirects.await(timeout = 50.milliseconds))

        IosAuthorizationRedirects.deliver("${IosAuthorizationRedirects.REDIRECT_PREFIX}?code=late")

        // The flow failed with "Authorization was not completed." — nothing is left to resume.
        assertTrue(announced.isEmpty())
    }

    @Test
    fun a_url_that_is_not_a_redirect_is_not_announced_even_while_waiting() = runTest {
        val announced = resumeAnnouncements()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            IosAuthorizationRedirects.await(timeout = 50.milliseconds)
        }

        IosAuthorizationRedirects.deliver("https://example.test/callback")

        assertNull(waiting.await())
        assertTrue(announced.isEmpty())
    }

    //endregion
}

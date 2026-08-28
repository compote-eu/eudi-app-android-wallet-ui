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

package eu.europa.ec.analyticslogic.controller

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.multipaz.util.Logger

/**
 * What a deployment implements to receive iOS analytics — the counterpart of Android's
 * `AnalyticsProvider`, and a near-copy of the official iOS wallet's protocol of the same name.
 *
 * [initialize] takes only the key, as theirs does; Android's equivalent also takes the `Application`,
 * which is the one asymmetry, and it is theirs to have rather than ours to invent.
 */
interface IosAnalyticsProvider {

    /** Called once at startup with the key this provider was registered under, e.g. an API token. */
    fun initialize(key: String)

    fun logScreen(name: String, arguments: Map<String, String>)

    fun logEvent(eventName: String, arguments: Map<String, String>)
}

/**
 * iOS's analytics, fanning every report out to whatever providers a deployment registered.
 *
 * ## With no provider registered this does nothing, deliberately
 *
 * Which is exactly what upstream Android and the official native iOS wallet do — see
 * [AnalyticsLogger] for why. The point of having it is the *seam*: a deployment that supplies a
 * provider now gets iOS screen views reported under the same names as Android's, where previously it
 * would have got Android's and silence from iOS.
 *
 * ## Why registration rather than the reflection both reference wallets use
 *
 * Android finds its config with `Class.forName("…AnalyticsConfigImpl")` and the official iOS app with
 * `NSClassFromString("AnalyticsConfigImpl")`. Neither is available here: Kotlin/Native has no
 * `Class.forName`, and while `NSClassFromString` *is* reachable, a Swift type found that way cannot be
 * cast to a Kotlin interface unless it already conforms to an exported protocol — so the lookup would
 * be a fragile way of arriving at the very seam below. [register] is the same shape this fork already
 * uses for the Swift-only `EudiRQESUi`, and it fails loudly rather than silently.
 *
 * ⚠️ The reference wallets' reflection hook fails *silently* by design — the official iOS wiki warns
 * that a `struct` rather than an `NSObject` subclass means "analytics is silently skipped". [register]
 * logs what it registered, so a deployment can tell from the log whether its provider was seen.
 */
object IosAnalytics : AnalyticsLogger {

    /**
     * Guards [providers]. Registration happens at startup and reporting from the navigation host on
     * the main thread, but nothing in the types enforces that ordering, and Kotlin/Native has no
     * `@Synchronized` — the same reasoning as `IosLogFile`.
     */
    private val lock: ReentrantLock = reentrantLock()

    private val providers: MutableMap<String, IosAnalyticsProvider> = mutableMapOf()

    /**
     * Adds a provider under [key], replacing any registered under the same key.
     *
     * Call before [initialize], from the app shell. [key] is what reaches
     * [IosAnalyticsProvider.initialize], mirroring the `[String: AnalyticsProvider]` map both
     * reference wallets key their providers by.
     */
    fun register(key: String, provider: IosAnalyticsProvider) {
        lock.withLock { providers[key] = provider }
        Logger.i(TAG, "registered an analytics provider under '$key'")
    }

    /**
     * Initializes every registered provider. Called once at startup, after registration.
     *
     * The counterpart of Android's `AnalyticsController.initialize(context)` and the official iOS
     * app's `AppDelegate.initializeReporting()`.
     */
    fun initialize() {
        val registered = lock.withLock { providers.toMap() }
        if (registered.isEmpty()) {
            // Said once, at startup, rather than on every screen: this is the normal state for every
            // build in this repo, so it must read as information and not as a fault.
            Logger.i(TAG, "no analytics provider registered; screen and event reports are dropped")
            return
        }
        registered.forEach { (key, provider) -> provider.initialize(key) }
    }

    override fun logScreen(name: String, arguments: Map<String, String>) =
        eachProvider { provider -> provider.logScreen(name, arguments) }

    override fun logEvent(eventName: String, arguments: Map<String, String>) =
        eachProvider { provider -> provider.logEvent(eventName, arguments) }

    /**
     * Runs [report] on every provider, and never lets one of them take the app down.
     *
     * A provider is third-party code on the navigation path: an exception from it would otherwise
     * propagate out of the navigation host and crash the app on a screen change. Android is no
     * better protected, but that is not a reason to copy the exposure.
     */
    private inline fun eachProvider(report: (IosAnalyticsProvider) -> Unit) {
        val registered = lock.withLock { providers.values.toList() }
        registered.forEach { provider ->
            try {
                report(provider)
            } catch (t: Throwable) {
                Logger.e(TAG, "an analytics provider threw and was ignored", t)
            }
        }
    }

    /**
     * Drops every registered provider.
     *
     * `internal`, and only for tests: this is an object, so its providers outlive a single test and
     * one case would otherwise see what another registered. There is no product reason to unregister
     * — a deployment registers once at startup and keeps reporting for the life of the process.
     */
    internal fun resetForTest() {
        lock.withLock { providers.clear() }
    }

    private const val TAG = "IosAnalytics"
}

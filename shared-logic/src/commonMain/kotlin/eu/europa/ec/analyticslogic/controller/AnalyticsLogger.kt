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

// Package `eu.europa.ec.analyticslogic.controller` deliberately, though the file lives in
// :shared-logic — `AnalyticsController` is declared in that package in :analytics-logic and now
// extends this, so every existing Android import stays valid. The same trick used for
// `AppFlavor` and `AuthenticationConfig`.
//
// Filename deliberately *not* `AnalyticsController.kt`: a commonMain file sharing both package and
// filename with an Android one risks a JVM facade clash, which compiles and then fails at runtime.
package eu.europa.ec.analyticslogic.controller

/**
 * Where the app reports what the user is looking at and what they did.
 *
 * ## What this is, and what it deliberately is not
 *
 * It is an **extension point, not a working analytics pipeline.** No provider ships in this repo, so
 * both methods fan out over an empty collection and nothing leaves the device. That is not an
 * oversight being papered over — it matches upstream Android *and* the official native iOS wallet,
 * both of which look up a class named exactly `AnalyticsConfigImpl` at runtime (`Class.forName`
 * there, `NSClassFromString` there) and quietly do nothing when a deployment has not supplied one.
 * Neither reference wallet depends on Firebase, AppCenter or anything similar; "e.g. Firebase" is a
 * comment in their config protocol.
 *
 * So the thing worth keeping symmetric is the **seam**: a deployment that supplies a provider should
 * see the same screens reported on both platforms, under the same names. Before iOS had this, such a
 * deployment would have got Android screen views and silence from iOS.
 *
 * ## Why the split
 *
 * Android's `AnalyticsController` extends this and adds `initialize(context: Application)`; iOS's
 * `IosAnalytics` adds a no-argument `initialize()`. Only the reporting half is platform-neutral, and
 * the official iOS wallet draws the line in exactly the same place — its `AnalyticsController` also
 * declares a context-free `initialize()` beside these two methods.
 *
 * The screen *names* are already shared, in `AppRoute.analyticsName` — which matters more than this
 * interface does, because those strings are the identifiers a dashboard is built on.
 */
interface AnalyticsLogger {

    /**
     * Records that [name] is now on screen, with the identifying arguments in [arguments].
     *
     * Called once per navigation from each platform's navigation host — Android's `RouterHost` and
     * iOS's `IosNavHost` — and nowhere else, so a screen is reported exactly when it is displayed.
     */
    fun logScreen(name: String, arguments: Map<String, String> = emptyMap())

    /** Records something the user did, as opposed to somewhere they went. */
    fun logEvent(eventName: String, arguments: Map<String, String> = emptyMap())
}

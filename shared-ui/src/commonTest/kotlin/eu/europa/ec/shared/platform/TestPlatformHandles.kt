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

package eu.europa.ec.shared.platform

/**
 * Test-only ways to obtain the two opaque platform handles, so that view-model tests which merely
 * pass them through can live in `commonTest` and run on BOTH targets.
 *
 * Neither type can be constructed from common code, and that is unchanged: both are `expect`
 * declarations exposing no constructor, which is what stops common code depending on an Android
 * `Context` or `Intent`. These factories are the test-side escape hatch, resolved per target — a
 * Mockito mock and a real `Intent` on Android, minted tokens on iOS.
 *
 * Reach for these ONLY where the code under test forwards the handle without looking at it. A test
 * that asserts on something an Android `Context` or `Intent` actually *does* is a genuinely Android
 * test and belongs in `androidHostTest`, where the handle is the real thing rather than a stand-in.
 */
internal expect fun testPlatformContext(): PlatformContext

/** See [testPlatformContext]. On iOS this is a memberless token: it carries nothing. */
internal expect fun testPlatformIntent(): PlatformIntent

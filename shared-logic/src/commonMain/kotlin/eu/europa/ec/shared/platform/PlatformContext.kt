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

// Phase 3b, platform-handle layer: the host handle for device authentication. See PlatformActivity.kt
// for the pattern and why these declare no members.
package eu.europa.ec.shared.platform

/**
 * An opaque handle on the platform's host context — Android's `Context`.
 *
 * Distinct from [PlatformActivity] only because the two are different Android static types at
 * different call sites: NFC engagement is handed a `ComponentActivity`, while device authentication is
 * handed a `Context` (which the Android controller narrows to a `FragmentActivity` itself). Both are
 * the host screen semantically, and both are pass-through-only from shared code. Keeping them separate
 * is what makes the Android side a pure `typealias` with no call-site churn: every interactor keeps its
 * `Context` parameter exactly as it was.
 */
// `abstract`, because the Android actual is `android.content.Context`, which is abstract — an
// expect/actual pair has to agree on modality.
expect abstract class PlatformContext

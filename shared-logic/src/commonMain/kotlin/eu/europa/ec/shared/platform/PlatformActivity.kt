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

// Phase 3b — the platform-handle layer. Several view-models are otherwise KMP-clean but name an
// Android type they never actually use: they take it from the composition and hand it straight to a
// platform API. This is the seam `Platform.kt` anticipated ("every platform binding will follow"),
// applied to those pass-through handles so the view-model logic can be shared without pretending the
// platform object is neutral data.
package eu.europa.ec.shared.platform

/**
 * An opaque handle on the host screen — Android's `ComponentActivity`.
 *
 * Deliberately declares **no members**: common code can only receive one and pass it back to a
 * platform binding, never inspect it. That is exactly the contract the view-models need, and it keeps
 * the Android type from leaking any API into shared code.
 *
 * On Android this is an `actual typealias`, so Android call sites and implementations keep their
 * existing `ComponentActivity` types and need no changes at all.
 */
expect class PlatformActivity

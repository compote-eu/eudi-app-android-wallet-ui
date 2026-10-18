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

import android.content.Context
import android.content.Intent
import org.mockito.kotlin.mock

/**
 * `PlatformContext` is `android.content.Context` here, which is abstract — hence a mock. That is
 * faithful rather than a compromise for the tests using it: they only forward the handle.
 *
 * It is also why this factory has to exist instead of `commonTest` building one itself. Mockito is
 * JVM-only, so common code cannot reference it.
 */
internal actual fun testPlatformContext(): PlatformContext = mock<Context>()

/** `PlatformIntent` is `android.content.Intent` here, which is concrete and constructible. */
internal actual fun testPlatformIntent(): PlatformIntent = Intent()

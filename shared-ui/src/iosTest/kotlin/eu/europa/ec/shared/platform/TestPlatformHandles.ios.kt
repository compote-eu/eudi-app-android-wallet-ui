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
 * iOS has neither a Context nor an Intent, so both handles stay uninhabited in production. These are
 * tokens minted purely so the shared view-model tests run on this target too.
 *
 * `PlatformContext` is abstract, so it needs a subclass — its constructor is `protected` for exactly
 * this. `PlatformIntent` is a memberless class whose constructor is simply no longer private.
 */
private class TokenPlatformContext : PlatformContext()

internal actual fun testPlatformContext(): PlatformContext = TokenPlatformContext()

internal actual fun testPlatformIntent(): PlatformIntent = PlatformIntent()

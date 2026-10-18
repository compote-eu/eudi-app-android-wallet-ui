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


package eu.europa.ec.ux.elements

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The element semantics tests on the Android host (Robolectric hosts the Compose UI test environment). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ElementsSemanticsAndroidTest : ElementsSemanticsScenario()

/** The template semantics tests and D7-semantics-preliminary on the Android host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplatesSemanticsAndroidTest : TemplatesSemanticsScenario()

/** M5c: the registry schemas against what the adapters read, on the Android host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegistryAdapterAndroidTest : RegistryAdapterScenario()

/** M6c: part assets by name, tinted masks, on the Android host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetAndroidTest : AssetScenario()

/** M5e: the Home parts on the Android host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomePartsAndroidTest : HomePartsScenario()

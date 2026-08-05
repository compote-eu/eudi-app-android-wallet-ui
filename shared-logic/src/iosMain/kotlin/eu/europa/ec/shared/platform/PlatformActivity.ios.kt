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
 * iOS has no Activity, and nothing on this platform produces one of these yet — the private
 * constructor says so rather than leaving a type that looks constructible.
 *
 * When iOS grows the features that need a host handle (proximity NFC engagement, biometric prompts),
 * this becomes whatever they actually need — most likely a `UIViewController` typealias. Leaving it
 * uninhabited until then means the compiler flags every iOS path that would need one, instead of a
 * placeholder silently succeeding.
 */
actual class PlatformActivity private constructor()

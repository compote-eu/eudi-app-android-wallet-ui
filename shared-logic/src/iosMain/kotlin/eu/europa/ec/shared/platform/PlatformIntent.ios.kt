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
 * A broadcast as iOS carries it in process: the action, and the extras its receivers read.
 *
 * iOS has no intents and no system broadcast bus, so this is not an intent — it is what [IosBroadcasts]
 * hands to `SystemBroadcastReceiver`, where shared screens listen for the same actions Android
 * broadcasts. It carries only what the accessors in `PlatformIntentAccessors` read, and nothing else.
 *
 * The no-argument form carries nothing; it is what a test mints when it only needs an instance to pass
 * through, the role Android's tests give a `mock<Context>()`. Common code still cannot construct one,
 * because the `expect` declaration exposes no constructor — that is what keeps common code off Android
 * intents, not this.
 */
actual class PlatformIntent(
    internal val action: String? = null,
    internal val stringExtras: Map<String, String> = emptyMap(),
    internal val stringListExtras: Map<String, List<String>> = emptyMap(),
)

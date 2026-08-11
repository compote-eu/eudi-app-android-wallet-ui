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
 * The little that shared code needs to read off a broadcast [PlatformIntent].
 *
 * [PlatformIntent] is deliberately an opaque `expect class` with no members, which is what stops
 * platform types leaking upwards — but it also means a shared screen handed one by
 * `SystemBroadcastReceiver` cannot read anything from it. These three accessors are that vocabulary,
 * kept as narrow as the app actually uses: which action fired, and one string or string-list extra.
 *
 * On iOS they are unreachable rather than unimplemented: `PlatformIntent` has no constructor there and
 * the broadcast receiver is a no-op, so no value can ever reach them.
 */
expect fun PlatformIntent.platformAction(): String?

/** A single string extra, or null when absent. */
expect fun PlatformIntent.platformStringExtra(key: String): String?

/** A string-list extra, or empty when absent. */
expect fun PlatformIntent.platformStringListExtra(key: String): List<String>

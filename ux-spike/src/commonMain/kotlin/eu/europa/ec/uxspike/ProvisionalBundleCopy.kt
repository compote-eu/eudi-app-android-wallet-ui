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

// PROVISIONAL (M5b): replaced at M6 by a CopyProvider over the generated Res.allStringResources (boundary §1.8).
package eu.europa.ec.uxspike

import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.screen.CopyProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PROVISIONAL copy: until `ux-spec/resources/values/strings.xml` exists, the text comes from the `_sk` annotations of
 * the PROVISIONAL bundle's copy table, keyed by resource name, with `{name}` placeholders turned into positional
 * `%n$s` in `placeholders` order (what the exporter does, boundary §1.8). The runtime itself never reads `_` keys.
 */
object ProvisionalBundleCopy {
    fun from(bundle: FlowBundle): CopyProvider {
        val copy = bundle.raw["copy"] as? JsonObject
        val keys = (copy?.get("keys") as? JsonObject).orEmpty().values
        val overrides = (copy?.get("brand_overrides") as? JsonObject).orEmpty().values.flatMap { (it as? JsonObject).orEmpty().values }
        val strings = (keys + overrides).filterIsInstance<JsonObject>().mapNotNull { k ->
            val res = k.string("res") ?: return@mapNotNull null
            val sk = k.string("_sk") ?: return@mapNotNull null
            val placeholders = (k["placeholders"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            res to placeholders.foldIndexed(sk) { i, text, name -> text.replace("{$name}", "%${i + 1}\$s") }
        }.toMap()
        return CopyProvider.of(strings)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

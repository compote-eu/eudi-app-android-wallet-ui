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

package eu.europa.ec.ux.runtime.screen

import eu.europa.ec.ux.runtime.bundle.FlowBundle
import eu.europa.ec.ux.runtime.engine.FlowLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The text behind a copy resource name (boundary §1.8: `Res.allStringResources[res]`, positional `%1$s` placeholders).
 * The host supplies it; until `ux-spec/resources` exists the spike's hook passes a PROVISIONAL one.
 */
fun interface CopyProvider {
    fun string(res: String): String?

    companion object {
        val NONE: CopyProvider = CopyProvider { null }

        fun of(strings: Map<String, String>): CopyProvider = CopyProvider { strings[it] }
    }
}

/**
 * Resolves copy ids through the bundle's `copy` table: the brand override (`brand_overrides.<brand>`) first, then the
 * base key; placeholders are filled in `placeholders` order. A missing key or resource draws `⟦<key>⟧` and is logged
 * once, so a gap is visible on screen and in the log.
 */
class CopyResolver(bundle: FlowBundle, brand: String?, private val provider: CopyProvider, private val log: FlowLog) {
    private val copy = bundle.raw["copy"] as? JsonObject
    private val keys = (copy?.get("keys") as? JsonObject).orEmpty()
    private val overrides = ((copy?.get("brand_overrides") as? JsonObject)?.get(brand.orEmpty()) as? JsonObject).orEmpty()
    private val reported = mutableSetOf<String>()

    /** [args] maps placeholder names to their values. */
    fun text(key: String, args: Map<String, String?> = emptyMap()): String {
        val base = keys[key] as? JsonObject
        val override = (overrides[key] as? JsonObject)?.get("res").str()
        val res = base?.get("res").str()
        val template = override?.let(provider::string) ?: res?.let(provider::string)
        if (template == null) {
            if (reported.add(key)) log.log("copy missing: $key (res ${override ?: res})")
            return "⟦$key⟧"
        }
        val placeholders = (base?.get("placeholders") as? JsonArray).orEmpty().mapNotNull { it.str() }
        return format(template, placeholders.map { args[it].orEmpty() })
    }

    private companion object {
        val POSITIONAL = Regex("%(\\d+)\\\$s")

        fun format(template: String, values: List<String>): String =
            POSITIONAL.replace(template) { m -> values.getOrNull(m.groupValues[1].toInt() - 1) ?: m.value }
    }
}

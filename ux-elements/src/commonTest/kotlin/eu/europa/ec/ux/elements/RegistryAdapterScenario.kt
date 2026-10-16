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

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.europa.ec.ux.elements.registry.UxElementRegistry
import eu.europa.ec.ux.elements.registry.UxNodeScope
import eu.europa.ec.ux.elements.registry.UxRecord
import eu.europa.ec.ux.elements.registry.UxTemplateScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M5c: the schemas of [UxElementRegistry] cannot drift from the adapters. Each adapter runs once against a recording
 * scope that answers every read with "absent"; every prop, slot, record field and region it asked for must be in its
 * schema (so the interpreter's unknown-prop check never rejects what an adapter reads). Both targets.
 */
@OptIn(ExperimentalTestApi::class)
abstract class RegistryAdapterScenario {

    @Test
    fun everyAdapterReadsOnlyWhatItsSchemaDeclares() = runComposeUiTest {
        val reads = mutableMapOf<String, Triple<Set<String>, Set<String>, Set<String>>>()
        setUx {
            UxElementRegistry.elements.forEach { (id, adapter) ->
                val scope = RecordingNode(id)
                adapter(scope)
                reads[id] = Triple(scope.props, scope.slots, scope.fields)
            }
        }
        waitForIdle()
        assertEquals(UxElementRegistry.elements.keys, reads.keys)
        reads.forEach { (id, read) ->
            val schema = UxElementRegistry.elementSchemas.getValue(id)
            assertTrue(read.first.isNotEmpty(), "$id read no prop at all")
            assertTrue(schema.props.keys.containsAll(read.first), "$id reads undeclared props ${read.first - schema.props.keys}")
            assertTrue(schema.slots.containsAll(read.second), "$id reads undeclared slots ${read.second - schema.slots}")
            assertTrue(schema.itemProps.keys.containsAll(read.third), "$id reads undeclared item fields ${read.third - schema.itemProps.keys}")
        }
    }

    @Test
    fun everyTemplateAdapterReadsOnlyDeclaredRegions() = runComposeUiTest {
        val reads = mutableMapOf<String, Set<String>>()
        setUx {
            UxElementRegistry.templates.forEach { (id, adapter) ->
                val scope = RecordingTemplate(id)
                adapter(scope)
                reads[id] = scope.regions
            }
        }
        waitForIdle()
        reads.forEach { (id, regions) ->
            val declared = UxElementRegistry.templateSchemas.getValue(id).regions
            assertTrue(declared.containsAll(regions), "$id reads undeclared regions ${regions - declared}")
            assertEquals(declared, regions, "$id never asks for some declared region")
        }
    }
}

private class RecordingNode(override val id: String) : UxNodeScope {
    val props = mutableSetOf<String>()
    val slots = mutableSetOf<String>()
    val fields = mutableSetOf<String>()
    override val enabled: Boolean = true
    override val focusRequester: FocusRequester? = null

    override fun text(prop: String): String? = null.also { props += prop }

    override fun flag(prop: String): Boolean? = null.also { props += prop }

    override fun records(prop: String): List<UxRecord> = listOf(RecordingRecord(fields)).also { props += prop }

    override fun copy(key: String): String = key

    override fun hasSlot(slot: String): Boolean = true.also { slots += slot }

    @Composable
    override fun Slot(slot: String) {
        slots += slot
    }

    override fun emit(event: String, payload: Map<String, String>) = Unit
}

private class RecordingRecord(private val fields: MutableSet<String>) : UxRecord {
    override val keys: Set<String> = emptySet()

    override fun text(field: String): String? = null.also { fields += field }

    override fun flag(field: String): Boolean? = null.also { fields += field }
}

private class RecordingTemplate(override val id: String) : UxTemplateScope {
    val regions = mutableSetOf<String>()
    override val dialogLabel: String? = null

    override fun hasRegion(region: String): Boolean = true.also { regions += region }

    @Composable
    override fun Region(region: String) {
        regions += region
    }
}

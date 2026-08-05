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

package eu.europa.ec.shared.resources

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/**
 * Serializes a compose-resources resource handle as its `key`.
 *
 * This is what lets a Nav3 route payload carry *resource keys* instead of resolved text, and
 * therefore what lets [UiText] appear inside a `@Serializable` config. Without it the choice was
 * between a view-model that injects a resolver purely to fill a config it is about to navigate
 * with, or a config that cannot be serialized at all.
 *
 * A handle cannot be serialized structurally: [StringResource] carries an id, a key and a set of
 * qualified resource items, and its constructor is `@InternalResourceApi`. The key is the stable
 * identity anyway — it is the name declared in `strings.xml`, and the generated
 * [Res.allStringResources] / [Res.allPluralStringResources] maps are exactly the reverse index,
 * built once behind `by lazy`. Handle equality is by id, and ids and keys are 1:1 within a
 * corpus, so a round-trip returns a value equal to the original.
 *
 * Decoding an unknown key throws rather than falling back: every key reachable from a config is
 * written as `Res.string.<name>` at the construction site, so it exists by construction, and a
 * saved back stack never outlives the corpus it was written against (an app update takes the
 * process, and with it the saved state, down). A throw with the key in the message therefore
 * reports a real defect instead of hiding it behind placeholder text.
 */
object StringResourceSerializer : KSerializer<StringResource> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("eu.europa.ec.shared.resources.StringResource", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: StringResource) {
        encoder.encodeString(value.key)
    }

    override fun deserialize(decoder: Decoder): StringResource {
        val key = decoder.decodeString()
        return Res.allStringResources[key] ?: throw SerializationException(unknownKey("string", key))
    }
}

/** [StringResourceSerializer] for quantity strings. See that class for the rationale. */
object PluralStringResourceSerializer : KSerializer<PluralStringResource> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "eu.europa.ec.shared.resources.PluralStringResource",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: PluralStringResource) {
        encoder.encodeString(value.key)
    }

    override fun deserialize(decoder: Decoder): PluralStringResource {
        val key = decoder.decodeString()
        return Res.allPluralStringResources[key]
            ?: throw SerializationException(unknownKey("plural", key))
    }
}

private fun unknownKey(kind: String, key: String): String =
    "No $kind resource named '$key' in the shared corpus. It was serialized from a build whose " +
            "strings.xml declared it; either the key was renamed or removed without updating the " +
            "call site, or the payload came from a different corpus."

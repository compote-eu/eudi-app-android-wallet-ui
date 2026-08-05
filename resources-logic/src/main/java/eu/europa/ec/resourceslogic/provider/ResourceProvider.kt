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

package eu.europa.ec.resourceslogic.provider

import android.content.ContentResolver
import android.content.Context
import androidx.annotation.RawRes
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.StringCatalog
import eu.europa.ec.shared.resources.generic_error_description
import eu.europa.ec.shared.resources.generic_network_error_message
import org.jetbrains.compose.resources.StringResource
import java.util.Locale

/**
 * Android-side resource access for the interactor/controller layer.
 *
 * Phase 3a moved the string corpus to compose-resources, so the string methods now take a
 * [StringResource] and delegate to the shared [StringCatalog] — there is one corpus, resolvable
 * from `commonMain`. The `@StringRes Int` overloads are gone rather than kept alongside, so any
 * unconverted call site is a compile error instead of a lookup against a corpus that no longer
 * exists.
 *
 * What is left here is what genuinely needs Android: a [Context], a [ContentResolver], raw
 * resources, and the platform [Locale]. View-models no longer use this type at all — their state
 * carries `UiText` and the composable resolves it.
 *
 * Plurals are deliberately absent; see [StringCatalog] for why they resolve through the suspend
 * path instead.
 */
interface ResourceProvider {

    fun provideContext(): Context
    fun provideContentResolver(): ContentResolver
    fun getString(resource: StringResource): String
    fun getString(resource: StringResource, vararg formatArgs: Any): String
    fun getStringFromRaw(@RawRes resId: Int): String
    fun genericErrorMessage(): String
    fun genericNetworkErrorMessage(): String
    fun getLocale(): Locale
}

class ResourceProviderImpl(
    private val context: Context,
    private val stringCatalog: StringCatalog,
) : ResourceProvider {

    override fun provideContext() = context

    override fun provideContentResolver(): ContentResolver = context.contentResolver

    override fun genericErrorMessage() = stringCatalog[Res.string.generic_error_description]

    override fun genericNetworkErrorMessage() =
        stringCatalog[Res.string.generic_network_error_message]

    override fun getString(resource: StringResource): String = stringCatalog[resource]

    override fun getString(resource: StringResource, vararg formatArgs: Any): String =
        stringCatalog.get(resource, *formatArgs)

    override fun getStringFromRaw(@RawRes resId: Int): String =
        try {
            context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }

    override fun getLocale(): Locale = Locale.getDefault()
}

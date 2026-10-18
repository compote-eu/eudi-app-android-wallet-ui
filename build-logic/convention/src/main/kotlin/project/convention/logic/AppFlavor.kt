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

package project.convention.logic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ApplicationProductFlavor
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.ProductFlavor
import org.gradle.api.Project
import org.gradle.kotlin.dsl.invoke

// Lowercase on purpose: the entry name is the Gradle flavour *dimension* string, and renaming it
// would rename the dimension. "EnumEntryName" is the IDE's inspection, "EnumNaming" is detekt's.
@Suppress("EnumEntryName", "EnumNaming")
enum class FlavorDimension {
    contentType
}

/**
 * The product flavours. Their per-variant *values* — the id suffix and the name suffix — deliberately
 * do not live here: they are in `app.properties`, read below, because iOS needs the same two strings
 * and a Kotlin enum is not readable from an Xcode build. The enum name lowercased is the key suffix,
 * and it is also iOS's `BUILD_VARIANT`.
 */
enum class AppFlavor(
    val dimension: FlavorDimension
) {
    Dev(FlavorDimension.contentType),
    Demo(FlavorDimension.contentType)
}

/** The identity file both platforms read; see its header for what belongs in it and what does not. */
private const val APP_PROPERTIES = "app.properties"

/**
 * Reads [key] from [fileName], failing the build when it is absent. [getProperty] answers null both
 * for a missing key and for a missing file, and a null here would surface as an app named "null" or an
 * id with a "null" suffix — so an absent key has to stop the build rather than reach a device.
 *
 * An *empty* value is legitimate and returned as-is: the name suffixes are empty on purpose.
 */
private fun Project.requiredProperty(key: String, fileName: String): String =
    requireNotNull(getProperty<String>(key, fileName)) {
        "$key is missing from $fileName, which both platforms read for the app's identity"
    }

fun Project.configureFlavors(
    commonExtension: CommonExtension,
    flavorConfigurationBlock: ProductFlavor.(flavor: AppFlavor) -> Unit = {}
) {

    val version = getProperty<String>(
        "VERSION_NAME",
        "version.properties"
    ).orEmpty()

    val appName = requiredProperty("APP_NAME", APP_PROPERTIES)

    commonExtension.apply {
        flavorDimensions += FlavorDimension.contentType.name
        productFlavors {
            AppFlavor.entries.forEach {
                create(it.name.lowercase()) {
                    val variant = it.name.lowercase()
                    dimension = it.dimension.name
                    val idSuffix = requiredProperty("APP_ID_SUFFIX_$variant", APP_PROPERTIES)
                    if (this@apply is ApplicationExtension && this is ApplicationProductFlavor) {
                        versionName = version
                        // Left unset when empty rather than set to "": AGP appends the suffix verbatim,
                        // and this keeps the demo flavour's id byte-identical to having no suffix at all.
                        if (idSuffix.isNotEmpty()) {
                            applicationIdSuffix = idSuffix
                        }
                    }
                    // Both halves of `android:label="${appName}${appNameSuffix}"` in assembly-logic's
                    // manifest. Set on the flavour rather than defaultConfig so the suffix can differ
                    // per variant, and set here rather than in assembly-logic so the name has one home.
                    manifestPlaceholders["appName"] = appName
                    manifestPlaceholders["appNameSuffix"] =
                        requiredProperty("APP_NAME_SUFFIX_$variant", APP_PROPERTIES)
                    addConfigField(
                        "APP_VERSION",
                        version
                    )
                    flavorConfigurationBlock(this, it)
                }
            }
        }
    }
}

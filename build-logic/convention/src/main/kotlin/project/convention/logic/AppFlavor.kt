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

@Suppress("EnumEntryName")
enum class FlavorDimension {
    contentType
}

enum class AppFlavor(
    val dimension: FlavorDimension,
    val applicationIdSuffix: String? = null,
    val applicationNameSuffix: String? = null
) {
    Dev(FlavorDimension.contentType, applicationIdSuffix = ".dev"),
    Demo(FlavorDimension.contentType),
    Local(FlavorDimension.contentType, applicationIdSuffix = ".local"),

    // Slovak (SK) variant. Starts as a suffix flavor (installs alongside dev/demo/local)
    // and currently targets the local backend for development, so it reuses LOCAL_IP and
    // the local dev-CA trust. It will get its OWN full applicationId in a later phase.
    Sk(
        FlavorDimension.contentType,
        applicationIdSuffix = ".sk",
        applicationNameSuffix = " SK"
    )
}

fun Project.configureFlavors(
    commonExtension: CommonExtension,
    flavorConfigurationBlock: ProductFlavor.(flavor: AppFlavor) -> Unit = {}
) {

    val version = getProperty<String>(
        "VERSION_NAME",
        "version.properties"
    ).orEmpty()

    // Host used by the `local` flavor to reach services running on the developer's
    // machine. Configurable per developer/LAN via `localIp` in local.properties, or
    // the LOCAL_IP environment variable. Falls back to the Android emulator's host
    // alias (10.0.2.2) when unset. Consumed as BuildConfig.LOCAL_IP by the `local`
    // source sets (e.g. core-logic/src/local/.../WalletCoreConfigImpl.kt).
    val localIp = getProperty<String>("localIp")
        ?: System.getenv("LOCAL_IP")
        ?: "10.0.2.2"

    commonExtension.apply {
        flavorDimensions += FlavorDimension.contentType.name
        productFlavors {
            AppFlavor.entries.forEach {
                create(it.name.lowercase()) {
                    dimension = it.dimension.name
                    if (this@apply is ApplicationExtension && this is ApplicationProductFlavor) {
                        versionName = version
                        if (it.applicationIdSuffix != null) {
                            applicationIdSuffix = it.applicationIdSuffix
                        }
                    }
                    manifestPlaceholders["appNameSuffix"] = it.applicationNameSuffix.orEmpty()
                    addConfigField(
                        "APP_VERSION",
                        version
                    )
                    // The `local` flavor and the `sk` flavor (which currently targets the
                    // local backend) need the local host address.
                    if (it == AppFlavor.Local || it == AppFlavor.Sk) {
                        addConfigField("LOCAL_IP", localIp)
                    }
                    flavorConfigurationBlock(this, it)
                }
            }
        }
    }
}

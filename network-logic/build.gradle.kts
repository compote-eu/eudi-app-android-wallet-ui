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

import com.android.build.api.dsl.LibraryExtension
import project.convention.logic.config.LibraryModule
import project.convention.logic.kover.KoverExclusionRules
import project.convention.logic.kover.excludeFromKoverReport

plugins {
    id("project.android.library")
    id("project.ktor")
}

extensions.configure<LibraryExtension>("android") {
    namespace = "eu.europa.ec.networklogic"
}

moduleConfig {
    module = LibraryModule.NetworkLogic
}

dependencies {
    implementation(project(LibraryModule.BusinessLogic.path))
    testImplementation(project(LibraryModule.TestLogic.path))
}

excludeFromKoverReport(
    excludedClasses = KoverExclusionRules.NetworkLogic.classes,
    excludedPackages = KoverExclusionRules.NetworkLogic.packages,
)

// ---------------------------------------------------------------------------------------
// Local development CA / TLS helper tasks (used by the `local` build flavor).
// These are developer conveniences and are never part of the app build itself.
// ---------------------------------------------------------------------------------------
val localDevCaScript = "$rootDir/scripts/generate-local-dev-ca.sh"

tasks.register<Exec>("generateLocalDevCa") {
    group = "local dev"
    description = "Generates a local development CA and bundles its certificate at " +
        "network-logic/src/local/res/raw/local_dev_ca.pem for the `local` flavor. Pass -PforceCa to regenerate " +
        "(destructive: invalidates any copy already installed on a device)."
    workingDir = rootDir
    commandLine("bash", localDevCaScript, "ca")
    // Uses a dedicated -PforceCa flag (NOT -Pforce) so regenerating a server cert via
    // generateLocalServerCert -Pforce never destroys/rotates the CA as a side effect.
    val forceCaProp = providers.gradleProperty("forceCa")
    argumentProviders.add(CommandLineArgumentProvider {
        if (forceCaProp.isPresent) listOf("--force") else emptyList()
    })
}

tasks.register<Exec>("generateLocalServerCert") {
    group = "local dev"
    description = "Generates a TLS server certificate signed by the local dev CA for your local " +
        "services. Host resolved from -PhostIp, LOCAL_IP env, or localIp in local.properties. Pass -Pforce to regenerate."
    dependsOn("generateLocalDevCa")
    workingDir = rootDir
    commandLine("bash", localDevCaScript, "server")
    val hostIpProp = providers.gradleProperty("hostIp")
    val forceProp = providers.gradleProperty("force")
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            hostIpProp.orNull?.let { add(it) }
            if (forceProp.isPresent) add("--force")
        }
    })
}

tasks.register<Exec>("generateWalletProviderSigningKey") {
    group = "local dev"
    description = "Generates the wallet-provider ES256 signing keystore at " +
        "local-services/config/wallet-provider/signing.p12 (+ public cert). Password via -PkeystorePassword (default 'changeit')."
    workingDir = rootDir
    commandLine(
        "bash", "$rootDir/scripts/generate-signing-keystore.sh",
        "local-services/config/wallet-provider/signing.p12",
        "wallet-provider"
    )
    val pwProp = providers.gradleProperty("keystorePassword").orElse("changeit")
    argumentProviders.add(CommandLineArgumentProvider { listOf(pwProp.get()) })
}
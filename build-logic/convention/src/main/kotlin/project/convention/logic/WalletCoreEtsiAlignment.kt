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

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Before each variant builds, checks that every ETSI trust-library module wallet-core depends on
 * resolves to the version wallet-core asks for — the version it was compiled against.
 *
 * Needed because this module asks for the ETSI library too (see `EudiWalletCorePlugin`), and Gradle
 * settles two requests for one module on the higher version without a word. wallet-core calls into
 * that library, so a different version is a binary mismatch that surfaces only when a call fails at
 * run time: a `NoSuchMethodError` on a screen rather than a failed build.
 *
 * The expected version is read from the resolved graph — what wallet-core's own metadata requests —
 * so a wallet-core upgrade needs no edit here.
 */
internal fun Project.verifyWalletCoreEtsiAlignment() {
    pluginManager.withPlugin("com.android.base") {
        extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
            val verify = tasks.register(
                "${variant.name}VerifyWalletCoreEtsiVersions",
                VerifyWalletCoreEtsiVersionsTask::class.java,
            ) {
                rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                variantName.set(variant.name)
            }
            val preBuild = "pre${variant.name.replaceFirstChar { it.uppercase() }}Build"
            tasks.matching { it.name == preBuild }.configureEach { dependsOn(verify) }
        }
    }
}

internal abstract class VerifyWalletCoreEtsiVersionsTask : DefaultTask() {

    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun verify() {
        val mismatches = walletCoreEtsiMismatches(rootComponent.get())
        if (mismatches.isNotEmpty()) {
            throw GradleException(
                "The ${variantName.get()} runtime classpath does not give wallet-core the ETSI library it " +
                    "was built against:\n" + mismatches.joinToString("\n") { "  - $it" } +
                    "\nKeep the catalogue's Android ETSI version equal to what wallet-core requests."
            )
        }
    }
}

/** Every ETSI module wallet-core requests at one version and the graph resolved to another. */
internal fun walletCoreEtsiMismatches(root: ResolvedComponentResult): List<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val pending = ArrayDeque(listOf(root))
    val mismatches = mutableListOf<String>()
    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        if (!seen.add(component)) continue
        val walletCore = component.moduleVersion?.takeIf { it.group == EUDI_GROUP && it.name == WALLET_CORE }
        for (dependency in component.dependencies.filterIsInstance<ResolvedDependencyResult>()) {
            pending += dependency.selected
            val requested = dependency.requested as? ModuleComponentSelector ?: continue
            if (walletCore == null || requested.group != EUDI_GROUP || !requested.module.startsWith("etsi-")) continue
            val selected = dependency.selected.moduleVersion?.version
            if (selected != requested.version) {
                mismatches += "${requested.module}: wallet-core ${walletCore.version} requests " +
                    "${requested.version}, the graph selected $selected"
            }
        }
    }
    return mismatches
}

private const val EUDI_GROUP = "eu.europa.ec.eudi"
private const val WALLET_CORE = "eudi-lib-android-wallet-core"

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

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import project.convention.logic.libs

/**
 * The repository's only automated code-quality gate: `./gradlew detekt ktlintCheck`.
 *
 * Applied to the root project once, rather than per module, so that adding a module cannot silently
 * opt out of it.
 *
 * The two tools have separate jobs and share no rule. detekt owns static analysis -- above all the
 * dead-code rules, which is the drift this gate exists to stop. ktlint owns imports. Enabling the
 * same rule in both would report every finding twice and leave neither tool the owner, so
 * `detekt-formatting` (which is ktlint's engine wrapped for detekt) is deliberately not on the
 * classpath. Which rules are on, which are off and what each exemption cost is recorded in
 * `detekt.yml` and `.editorconfig`.
 */
class CodeQualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) {
            "project.code.quality configures the whole build and belongs on the root project only, " +
                "but was applied to ${target.path}."
        }
        target.configureDetekt()
        target.configureKtlint()
    }
}

/**
 * One root task over the whole tree, not one task per source set.
 *
 * detekt lives on the root project, which has no Kotlin sources of its own, so its own default
 * source is empty -- leave `setSource` off and the task reports `no source files` and analyses
 * nothing at all, silently. Pointing it at `rootDir` instead is what gives it the whole tree, and
 * it cannot miss a source set, present or future: `commonMain`, `iosMain` and every test source set
 * are covered by construction rather than by enumeration. Each of those was confirmed by planting a
 * violation and watching this task fail on it.
 */
private fun Project.configureDetekt() {
    // The root project has no lifecycle tasks of its own, so `check` does not exist here and
    // detekt has nothing to attach itself to -- `./gradlew check` would run every module's
    // ktlintCheck and silently skip detekt entirely. `base` supplies the `check` task that
    // detekt's plugin then wires itself into.
    pluginManager.apply("base")
    pluginManager.apply("io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        ignoreFailures = false
        config.setFrom(rootProject.file("detekt.yml"))
        basePath = rootProject.projectDir.absolutePath
    }

    tasks.withType<Detekt>().configureEach {
        // Type resolution is deliberately not configured. It would need a per-target classpath,
        // which is the per-source-set setup this task exists to avoid, and none of the rules left
        // active in detekt.yml need it.
        jvmTarget = "17"
        autoCorrect = false
        setSource(files(rootProject.projectDir))
        include("**/*.kt", "**/*.kts")
        exclude(
            "**/build/**",
            "**/.git/**",
            "**/.gradle/**",
            "**/.kotlin/**",
            // Clones of upstream's backend services for local development, kept out of git by
            // .git/info/exclude rather than .gitignore -- so they are present in a working copy
            // that has run the local stack, and absent everywhere else. Not our code either way.
            "**/local-services/**",
        )
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
            md.required.set(false)
        }
    }
}

/**
 * ktlint has to be applied per project: its tasks come from each project's Kotlin source sets, and
 * on the root project alone it would only ever see the build scripts.
 *
 * This leaves `build-logic` uncovered -- it is a separate included build, so `allprojects` does not
 * reach it. detekt does cover it, because `build-logic/` sits under `rootDir`.
 */
private fun Project.configureKtlint() {
    val ktlintEngineVersion = libs.findVersion("ktlintCli").get().requiredVersion

    allprojects {
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        extensions.configure<KtlintExtension> {
            // The engine version, pinned in the catalog independently of the plugin wrapping it:
            // a plugin bump must not silently change which rules run.
            version.set(ktlintEngineVersion)
            ignoreFailures.set(false)
            // Left false on purpose. `android_studio` is a *code style*, and switching this tree to
            // one is a 9,079-finding reformat; the rule-by-rule decisions live in .editorconfig
            // instead, where each one carries what it cost.
            android.set(false)
            reporters {
                reporter(ReporterType.PLAIN)
                reporter(ReporterType.CHECKSTYLE)
            }
            filter {
                // invariantSeparatorsPath so the patterns hold on Windows too.
                exclude { it.file.invariantSeparatorsPath.contains("/build/") }
                exclude { it.file.invariantSeparatorsPath.contains("/local-services/") }
            }
        }
    }
}

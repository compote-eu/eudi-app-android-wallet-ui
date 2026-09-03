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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable

// KMP business/presentation LOGIC shared by both platforms — deliberately Compose-UI-free
// (no compose-ui/foundation/material on the classpath) so a future partial native-SwiftUI iOS
// path can consume it directly. The Compose Multiplatform UI lives in the sibling :shared-ui.
// See wiki/KMP_FEASIBILITY.md.
plugins {
    // These two MUST stay `id(...)` without a version, and cannot become `alias(...)`: they ship
    // inside kotlin-gradle-plugin / AGP, which `build-logic` puts on the buildscript classpath, and
    // `alias()` always carries the catalog's version. Requesting a version for a plugin already on
    // the classpath fails: "the plugin is already on the classpath with an unknown version, so
    // compatibility cannot be checked". Everything else here uses `alias()`.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // @Serializable Nav3 route keys (kotlinx.serialization core is KMP; no Compose involved).
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Which of Android's two product flavours this iOS build mirrors, from `-PappFlavor=dev|demo`.
 *
 * Defaults to `dev`, matching `assembleDevDebug` — the Android variant the verify set builds and the
 * one every documented probe run has used. An unknown value fails the build rather than silently
 * falling back: a typo that quietly produced a dev build would be the worst outcome here, since the
 * two flavours differ in which issuers and wallet provider the app talks to.
 */
private val appFlavor: IosAppFlavor = IosAppFlavor.from(providers.gradleProperty("appFlavor").orNull)

private enum class IosAppFlavor(val directorySuffix: String) {
    Dev("Dev"),
    Demo("Demo");

    companion object {
        fun from(value: String?): IosAppFlavor {
            if (value == null) return Dev
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error(
                    "Unknown -PappFlavor='$value'. Expected one of " +
                            entries.joinToString { it.name.lowercase() } + "."
                )
        }
    }
}

kotlin {
    // AGP 9's KMP-aware Android target. NB: `android {}`, NOT `androidLibrary {}` — the latter is
    // deprecated as of AGP 9.3.x ("Please use 'android' instead").
    android {
        namespace = "eu.europa.ec.shared"
        compileSdk = 37
        minSdk = 29
        withHostTest {}
        // Pin to JVM 17 like every other module so the app (built at 17) can inline this module's
        // inline funs (safeLet/safeAsync).
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets: device (arm64) + Apple-Silicon simulator. No framework binary here — this is a
    // library (klib) consumed by :shared-ui, whose SharedKit framework re-exports it. iosX64
    // (Intel-Mac simulator) is intentionally omitted (Apple ships only Apple Silicon).
    iosArm64()
    iosSimulatorArm64()

    // The *test* binaries link two things the app gets from Xcode instead.
    //
    // 1. **SQLite.** The app never needed this — Xcode links libsqlite3 when it builds the framework
    //    into the app — but a test that so much as mentions `IosWalletEngine` pulls in androidx.sqlite's
    //    cinterop (the real, non-backed-up storage behind `MultipazWalletStore`) and fails to link with a
    //    wall of undefined `_sqlite3_*` symbols. Tests over `EphemeralStorage` never touched it, which is
    //    why this only appeared with the first test to name the real store.
    //
    // 2. **PKIXBridge**, the same shape one layer up. The ETSI consultation library reaches iOS
    //    certificate path validation through cinterop: its published klib *records* the Swift symbols
    //    (`PKIXValidator`, `PKIXConfiguration`, `PKIXCertificateInspector`) and carries no
    //    implementation, expecting the consuming Xcode target to supply them. The app target does, via
    //    the vendored SPM package in `iosApp/PKIXBridge`. A Kotlin/Native test binary has no Xcode target
    //    to borrow from, so it compiles the same vendored sources itself — see [buildPkixBridge].
    //
    //    🪤 This is why iOS trust was recorded as blocked for weeks. The undefined-symbol failure
    //    (`_OBJC_CLASS_$__TtC10PKIXBridge13PKIXValidator`) reads like a packaging problem in the library
    //    and was written up as one; it is the `-lsqlite3` problem again, and this repository had already
    //    solved that.
    //
    //    🪤 And it is invisible until the trust code is *reachable* from a test: Kotlin/Native drops
    //    unreferenced code, so merely adding the dependency and the classes links fine. The first test
    //    that exercises a trust decision is what surfaces it.
    targets.withType(KotlinNativeTarget::class.java).configureEach {
        val pkixBridge = registerPkixBridgeBuild(this)
        binaries.withType(TestExecutable::class.java).configureEach {
            linkerOpts("-lsqlite3")
            linkerOpts("-L${pkixBridgeDirectory(targetName).get().asFile.absolutePath}", "-lPKIXBridge")
            linkTaskProvider.configure { dependsOn(pkixBridge) }
        }
    }

    sourceSets {
        // The iOS half of Android's product flavours. Android varies a build by *source set* —
        // `core-logic/src/dev` and `core-logic/src/demo` each hold a `WalletCoreConfigImpl.kt` with
        // the same fully-qualified name, and AGP puts exactly one on the compile path. Kotlin/Native
        // has no product flavours, so the same effect is had by adding exactly one flavour directory
        // here, chosen by `-PappFlavor`. `IosWalletConfigImpl` therefore has one FQN and two bodies,
        // which is the property that makes this a mirror of Android rather than a lookalike.
        //
        // Only :shared-logic needs this. :shared-ui reads the same object through its dependency on
        // this module, so the flavour is decided in one place.
        iosMain {
            kotlin.srcDir("src/ios${appFlavor.directorySuffix}Main/kotlin")
        }
        // Tests get a flavour directory too, and for a reason that is not symmetry for its own sake:
        // a test that branched on `iosWalletConfig.appFlavor` to decide what to expect would pass
        // whichever flavour it was handed, so it could not catch the build defaulting to the wrong
        // one. Here each flavour's expected values live in a file that only exists on that flavour's
        // compile path, so `assertEquals(DEV, ...)` is a real assertion rather than a tautology.
        iosTest {
            kotlin.srcDir("src/ios${appFlavor.directorySuffix}Test/kotlin")
        }
        commonMain.dependencies {
            // `api` where the type appears in public signatures (safeAsync exposes Flow/Dispatcher;
            // MviViewModel extends androidx.lifecycle.ViewModel; AppRoute uses NavKey).
            api(libs.kotlinx.coroutines)
            api(libs.androidx.lifecycle.viewmodel)
            // `api`: LocalDateTime is part of the filter framework's public surface
            // (FilterElement.DateTimeRangeFilterItem / FilterValidator.updateDateFilter), so
            // consumers in other modules must see the type.
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        // iOS-only: the document layer reads multipaz's DocumentStore directly, because there is no
        // KMP build of eudi-lib-android-wallet-core or of the document manager (see the three-way
        // split in wiki/KMP_FEASIBILITY.md — multipaz IS fully KMP, the EUDI wrappers are not).
        // Deliberately NOT in commonMain: Android reaches the very same multipaz through wallet-core,
        // so putting it in commonMain would add a second version *request* to the app's classpath and
        // couple half the Android app to multipaz for no gain. `implementation`, since no multipaz
        // type appears in this module's public API — the seam is WalletDocument/WalletEngine.
        iosMain.dependencies {
            // The lock behind `NativeSecurePin`. Kotlin/Native has no `@Synchronized`, and a PIN's
            // mutual exclusion is not something to leave out; atomicfu's `ReentrantLock` is the
            // standard multiplatform answer and already arrives transitively with coroutines and
            // multipaz — declared here so the dependency is a decision rather than an accident.
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.multipaz)
            // Ktor's Darwin engine, for fetching status-list tokens. `ktor-client-core` already
            // arrives transitively with multipaz (which uses it itself), so this adds the iOS engine
            // and nothing else — checked with `:shared-logic:dependencies`.
            implementation(libs.ktor.client.darwin)
            // EXPERIMENT: ETSI trust-list consultation.
            implementation(libs.eudi.lib.kmp.etsi119602.consultation)
        }
        iosTest.dependencies {
            // The mock HTTP engine for `MultipazRevocationCheckerTest`; everything else it needs
            // (multipaz's StatusList, crypto, ephemeral storage) is already on the iOS classpath.
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            // `api`, not implementation: these types ARE the androidMain platform handles (actual
            // typealiases), so they appear in this module's public Android signatures —
            // ComponentActivity is `PlatformActivity` and BiometricPrompt.CryptoObject is
            // `PlatformCryptoObject`. Plain `activity`, never activity-compose: this module stays
            // Compose-UI-free.
            api(libs.androidx.activity)
            api(libs.androidx.biometric)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

/**
 * Where [registerPkixBridgeBuild] leaves `libPKIXBridge.a` for a native target.
 *
 * Separate from the task so `linkerOpts` can name the directory without realising it — a
 * `TaskProvider.get()` inside `configureEach` would force the task to be configured for every build,
 * including Android-only ones.
 */
fun pkixBridgeDirectory(targetName: String): Provider<Directory> =
    layout.buildDirectory.dir("pkix-bridge/$targetName")

/**
 * Compiles the vendored PKIXBridge Swift sources into a static library the Kotlin/Native **test**
 * linker can consume.
 *
 * The app does not use this: Xcode builds the same sources as an SPM package (see
 * `iosApp/project.yml`), and an app target can auto-link the framework the cinterop asks for. A test
 * binary has no Xcode target, so it needs the symbols as a plain archive instead.
 *
 * 📌 The **module name is load-bearing** and must stay `PKIXBridge`: the cinterop `.def` in the
 * published klib says `modules = PKIXBridge`, and the symbols it records are mangled accordingly
 * (`_OBJC_CLASS_$__TtC10PKIXBridge13PKIXValidator` — the `10` is the length of the module name).
 * Compiling the same files under any other module name produces an archive that satisfies nothing.
 *
 * 🪤 Keep the sources in step with `eudiLibKmpEtsi1196x2` in `libs.versions.toml`; they are a copy of
 * that tag's `ios/cinterop/Sources/PKIXBridge`. See `iosApp/PKIXBridge/VENDORED.md`.
 */
fun registerPkixBridgeBuild(target: KotlinNativeTarget): TaskProvider<Exec> {
    val targetName = target.targetName
    // Only the two targets this module declares. An unmapped one fails loudly rather than silently
    // building for the wrong platform, which would surface as a confusing link error much later.
    val (sdk, triple) = when (targetName) {
        "iosSimulatorArm64" -> "iphonesimulator" to "arm64-apple-ios17.0-simulator"
        "iosArm64" -> "iphoneos" to "arm64-apple-ios17.0"
        else -> error("No PKIXBridge platform mapping for '$targetName'; add one above.")
    }
    val sources = layout.projectDirectory.dir("../iosApp/PKIXBridge/Sources/PKIXBridge")
    val library = pkixBridgeDirectory(targetName).map { it.file("libPKIXBridge.a") }

    return tasks.register<Exec>(
        "buildPkixBridge" + targetName.replaceFirstChar { it.uppercase() }
    ) {
        description = "Compiles the vendored PKIXBridge Swift sources for $targetName."
        inputs.dir(sources).withPropertyName("swiftSources")
        outputs.file(library).withPropertyName("staticLibrary")
        outputs.cacheIf { true }
        executable = "xcrun"
        // Resolved at execution time so the file list is not baked into the configuration cache.
        argumentProviders.add(
            CommandLineArgumentProvider {
                val swiftFiles = sources.asFileTree
                    .matching { include("**/*.swift") }
                    .files
                    // Sorted so the archive is reproducible; `FileTree` order is not defined.
                    .sortedBy { it.absolutePath }
                    .map { it.absolutePath }
                check(swiftFiles.isNotEmpty()) {
                    "No Swift sources under $sources — is iosApp/PKIXBridge still vendored?"
                }
                listOf(
                    "-sdk", sdk, "swiftc",
                    "-emit-library", "-static",
                    "-module-name", "PKIXBridge",
                    "-target", triple,
                    "-o", library.get().asFile.absolutePath,
                ) + swiftFiles
            }
        )
        doFirst { library.get().asFile.parentFile.mkdirs() }
    }
}

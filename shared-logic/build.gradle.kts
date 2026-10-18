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
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

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

/**
 * Where [downloadPkixBridge] unpacks `PKIXBridge.xcframework`.
 *
 * Separate from the task so `linkerOpts` can name the directory without realising the task.
 */
private val pkixBridgeFramework: Provider<Directory> = layout.buildDirectory.dir("pkix-bridge")

/**
 * Downloads PKIXBridge, the Swift half of the ETSI consultation library's cinterop, for the
 * Kotlin/Native **test** binaries, which have to link it themselves (see the note in `kotlin {}`).
 *
 * The app does not use this: Xcode gets the same framework from the local package in
 * `iosApp/PKIXBridge`. This task reads that package's `Package.swift` for the URL and the SHA-256
 * rather than keeping its own copy, so a test binary links exactly the bytes the app does.
 *
 * 📌 It also refuses a URL whose release is not `eudiLibKmpEtsi1196x2Ios`. The klib and the framework
 * have to come from the same release, and a mismatch can link without complaint: alpha.1's Swift
 * under alpha.2's klib did, and silently kept the old revocation default. The Xcode build cannot check
 * this; every iOS test run does.
 */
private val downloadPkixBridge = tasks.register("downloadPkixBridge") {
    description = "Downloads the PKIXBridge xcframework that iosApp/PKIXBridge/Package.swift names."
    val manifestPath = "iosApp/PKIXBridge/Package.swift"
    val manifest = providers.fileContents(layout.projectDirectory.file("../$manifestPath")).asText
    fun field(name: String) = manifest.map { text ->
        Regex("""\b$name:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: error("No `$name:` in $manifestPath")
    }
    val url = field("url")
    val checksum = field("checksum")
    val klibVersion = libs.versions.eudiLibKmpEtsi1196x2Ios
    val output = pkixBridgeFramework
    inputs.property("url", url)
    inputs.property("checksum", checksum)
    inputs.property("klibVersion", klibVersion)
    outputs.dir(output).withPropertyName("xcframework")
    outputs.cacheIf { true }
    doLast {
        check("/download/v${klibVersion.get()}/" in url.get()) {
            "$manifestPath points at ${url.get()}, but eudiLibKmpEtsi1196x2Ios is ${klibVersion.get()}. " +
                    "Move both together: the klib and PKIXBridge must come from the same release."
        }
        val connection = URI(url.get()).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        val zip = connection.getInputStream().use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(zip)
            .joinToString("") { "%02x".format(it) }
        check(actual == checksum.get()) {
            "${url.get()} has SHA-256 $actual; $manifestPath expects ${checksum.get()}."
        }
        val root = output.get().asFile.apply { deleteRecursively(); mkdirs() }.canonicalFile
        ZipInputStream(zip.inputStream()).use { entries ->
            generateSequence { entries.nextEntry }.forEach { entry ->
                val file = File(root, entry.name).canonicalFile
                check(file.toPath().startsWith(root.toPath())) { "Zip entry outside the target: ${entry.name}" }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile.mkdirs()
                    file.outputStream().use { entries.copyTo(it) }
                }
            }
        }
        check(File(root, "PKIXBridge.xcframework/Info.plist").isFile) {
            "${url.get()} did not unpack to PKIXBridge.xcframework"
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
    //    the package in `iosApp/PKIXBridge` (upstream's prebuilt xcframework). A Kotlin/Native test
    //    binary has no Xcode target to borrow from, so it links the same framework itself — see
    //    [downloadPkixBridge].
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
        val (slice, sdk) = pkixBridgeSlice(targetName)
        binaries.withType(TestExecutable::class.java).configureEach {
            linkerOpts("-lsqlite3")
            linkerOpts(
                "-F${pkixBridgeFramework.get().asFile.absolutePath}/PKIXBridge.xcframework/$slice",
                "-framework", "PKIXBridge",
            )
            linkTaskProvider.configure {
                // An input, not just a dependency: a new framework must relink the test binary.
                inputs.files(downloadPkixBridge).withPropertyName("pkixBridgeFramework")
                // Upstream builds the framework for iOS 13, so its objects autolink Swift's
                // back-deployment libraries (`swiftCompatibility56` …), which the toolchain holds and
                // the Kotlin/Native linker does not search. Without this: `Undefined symbols …
                // __swift_FORCE_LOAD_$_swiftCompatibility56`. A provider, so only an iOS link asks Xcode.
                toolOptions.freeCompilerArgs.addAll(
                    swiftToolchainLibraries(sdk).map { listOf("-linker-option", "-L$it") }
                )
            }
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
        // Probes are DEVELOPER TOOLING and must not reach a shipped binary: they drive real issuance
        // against real issuers, seed fixtures and print diagnostics. They compile for every build
        // EXCEPT a Release one, which is the only kind a user ever receives. Xcode passes
        // `CONFIGURATION` to the framework build, so the decision is made where the app is actually
        // assembled; a plain Gradle run (tests, CI) has no such variable and keeps them, which is what
        // a developer wants. ⛔ Do not move probe code back into `iosMain`.
        val isReleaseBuild = providers.environmentVariable("CONFIGURATION").orNull == "Release"
        if (!isReleaseBuild) {
            kotlin.srcDir("src/iosProbeMain/kotlin")
        }
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
        // Deliberately NOT in commonMain: Android reaches multipaz-android through wallet-core (at its own
        // version, 0.101.0 under wallet-core 0.31.0), so putting it in commonMain would add a second
        // version *request* to the app's classpath and couple half the Android app to multipaz for no
        // gain. `implementation`, since no multipaz
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
            implementation(libs.eudi.lib.kmp.ios.etsi119602.consultation)
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
 * The slice of `PKIXBridge.xcframework` and the SDK for a native target.
 *
 * Only the two targets this module declares. An unmapped one fails loudly rather than silently
 * linking the wrong platform, which would surface as a confusing link error much later.
 */
fun pkixBridgeSlice(targetName: String): Pair<String, String> = when (targetName) {
    "iosSimulatorArm64" -> "ios-arm64_x86_64-simulator" to "iphonesimulator"
    "iosArm64" -> "ios-arm64" to "iphoneos"
    else -> error("No PKIXBridge slice for '$targetName'; add one above.")
}

/**
 * The selected toolchain's `usr/lib/swift/<sdk>`, where Swift's back-deployment libraries live.
 *
 * Asked of `xcrun` — the same lookup the Kotlin/Native linker makes — so it follows `xcode-select`
 * and `DEVELOPER_DIR`. Evaluated only when a link task that needs it runs.
 */
fun swiftToolchainLibraries(sdk: String): Provider<String> =
    providers.exec { commandLine("xcrun", "--sdk", sdk, "--find", "swiftc") }
        .standardOutput.asText
        .map { File(it.trim()).parentFile.parentFile.resolve("lib/swift/$sdk").absolutePath }

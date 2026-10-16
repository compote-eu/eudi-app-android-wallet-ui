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

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.gms) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.secrets) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.owasp.dependencycheck) apply false
    alias(libs.plugins.kotlinx.kover) apply false
    alias(libs.plugins.sonar) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.koin.compiler) apply false
    alias(libs.plugins.dependencyGraph)
    // `apply false` puts these on the build's classpath without applying them here; the convention
    // plugin below is what applies and configures them.
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    // Applied to the root project only: it configures detekt here and ktlint across every
    // project, so a new module is covered by the gate without having to remember it.
    id("project.code.quality")
}

moduleGraphConfig {
    readmePath.set("wiki/dependency-graph.md")
}

// ── iOS project generation ────────────────────────────────────────────────────────────────────────
//
// `iosApp/project.yml` takes the app's name, id and version from `app.properties` and
// `version.properties` — the same two files the Android build reads — through XcodeGen's
// generate-time `${...}` expansion, which reads the *environment*. This task is what puts them there,
// and is therefore the only supported way to regenerate the Xcode project: a bare
// `xcodegen generate` leaves every reference as literal text, with no warning at all.
tasks.register("generateIosProject") {
    group = "ios"
    description = "Regenerates iosApp/iosApp.xcodeproj from project.yml and the app/version properties"

    // Locals rather than script-level properties: the configuration cache cannot serialize a
    // reference to the script object, so everything the action closes over has to be a plain value.
    val identityFiles = listOf("version.properties", "app.properties")
    val repoRoot = layout.projectDirectory.asFile
    // The purpose strings' source: the shared Compose string catalogue, every locale of it.
    val composeResources = File(repoRoot, "shared-ui/src/commonMain/composeResources")
    inputs.files(identityFiles.map { File(repoRoot, it) } + File(repoRoot, "iosApp/project.yml"))
    inputs.files(fileTree(composeResources) { include("values*/strings.xml") })

    doLast {
        // A `${NAME}` XcodeGen should have expanded. `${NAME:-default}` is excluded on purpose: that
        // form is shell, and the script phases in project.yml legitimately use it. A script phase
        // needing a plain `${NAME}` would trip this check — write `$NAME` there instead.
        val unexpandedReference = Regex("""\$\{[A-Za-z_][A-Za-z0-9_]*}""")
        val environment = linkedMapOf<String, String>()
        identityFiles.forEach { name ->
            val file = File(repoRoot, name)
            if (!file.isFile) throw GradleException("$name is missing from the repository root")
            val properties = java.util.Properties().apply { file.reader().use { load(it) } }
            properties.forEach { key, value ->
                val previous = environment.put(key.toString(), value.toString())
                if (previous != null) {
                    throw GradleException("${key} is defined in more than one of $identityFiles")
                }
            }
        }

        // ── Purpose strings: the text of iOS's one-time permission alerts ───────────────────────────
        //
        // iOS reads them from each bundle's Info.plist, and their translations from that bundle's
        // `<locale>.lproj/InfoPlist.strings`, before any app code runs — so unlike the rest of the
        // wallet's text they cannot come from the Compose catalogue at runtime. They come from it here
        // instead: project.yml names each one as `${IOS_PERMISSION_<NAME>}`, which XcodeGen expands to
        // the English `ios_permission_<name>` string, and every other locale is written out as that
        // bundle's InfoPlist.strings. Which keys a bundle carries is read from project.yml itself.
        val permissionReference =
            Regex("""^\s+([A-Za-z][A-Za-z0-9]*):\s*\$\{(IOS_PERMISSION_[A-Z0-9_]+)}\s*$""")
        val targetHeader = Regex("""^  ([A-Za-z0-9_]+):\s*$""")
        val sourcesPath = Regex("""^      - path:\s*(\S+)\s*$""")
        // Target → (its source folder, Info.plist key → environment name), in project.yml order.
        val permissionsByTarget = linkedMapOf<String, Pair<String?, MutableMap<String, String>>>()
        var inTargets = false
        var target: String? = null
        var inSources = false
        File(repoRoot, "iosApp/project.yml").readLines().forEach { line ->
            when {
                line == "targets:" -> inTargets = true
                line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("#") -> inTargets = false
                !inTargets -> Unit
                targetHeader.matches(line) -> {
                    target = targetHeader.find(line)!!.groupValues[1]
                    permissionsByTarget[target!!] = null to linkedMapOf()
                    inSources = false
                }
                line == "    sources:" -> inSources = true
                inSources && sourcesPath.matches(line) -> {
                    val folder = sourcesPath.find(line)!!.groupValues[1]
                    val current = permissionsByTarget.getValue(target!!)
                    if (current.first == null) permissionsByTarget[target!!] = folder to current.second
                    inSources = false
                }
                permissionReference.matches(line) && target != null -> {
                    val (key, variable) = permissionReference.find(line)!!.destructured
                    permissionsByTarget.getValue(target!!).second[key] = variable
                }
            }
        }

        // values/ is English, the development language; values-sk/ is sk, values-pt-rBR/ is pt-BR.
        fun localeOf(directory: String): String? = when {
            directory == "values" -> "en"
            directory.startsWith("values-b+") -> directory.removePrefix("values-b+").replace('+', '-')
            Regex("""values-[a-z]{2,3}(-r[A-Z]{2})?""").matches(directory) ->
                directory.removePrefix("values-").replace("-r", "-")
            else -> null
        }
        // An Android-style resource string, as Compose resources read it: optional surrounding quotes,
        // and backslash escapes.
        fun unescapeResource(raw: String): String {
            var text = raw.trim()
            if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
                text = text.substring(1, text.length - 1)
            }
            val out = StringBuilder()
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '\\' && index + 1 < text.length) {
                    when (val next = text[index + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> out.append(next)
                    }
                    index += 2
                } else {
                    out.append(char)
                    index++
                }
            }
            return out.toString()
        }
        fun permissionStrings(file: File): Map<String, String> {
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                .filter { it.getAttribute("name").startsWith("ios_permission_") }
                .associate { it.getAttribute("name") to unescapeResource(it.textContent) }
        }
        val localeDirectories = composeResources.listFiles().orEmpty()
            .filter { File(it, "strings.xml").isFile && localeOf(it.name) != null }
            .sortedBy { it.name }
        val stringsByLocale = localeDirectories.associate { directory ->
            localeOf(directory.name)!! to permissionStrings(File(directory, "strings.xml"))
        }
        fun resourceName(variable: String) = "ios_permission_" +
            variable.removePrefix("IOS_PERMISSION_").lowercase()

        val english = stringsByLocale["en"].orEmpty()
        permissionsByTarget.values.flatMap { it.second.values }.distinct().forEach { variable ->
            val text = english[resourceName(variable)] ?: throw GradleException(
                "project.yml uses \${$variable}, but ${resourceName(variable)} is missing from " +
                    "shared-ui/src/commonMain/composeResources/values/strings.xml"
            )
            if (environment.put(variable, text) != null) {
                throw GradleException("$variable is also defined in one of $identityFiles")
            }
        }

        val generatedMarker = "/* Generated by ./gradlew generateIosProject"
        fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
        permissionsByTarget.forEach { (name, entry) ->
            val (folder, keys) = entry
            if (folder == null || keys.isEmpty()) return@forEach
            val bundleRoot = File(repoRoot, "iosApp/$folder")
            // Replace what this task wrote last time, and nothing else: a hand-written
            // InfoPlist.strings would not carry the marker, and is left alone.
            bundleRoot.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.endsWith(".lproj") }
                .map { File(it, "InfoPlist.strings") }
                .filter { it.isFile && it.readText().startsWith(generatedMarker) }
                .forEach { stale ->
                    stale.delete()
                    // A locale the catalogue no longer has leaves no empty folder behind.
                    stale.parentFile.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
                }
            stringsByLocale.forEach { (locale, strings) ->
                val lines = keys.mapNotNull { (key, variable) ->
                    strings[resourceName(variable)]?.let { "${quoted(key)} = ${quoted(it)};" }
                }
                val missing = keys.values.map(::resourceName).filterNot(strings::containsKey)
                if (missing.isNotEmpty() && locale != "en") {
                    logger.warn(
                        "$name: no $locale translation for ${missing.joinToString()}; " +
                            "iOS shows the English text for those"
                    )
                }
                if (lines.isEmpty()) return@forEach
                val directory = localeDirectories.first { localeOf(it.name) == locale }.name
                File(bundleRoot, "$locale.lproj").apply { mkdirs() }
                    .resolve("InfoPlist.strings")
                    .writeText(
                        "$generatedMarker from shared-ui/src/commonMain/composeResources/" +
                            "$directory/strings.xml. Do not edit: change the ios_permission_ strings " +
                            "there. */\n" + lines.joinToString("\n") + "\n"
                    )
            }
        }

        val builder = ProcessBuilder("xcodegen", "generate")
            .directory(File(repoRoot, "iosApp"))
            .redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = try {
            builder.start()
        } catch (_: java.io.IOException) {
            throw GradleException("xcodegen is not on PATH — install it with `brew install xcodegen`")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val status = process.waitFor()
        logger.lifecycle(output.trim())
        if (status != 0) throw GradleException("`xcodegen generate` failed with exit code $status")

        // XcodeGen does not report an unset variable, it just writes the reference through — so the
        // generated project is the only place a missing or mistyped key shows up. Left unchecked it
        // would reach a device as an app literally named "${APP_NAME}".
        val generated = listOf(
            File(repoRoot, "iosApp/iosApp.xcodeproj/project.pbxproj"),
            File(repoRoot, "iosApp/iosApp/Info.plist"),
            File(repoRoot, "iosApp/DocumentProvider/Info.plist"),
        )
        val leftovers = generated.filter { it.isFile }.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                unexpandedReference.containsMatchIn(line)
            }.map { (index, line) -> "  ${file.name}:${index + 1}: ${line.trim()}" }
        }
        if (leftovers.isNotEmpty()) {
            throw GradleException(
                "XcodeGen left references unexpanded, so a key is missing from $identityFiles:\n" +
                    leftovers.joinToString("\n")
            )
        }
    }
}

true

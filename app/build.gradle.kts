import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

val verifyPikafishNetwork = tasks.register("verifyPikafishNetwork") {
    val expectedSha256 =
        "7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e"
    val network = layout.projectDirectory.file(
        "src/main/assets/pikafish/pikafish.nnue",
    )
    inputs.file(network)
    doLast {
        val file = network.asFile
        check(file.length() == 50_706_378L) {
            "Bundled Pikafish network has an unexpected size"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString(separator = "") {
            "%02x".format(it.toInt() and 0xff)
        }
        check(actual == expectedSha256) {
            "Bundled Pikafish network failed SHA-256 verification"
        }
    }
}

val verifyGameSounds = tasks.register("verifyGameSounds") {
    val names = listOf(
        "chess_capture.wav",
        "chess_move.wav",
        "game_defeat.wav",
        "game_draw.wav",
        "game_victory.wav",
    )
    val soundFiles = names.associateWith {
        layout.projectDirectory.file("src/main/res/raw/$it")
    }
    inputs.files(soundFiles.values)
    doLast {
        val expectedSounds = mapOf(
            "chess_capture.wav" to Pair(
                7_542L,
                "5e790742af8cec7e9bf4f71a961bc8e147e8f42afdc8e6bbde07d1aa451f3d49",
            ),
            "chess_move.wav" to Pair(
                4_896L,
                "768674eedef76dfd6d33584c0ca85d4166ff964ea857a1694bb91a59057f3387",
            ),
            "game_defeat.wav" to Pair(
                22_274L,
                "67a2fb5c692ce58082fb3fd6a4e2760a5068632ce27d48dadbff02d67b520f44",
            ),
            "game_draw.wav" to Pair(
                13_980L,
                "cfd71d1c55979325ad10a615a79ec767c9318666b551e2d638cb2c214d8867f2",
            ),
            "game_victory.wav" to Pair(
                19_622L,
                "a49ab42725f2d7eca65da11a542f925bf4193ef6659a08f815e2b05ae819122e",
            ),
        )
        expectedSounds.forEach { (name, expected) ->
            val file = soundFiles.getValue(name).asFile
            check(file.length() == expected.first) {
                "Game sound $name has an unexpected size"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString(separator = "") {
                "%02x".format(it.toInt() and 0xff)
            }
            check(actual == expected.second) {
                "Game sound $name failed SHA-256 verification"
            }
        }
    }
}

val verifyBundledLegalDocuments = tasks.register("verifyBundledLegalDocuments") {
    val legalCopies = listOf(
        rootProject.file("LICENSE") to
            layout.projectDirectory.file("src/main/assets/legal/GPL-3.0.txt").asFile,
        rootProject.file("NOTICE.md") to
            layout.projectDirectory.file("src/main/assets/legal/OPEN-SOURCE-NOTICES.md").asFile,
        rootProject.file("third_party/pikafish-network/UPSTREAM-README.md") to
            layout.projectDirectory.file("src/main/assets/pikafish/NETWORK-LICENSE.md").asFile,
    )
    inputs.files(legalCopies.flatMap { listOf(it.first, it.second) })
    doLast {
        legalCopies.forEach { (source, bundled) ->
            check(source.readBytes().contentEquals(bundled.readBytes())) {
                "Bundled legal document ${bundled.name} differs from ${source.path}"
            }
        }
    }
}

val verifyReleasedGameContent = tasks.register("verifyReleasedGameContent") {
    val releaseContentManifest = rootProject.file("content/manifests/release-content-v1.tsv")
    val releaseContentRoot = rootProject.projectDir
    val endgameAssets = layout.projectDirectory.dir("src/main/assets/endgames").asFile
    val openingSource = layout.projectDirectory.file(
        "src/main/kotlin/com/masterofchessstrategy/opening/ChineseChessOpeningLibrary.kt",
    ).asFile
    inputs.file(releaseContentManifest)
    inputs.dir(endgameAssets)
    inputs.file(openingSource)
    doLast {
        val openingPath = openingSource.relativeTo(releaseContentRoot).invariantSeparatorsPath
        val expectedPaths = endgameAssets.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(releaseContentRoot).invariantSeparatorsPath }
            .toSet() + openingPath
        val listedPaths = mutableSetOf<String>()
        releaseContentManifest.readLines(Charsets.UTF_8).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val fields = line.split('|')
            check(fields.size == 5) { "Invalid content manifest row ${index + 1}" }
            val (path, hash, license, origin, evidence) = fields
            check(path == openingPath || path.startsWith("app/src/main/assets/endgames/")) {
                "Content manifest path is outside the published game content scope"
            }
            check(path !in listedPaths && ".." !in path && '\\' !in path) {
                "Duplicate or unsafe content manifest path: $path"
            }
            check(hash.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid SHA-256 for released game content: $path"
            }
            check(license == "GPL-3.0-or-later" && origin == "project-authored") {
                "External game content requires a separate reviewed license and release gate"
            }
            check(evidence.startsWith("content/manifests/") && ".." !in evidence) {
                "Missing provenance evidence for released game content: $path"
            }
            val sourceFile = releaseContentRoot.resolve(path)
            val evidenceFile = releaseContentRoot.resolve(evidence)
            check(sourceFile.isFile && evidenceFile.isFile) {
                "Released game content or its provenance evidence is missing: $path"
            }
            val actualHash = MessageDigest.getInstance("SHA-256")
                .digest(sourceFile.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actualHash == hash) {
                "Released game content differs from its reviewed manifest: $path"
            }
            listedPaths += path
        }
        check(listedPaths == expectedPaths) {
            "Published endgame or opening content is missing from the release manifest"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyPikafishNetwork)
    dependsOn(verifyGameSounds)
    dependsOn(verifyBundledLegalDocuments)
    dependsOn(verifyReleasedGameContent)
}

android {
    namespace = "com.masterofchessstrategy"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.masterofchessstrategy"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Pikafish uses 128-bit bitboards and supports Android only on 64-bit ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":engine-api"))
    implementation(project(":engine-native"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Room 2.8.5 migration serializers require GeneratedSerializer's JVM
    // default method, which is absent from the older transitive 1.7.3 core.
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

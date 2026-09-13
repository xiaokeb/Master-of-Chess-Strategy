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

tasks.named("preBuild").configure {
    dependsOn(verifyPikafishNetwork)
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
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

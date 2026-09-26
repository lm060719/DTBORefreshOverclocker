import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.mo.dtbooverclocker"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.mo.dtbooverclocker"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "1.1.6"

        // Includes uncommitted source edits, unlike a git commit alone. Stable across identical builds.
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        fileTree("src/main") { include("**/*.kt", "**/*.xml") }.files
            .sortedBy { it.relativeTo(projectDir).invariantSeparatorsPath }
            .forEach { source ->
                sourceDigest.update(source.relativeTo(projectDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                sourceDigest.update(0.toByte())
                sourceDigest.update(source.readBytes())
                sourceDigest.update(0.toByte())
            }
        val sourceId = sourceDigest.digest().joinToString("") { "%02x".format(it) }.take(16)
        buildConfigField("String", "SOURCE_ID", "\"$sourceId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Optional real-image regression; fixtures stay outside the source tree.
    systemProperty("dtbo.sampleDir", providers.gradleProperty("sampleDir").getOrElse(""))
    systemProperty("dtbo.dtc", providers.gradleProperty("hostDtc").getOrElse(""))
    systemProperty("dtbo.workflowSampleDir", providers.gradleProperty("workflowSampleDir").getOrElse(""))
    // e.g. -PtestMaxHeap=256m reproduces Android's default per-app heap limit.
    providers.gradleProperty("testMaxHeap").orNull?.let { maxHeapSize = it }
    providers.gradleProperty("sampleDir").orNull?.let { directory ->
        inputs.files(File(directory, "dtbo_b.img"), File(directory, "dtbo_b_144hz_scaled.img"))
    }
    providers.gradleProperty("hostDtc").orNull?.let { inputs.file(it) }
    providers.gradleProperty("avbSampleImage").orNull?.let {
        systemProperty("dtbo.avbSampleImage", it)
        inputs.file(it)
    }
    providers.gradleProperty("chargingSampleImage").orNull?.let {
        systemProperty("dtbo.chargingSampleImage", it)
        inputs.file(it)
    }
    providers.gradleProperty("chargingSampleDts").orNull?.let {
        systemProperty("dtbo.chargingSampleDts", it)
        inputs.file(it)
    }
    providers.gradleProperty("chargingDeviceImage").orNull?.let {
        systemProperty("dtbo.chargingDeviceImage", it)
        inputs.file(it)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

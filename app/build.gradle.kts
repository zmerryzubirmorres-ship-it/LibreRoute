plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Set when publishing our own GitHub Releases. An unset value disables update
// checks; a local checkout must never poll the upstream project's releases.
val updateRepository = providers.gradleProperty("openfluxUpdateRepository")
    .orNull?.trim().orEmpty()
require(updateRepository.isEmpty() ||
    Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(updateRepository)) {
    "openfluxUpdateRepository must be a GitHub owner/repository pair"
}

// Release updates require the same private signing key on every version.
// Keep key material outside the repository and never fall back to the debug key.
val releaseKeyStore = providers.environmentVariable("OPENFLUX_RELEASE_KEYSTORE").orNull
val releaseKeyStorePassword = providers.environmentVariable("OPENFLUX_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("OPENFLUX_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("OPENFLUX_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = !releaseKeyStore.isNullOrBlank() &&
    !releaseKeyStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "io.github.p1neapplexpress.openflux"
    compileSdk = 34
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.p1neapplexpress.openflux"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.2.3"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
        aidl = true
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("ownRelease") {
                storeFile = file(releaseKeyStore!!)
                storePassword = releaseKeyStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("ownRelease")
            }
        }
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libp1npplydtransport.so"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(releaseSigningReady) {
            "Release signing is not configured. Set OPENFLUX_RELEASE_KEYSTORE, " +
                "OPENFLUX_RELEASE_STORE_PASSWORD, OPENFLUX_RELEASE_KEY_ALIAS and " +
                "OPENFLUX_RELEASE_KEY_PASSWORD. Debug keys must not sign published APKs."
        }
        check(file(releaseKeyStore!!).isFile) {
            "OPENFLUX_RELEASE_KEYSTORE does not point to a readable file"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.github.g00fy2.quickie:quickie-unbundled:1.10.0")
    implementation("com.google.zxing:core:3.5.3")
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apkFile = releaseDir.listFiles()?.firstOrNull { it.extension == "apk" }
            ?: layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (apkFile.exists()) {
            val dest = rootProject.file("LibreRoute.apk")
            apkFile.copyTo(dest, overwrite = true)
        }
    }
}

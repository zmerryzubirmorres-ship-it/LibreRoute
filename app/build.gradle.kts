import java.security.MessageDigest
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import groovy.json.JsonSlurper
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Set when publishing our own GitHub Releases. An unset value disables update
// checks; a local checkout must never poll the upstream project's releases.
val updateRepository = providers.gradleProperty("librerouteUpdateRepository")
    .orNull?.trim().orEmpty()
require(updateRepository.isEmpty() ||
    Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(updateRepository)) {
    "librerouteUpdateRepository must be a GitHub owner/repository pair"
}

// Release updates require the same private signing key on every version.
// Keep key material outside the repository and never fall back to the debug key.
val releaseKeyStore = providers.environmentVariable("LIBREROUTE_RELEASE_KEYSTORE").orNull
val releaseKeyStorePassword = providers.environmentVariable("LIBREROUTE_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("LIBREROUTE_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("LIBREROUTE_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = !releaseKeyStore.isNullOrBlank() &&
    !releaseKeyStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

val bundledCoreVersion = Regex("var CoreVersion = \\\"([^\\\"]+)\\\"")
    .find(File(rootProject.projectDir.parentFile, "LibreRoute-Core/admin/core_compatibility.go").readText())
    ?.groupValues?.get(1)
    ?: error("CoreVersion is unavailable")

android {
    namespace = "io.github.libreroute"
    compileSdk = 34
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.libreroute"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.2.3"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("String", "BUNDLED_CORE_VERSION", "\"$bundledCoreVersion\"")

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
        create("trialRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".trial"
            versionNameSuffix = "-trial"
            matchingFallbacks += listOf("release")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.matching { it.name == "preReleaseBuild" || it.name == "preTrialReleaseBuild" }.configureEach {
    doFirst {
        check(releaseSigningReady) {
            "Release signing is not configured. Set LIBREROUTE_RELEASE_KEYSTORE, " +
                "LIBREROUTE_RELEASE_STORE_PASSWORD, LIBREROUTE_RELEASE_KEY_ALIAS and " +
                "LIBREROUTE_RELEASE_KEY_PASSWORD. Debug keys must not sign published APKs."
        }
        check(file(releaseKeyStore!!).isFile) {
            "LIBREROUTE_RELEASE_KEYSTORE does not point to a readable file"
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
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
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

tasks.register("verifyCoreArtifacts") {
    group = "verification"
    description = "Verifies that bundled Core binaries and install manifests exist, are fresh, and match hashes."
    doLast {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { b: Byte -> "%02x".format(b) }
        }

        val workspaceRoot = rootProject.projectDir.parentFile
        val coreRoot = File(workspaceRoot, "LibreRoute-Core")
        val sourceFiles = coreRoot.walkTopDown().onEnter { it.name != ".git" }.filter { f ->
            f.isFile && ((f.extension == "go" && !f.name.endsWith("_test.go")) ||
                f.name in setOf("go.mod", "go.sum") || f.extension in setOf("ps1", "sh"))
        }.toList() + listOf("core-artifacts.ps1", "build-libreroute-core.ps1", "app/build.gradle.kts")
            .map { File(rootProject.projectDir, it) }
        val sourceRecords = sourceFiles.sortedBy { it.relativeTo(workspaceRoot).invariantSeparatorsPath }
            .joinToString("") { "${it.relativeTo(workspaceRoot).invariantSeparatorsPath}\u0000${sha256(it)}\n" }
        val sourceHash = MessageDigest.getInstance("SHA-256").digest(sourceRecords.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        fun jsonObject(text: String): Map<*, *> = JsonSlurper().parseText(text) as? Map<*, *>
            ?: error("Expected a JSON object")
        val receiptFile = file("src/main/assets/install/core-artifacts.json")
        check(receiptFile.isFile) { "Core build receipt is missing; rebuild Android and Linux Core." }
        val receipt = jsonObject(receiptFile.readText())
        check(receipt["schema"] == 1) { "Unsupported Core build receipt" }
        val receipts = receipt["artifacts"] as? Map<*, *> ?: error("Missing Core artifact receipts")

        fun verifyReceipt(name: String, binary: File): String {
            check(binary.isFile && binary.length() > 0) { "Missing Core artifact: ${binary.name}" }
            val entry = receipts[name] as? Map<*, *> ?: error("Missing build receipt for $name; rebuild Core.")
            check(entry["source_sha256"] == sourceHash) { "Stale Core artifact $name: sources or build scripts changed. Rebuild Core." }
            check(entry["sha256"] == sha256(binary)) { "Core artifact $name differs from its build receipt. Rebuild Core." }
            return entry["install_public_key"] as? String ?: error("Missing installation public key in $name receipt")
        }
        val nativePublicKey = verifyReceipt("native_arm64", file("src/main/jniLibs/arm64-v8a/liblibreroute_client.so"))
        // zapret is part of the base client. Keep the direct-dpi profile usable
        // immediately after installation instead of silently producing an APK
        // that only works after a separate native setup step.
        val zapretBinary = file("src/main/jniLibs/arm64-v8a/libtpws.so")
        check(zapretBinary.isFile && zapretBinary.length() > 0) {
            "Missing bundled zapret tpws binary: ${zapretBinary.absolutePath}"
        }
        val expectedVersion = Regex("var CoreVersion = \"([^\"]+)\"")
            .find(File(coreRoot, "admin/core_compatibility.go").readText())?.groupValues?.get(1)
            ?: error("CoreVersion is unavailable")

        fun checkManifest(arch: String) {
            val binFile = file("src/main/assets/install/libreroute-linux-$arch")
            val manifestFile = file("src/main/assets/install/manifest-$arch.json")
            check(binFile.isFile && binFile.length() > 0) {
                "Missing install binary: ${binFile.absolutePath}."
            }
            check(manifestFile.isFile && manifestFile.length() > 0) {
                "Missing install manifest: ${manifestFile.absolutePath}."
            }
            val publicKey = verifyReceipt("linux_$arch", binFile)
            check(publicKey == nativePublicKey) { "Android and Linux Core use different installation trust keys" }
            val signed = jsonObject(manifestFile.readText())
            val payload = Base64.getUrlDecoder().decode(signed["payload"] as? String ?: error("Missing manifest payload"))
            val signature = Base64.getUrlDecoder().decode(signed["signature"] as? String ?: error("Missing manifest signature"))
            val rawKey = Base64.getUrlDecoder().decode(publicKey)
            check(rawKey.size == 32) { "Invalid Ed25519 installation public key" }
            val keyPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(keyPrefix + rawKey)))
            verifier.update(payload)
            check(verifier.verify(signature)) { "Installation manifest signature mismatch for $arch" }
            val manifest = jsonObject(String(payload, Charsets.UTF_8))
            check(manifest["sha256"] == sha256(binFile) && (manifest["size"] as? Number)?.toLong() == binFile.length()) {
                "Installation manifest hash or size mismatch for $arch"
            }
            check(manifest["os"] == "linux" && manifest["arch"] == arch && manifest["version"] == expectedVersion) {
                "Installation manifest target or version differs from current Core ($expectedVersion)"
            }
        }

        checkManifest("amd64")
        checkManifest("arm64")
    }
}

tasks.named("preBuild").configure {
    dependsOn("verifyCoreArtifacts")
}

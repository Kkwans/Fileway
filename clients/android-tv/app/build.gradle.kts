import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val tvAudioManifest = providers.gradleProperty("filewayTvFfmpegManifest").orNull
val tvAudioFromSource = providers.gradleProperty("filewayTvFfmpegWorkDir").isPresent
val sourceRevision = providers.gradleProperty("filewaySourceRevision").orElse("uncommitted").get()
val tvSigningFile = providers.gradleProperty("filewayTvSigningFile").orNull?.let { file(it).canonicalFile }
val tvSigning = tvSigningFile?.let { input ->
    Properties().apply { input.inputStream().use { load(it) } }.also { values ->
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
            require(!values.getProperty(key).isNullOrBlank()) { "Missing TV signing property: $key" }
        }
        require(File(values.getProperty("storeFile")).isAbsolute) { "TV keystore path must be absolute" }
    }
}
require(sourceRevision == "uncommitted" || sourceRevision.matches(Regex("[a-f0-9]{40}"))) { "Provide an exact source revision" }
var verifiedTvAudio: java.io.File? = null
if (tvAudioManifest != null) {
    val manifestFile = file(tvAudioManifest).canonicalFile
    val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
    fun sha(input: java.io.File) = MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
    require(manifest["schema"] == 1 && manifest["packagingVerified"] == true && manifest["minSdk"] == 28 && manifest["media3Version"] == "1.11.1")
    require(manifest["inputLockSha256"] == sha(rootProject.file("../shared/android/native/media3-ffmpeg-tv.lock.json")))
    require(manifest["recipeSha256"] == sha(rootProject.file("../shared/android/build-tv-audio.py")))
    require(manifest["packageRecipeSha256"] == sha(rootProject.file("../shared/android/package-tv-audio.py")))
    require(manifest["sharedRecipeSha256"] == sha(rootProject.file("../android/scripts/prepare-ffmpeg-probe.py")))
    require(manifest["aarFile"] == "fileway-tv-audio.aar")
    val aar = manifestFile.resolveSibling("fileway-tv-audio.aar").canonicalFile
    require(aar.parentFile == manifestFile.parentFile && sha(aar) == manifest["aarSha256"]) { "TV audio artifact hash/path mismatch" }
    verifiedTvAudio = aar
}
val verifyReleaseInputs = tasks.register("verifyTvReleaseInputs") {
    doLast {
        require(tvAudioFromSource || verifiedTvAudio != null) { "Release requires the verified API28 TV audio extension" }
        require(sourceRevision.matches(Regex("[a-f0-9]{40}"))) { "Release requires an exact validated source SHA" }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseInputs) }
android {
    namespace = "io.github.kkwans.fileway.tv"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "io.github.kkwans.fileway.tv"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"
        buildConfigField("String", "SOURCE_REVISION", "\"$sourceRevision\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += providers.gradleProperty("filewayTvAbis").orElse("armeabi-v7a,arm64-v8a").get().split(',') }
        externalNativeBuild { cmake { arguments += "-DNFB_CORE_DIR=${rootProject.projectDir.resolve("../shared/core/generated/api28-direct").canonicalPath}" } }
    }
    sourceSets.getByName("androidTest").assets.srcDir("../../android/app/src/androidTest/assets")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("../../android/app/src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    val privateTvSigning = tvSigning?.let { values ->
        signingConfigs.create("tvPrivate") {
            storeFile = file(values.getProperty("storeFile"))
            storePassword = values.getProperty("storePassword")
            keyAlias = values.getProperty("keyAlias")
            keyPassword = values.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { privateTvSigning?.let { signingConfig = it } }
        release {
            privateTvSigning?.let { signingConfig = it }
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    if (tvAudioFromSource) implementation(project(":tv-audio")) else verifiedTvAudio?.let { implementation(files(it)) }
    implementation(project(":client-core"))
    implementation(project(":media-core"))
    implementation(project(":design-foundation"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

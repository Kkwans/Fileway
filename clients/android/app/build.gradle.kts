import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
// libVLC is retained only by the reference instrumentation harness.
val vlcProbeVersion = providers.gradleProperty("filewayVlcProbeVersion").orNull
require(vlcProbeVersion == null || vlcProbeVersion == "3.7.7") { "Only the pinned libVLC 3.7.7 comparison is supported" }
val engineComparisonOption = providers.gradleProperty("filewayEngineComparison")
    .orElse((vlcProbeVersion != null || providers.gradleProperty("filewayMpvProbeAar").isPresent).toString()).get()
require(engineComparisonOption in listOf("true", "false")) { "filewayEngineComparison must be true or false" }
val engineComparison = engineComparisonOption == "true"
require(engineComparison || vlcProbeVersion == null && !providers.gradleProperty("filewayMpvProbeAar").isPresent) {
    "Reference engine candidates require the explicit comparison source set"
}
val audioCaptureOption = providers.gradleProperty("filewayAudioCaptureProbe").orElse("false").get()
require(audioCaptureOption in listOf("true", "false")) { "filewayAudioCaptureProbe must be true or false" }
val audioCaptureProbe = audioCaptureOption == "true"
val realMediaOption = providers.gradleProperty("filewayRealMediaProbe").orElse("false").get()
require(realMediaOption in listOf("true", "false")) { "filewayRealMediaProbe must be true or false" }
val realMediaProbe = realMediaOption == "true"
require(!(realMediaProbe && audioCaptureProbe)) { "Use separate real-media and audio-capture diagnostics" }
android {
    namespace = "io.github.kkwans.nasfilebrowser"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "io.github.kkwans.nasfilebrowser"
        minSdk = 29
        targetSdk = 37
        versionCode = 15
        versionName = "0.7.5-preview"
        buildConfigField("boolean", "NATIVE_VERBOSE", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // External control-plane acceptance runs explicitly through adb.
        // Filter at discovery: UTP reports runtime assumptions as failures.
        testInstrumentationRunnerArguments["notAnnotation"] = "io.github.kkwans.nasfilebrowser.ExternalNetworkAcceptance"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += "-DNFB_CORE_DIR=${rootProject.projectDir.resolve("../shared/core/generated").canonicalPath}" }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    if (engineComparison) sourceSets.getByName("androidTest").kotlin.srcDir("src/engineComparisonTest/java")
    if (audioCaptureProbe) {
        sourceSets.getByName("debug").manifest.srcFile("src/audioCaptureProbe/AndroidManifest.xml")
        sourceSets.getByName("debug").kotlin.srcDir("src/audioCaptureProbe/java")
        sourceSets.getByName("androidTest").kotlin.srcDir("src/audioCaptureProbeTest/java")
    }
    if (realMediaProbe) {
        sourceSets.getByName("debug").manifest.srcFile("src/realMediaProbe/AndroidManifest.xml")
        sourceSets.getByName("androidTest").kotlin.srcDir("src/realMediaProbeTest/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            // Explicit owned-fixture diagnostics only; release always keeps verbose logging off.
            val nativeVerbose = providers.gradleProperty("filewayNativeVerbose").orElse("false").get()
            require(nativeVerbose in listOf("true", "false")) { "filewayNativeVerbose must be true or false" }
            buildConfigField("boolean", "NATIVE_VERBOSE", nativeVerbose)
            if (nativeVerbose == "true") versionNameSuffix = "-native-diagnostic"
            if (vlcProbeVersion != null) versionNameSuffix = (versionNameSuffix ?: "") + "-vlc-3.7.7-comparison"
            if (audioCaptureProbe) versionNameSuffix = (versionNameSuffix ?: "") + "-audio-diagnostic"
            if (realMediaProbe) versionNameSuffix = (versionNameSuffix ?: "") + "-real-media-diagnostic"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-gif:3.6.3")
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.commons:commons-csv:1.14.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("io.github.peerless2012:ass-kt:0.5.1")
    if (engineComparison) androidTestImplementation("org.videolan.android:libvlc-all:${vlcProbeVersion ?: "3.7.6"}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.39.2")
    implementation("io.noties:prism4j:2.0.0") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    annotationProcessor("io.noties:prism4j-bundler:2.0.0")
    implementation("dev.snipme:highlights-jvm:1.0.0") { version { strictly("1.0.0") } }
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    providers.gradleProperty("filewayMpvProbeAar").orNull?.let { path ->
        require(!providers.gradleProperty("filewayLibassProbeAar").isPresent && !providers.gradleProperty("filewayFfmpegProbeManifest").isPresent) {
            "Use separate comparison APKs for the published mpv and enhanced Media3 native payloads"
        }
        val aar = file(path)
        val actual = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        require(actual == "df146592480fc8418415a06b1f1a1d6318b0088e21f52254b0e9a82b61ca8fa2") { "Unverified mpv comparison AAR" }
        androidTestImplementation(files(aar))
        android.sourceSets.getByName("androidTest").kotlin.srcDir("src/mpvProbeTest/java")
        android.sourceSets.getByName("androidTest").assets.srcDir("src/mpvProbeTest/assets")
    }
    providers.gradleProperty("filewayLibassProbeAar").orNull?.let { path ->
        require(providers.gradleProperty("filewayFfmpegProbeManifest").isPresent) { "Combined subtitle probe requires the verified FFmpeg audio candidate" }
        val aar = file(path)
        val actual = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        require(actual == "1051212faf98ef956e06992b002d43cfdc81b6c60dcd32662e8d3ff52d584d65") { "Unverified libass comparison AAR" }
        // The product already supplies the same pinned libass runtime.
        android.sourceSets.getByName("androidTest").kotlin.srcDir("src/libassProbeTest/java")
    }
    val ffmpegManifest = providers.gradleProperty("filewayFfmpegManifest")
        .orElse(providers.gradleProperty("filewayFfmpegProbeManifest"))
    if (providers.gradleProperty("filewayFfmpegWorkDir").isPresent) {
        // Also permits building the native library itself without requiring its output first.
        implementation(project(":ffmpeg-probe"))
    } else {
        require(ffmpegManifest.isPresent) {
            "Provide -PfilewayFfmpegManifest=<verified ffmpeg-probe.json> or build from filewayFfmpegWorkDir"
        }
        val manifestPath = ffmpegManifest.get()
        val manifestFile = file(manifestPath).canonicalFile
        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        fun hash(input: File) = MessageDigest.getInstance("SHA-256").digest(input.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(manifest["schema"] == 1 && manifest["packagingVerified"] == true)
        require(manifest["media3Version"] == "1.11.1") { "Probe must match the pinned Media3 runtime" }
        require(manifest["inputLockSha256"] == hash(rootProject.file("native/media3-ffmpeg.lock.json"))) { "Probe inputs differ from the source lock" }
        require(manifest["recipeSha256"] == hash(rootProject.file("scripts/prepare-ffmpeg-probe.py"))) { "Probe recipe differs from this checkout" }
        require(manifest["aarFile"] == "fileway-media3-ffmpeg-probe.aar")
        val aar = manifestFile.resolveSibling("fileway-media3-ffmpeg-probe.aar").canonicalFile
        require(aar.parentFile == manifestFile.parentFile && hash(aar) == manifest["aarSha256"]) { "Probe AAR hash/path mismatch" }
        implementation(files(aar))
    }
    if (providers.gradleProperty("filewayFfmpegProbeManifest").isPresent) {
        android.sourceSets.getByName("androidTest").kotlin.srcDir("src/ffmpegProbeTest/java")
    }
}

plugins { id("com.android.library") }
val nativeRoot = file(providers.gradleProperty("filewayTvFfmpegWorkDir").get()).canonicalFile
require(nativeRoot.resolve("input-lock.json").readText() == file("../native/media3-ffmpeg-tv.lock.json").readText()) { "TV FFmpeg source lock mismatch" }
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += listOf("-DFILEWAY_PROBE_ROOT=${nativeRoot.path}", "-DANDROID_STL=c++_static") } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").java.srcDir(nativeRoot.resolve("sources/media3/java"))
    sourceSets.getByName("main").assets.srcDir(nativeRoot.resolve("sources/licenses"))
    externalNativeBuild { cmake { path = file("../../../android/ffmpeg-probe/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    compileOnly("org.checkerframework:checker-qual:3.13.0")
    compileOnly("androidx.annotation:annotation:1.6.0")
}

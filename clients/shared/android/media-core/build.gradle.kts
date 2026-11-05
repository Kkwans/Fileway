plugins { id("com.android.library") }
// Independent phone/TV Gradle roots must not share incremental class outputs.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared/media-core"))
android {
    namespace = "io.github.kkwans.fileway.media"
    compileSdk = 37
    defaultConfig { minSdk = 28; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
android { buildFeatures { buildConfig = true } }
dependencies {
    api(project(":client-core"))
    api("androidx.media3:media3-exoplayer:1.11.1")
    api("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.peerless2012:ass-kt:0.5.1")
}

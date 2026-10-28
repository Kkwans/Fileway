plugins { id("com.android.library") }
android {
    namespace = "io.github.kkwans.fileway.media"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
android { buildFeatures { buildConfig = true } }
dependencies {
    api(project(":client-core"))
    api("androidx.media3:media3-exoplayer:1.11.1")
    api("androidx.media3:media3-ui:1.11.1")
    implementation("io.github.peerless2012:ass-kt:0.5.1")
}

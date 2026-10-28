plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
}
// Phone and TV have independent Gradle roots. Never share incremental asset,
// KSP or Kotlin outputs between those two builds of the same source module.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared/remote-core"))
android {
    namespace = "io.github.kkwans.fileway.remote"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    api(project(":client-core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.google.zxing:core:3.5.4")
    ksp("androidx.room:room-compiler:2.8.5")
}

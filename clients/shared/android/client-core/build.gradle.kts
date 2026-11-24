plugins { id("com.android.library") }
// Each app has its own Gradle root; their incremental outputs must not collide.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared/client-core"))
android {
    namespace = "io.github.kkwans.fileway.core"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("androidx.room:room-runtime:2.8.5")
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

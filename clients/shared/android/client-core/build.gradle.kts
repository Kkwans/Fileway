plugins { id("com.android.library") }
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
}

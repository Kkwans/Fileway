plugins { id("com.android.library") }
android {
    namespace = "io.github.kkwans.fileway.design"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.ui:ui-text")
}

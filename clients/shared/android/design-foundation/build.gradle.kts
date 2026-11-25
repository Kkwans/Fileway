plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Each app has its own Gradle root; their incremental outputs must not collide.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared/design-foundation"))
android {
    namespace = "io.github.kkwans.fileway.design"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
dependencies {
    configurations.configureEach {
        exclude(group = "androidx.lifecycle")
        exclude(group = "androidx.activity")
        exclude(group = "androidx.savedstate")
    }
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.ui:ui-text")
    api("androidx.compose.ui:ui-unit")
    api("androidx.compose.foundation:foundation-layout")
    api("androidx.compose.foundation:foundation") { isTransitive = false }
    api("androidx.compose.runtime:runtime")
    testImplementation("junit:junit:4.13.2")
}

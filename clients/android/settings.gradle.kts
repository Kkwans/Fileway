pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "FilewayAndroid"
include(":app")
include(":remote-core")
project(":remote-core").projectDir = file("../shared/android/remote-core")
// Native source builds; other hosts consume the verified packaged AAR instead.
if (providers.gradleProperty("filewayFfmpegWorkDir").isPresent) include(":ffmpeg-probe")

include(":client-core")
project(":client-core").projectDir = file("../shared/android/client-core")
include(":media-core")
project(":media-core").projectDir = file("../shared/android/media-core")
include(":design-foundation")
project(":design-foundation").projectDir = file("../shared/android/design-foundation")

pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "FilewayAndroidTv"
include(":app")
include(":client-core")
project(":client-core").projectDir = file("../shared/android/client-core")
include(":media-core")
project(":media-core").projectDir = file("../shared/android/media-core")
include(":design-foundation")
project(":design-foundation").projectDir = file("../shared/android/design-foundation")

if (providers.gradleProperty("filewayTvFfmpegWorkDir").isPresent) {
    include(":tv-audio")
    project(":tv-audio").projectDir = file("../shared/android/audio-codecs")
}

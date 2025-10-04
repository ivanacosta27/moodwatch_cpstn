pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // required for be.tarsos.dsp:core:2.5
        maven("https://mvn.0110.be/releases")
    }
}

rootProject.name = "MoodWatch"
include(":app")

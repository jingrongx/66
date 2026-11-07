pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")   // lunar-java（农历/节气/节日本地精确推算）在 JitPack
    }
}

rootProject.name = "lvyoubao"
include(":app")

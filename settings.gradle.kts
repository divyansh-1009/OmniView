pluginManagement {
    resolutionStrategy {
        eachPlugin {
            // ObjectBox publishes its plugin under a non-standard artifact name, so
            // Gradle's default <id>:<id>.gradle.plugin:<version> lookup fails.
            // This maps the plugin ID to the real Maven coordinate on Maven Central.
            if (requested.id.id == "io.objectbox") {
                useModule("io.objectbox:objectbox-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OmniView"
include(":app")
 
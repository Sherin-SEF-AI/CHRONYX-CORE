pluginManagement {
    includeBuild("build-logic")
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
        // Foxglove MCAP Java writer + protobuf well-known schemas are published to Maven Central.
        // If your build environment is air-gapped, set `chronyx.mcap.internalWriter=true` in
        // gradle.properties to skip the external dependency and use the internal writer.
    }
}

rootProject.name = "chronyx"

include(":chronyx-core")
include(":chronyx-mcap")
include(":chronyx-service")
include(":harness-app")

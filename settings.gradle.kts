pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx\\..*")
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
        // OpenKeychain's OpenPGP API (com.github.open-keychain:openpgp-api)
        // is published on JitPack, not Maven Central. Failure to declare
        // this repo makes gradle throw "Could not resolve" for the library
        // the OpenPGP PR adds to gradle/libs.versions.toml.
        maven {
            url = uri("https://jitpack.io")
            // JitPack builds are slow and produce 404s for groups it doesn't
            // serve; restrict queries to JitPack's `com.github.<user>`
            // convention so unrelated resolution paths don't ping it.
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "3mail"
include(":app")

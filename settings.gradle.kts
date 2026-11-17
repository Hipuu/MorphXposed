@file:Suppress("UnstableApiUsage")

import java.util.Properties

pluginManagement {
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

val localProps = Properties().apply {
    val file = rootProject.projectDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun ghUser(): String? =
    localProps.getProperty("gpr.user")
        ?: providers.gradleProperty("gpr.user").orNull
        ?: System.getenv("GITHUB_ACTOR")

fun ghToken(): String? =
    localProps.getProperty("gpr.key")
        ?: providers.gradleProperty("gpr.key").orNull
        ?: System.getenv("GITHUB_TOKEN")
        ?: System.getenv("GH_TOKEN")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Morphe publishes its patcher and library here. GitHub Packages requires
        // authentication even for public artifacts, so credentials come from
        // local.properties, Gradle properties, or the environment.
        maven {
            name = "MorpheGitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = ghUser().orEmpty().ifBlank { "anonymous" }
                password = ghToken().orEmpty()
            }
            content {
                includeGroup("app.morphe")
            }
        }
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
            content {
                // morphe-patcher depends on a JitPack build of smali, which has no
                // official release. ARSCLib comes from a JitPack commit hash too.
                includeGroup("com.github.MorpheApp")
                includeGroup("com.github.MorpheApp.smali")
                includeGroupByRegex("com\\.github\\.MorpheApp.*")
                // morphe-library's Android artifact uses libsu for root installs.
                includeGroupByRegex("com\\.github\\.topjohnwu.*")
            }
            metadataSources {
                mavenPom()
                artifact()
            }
        }
    }
}

rootProject.name = "MorpheXposed"

include(":app")

// The LSPosed events module is built as its own APK. It is included only when the Xposed API
// jar is present, so a checkout without it still configures and builds the main app.
if (file("xposed/libs/api-82.jar").exists()) {
    include(":xposed")
}

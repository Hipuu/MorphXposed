plugins {
    alias(libs.plugins.android.application)
}

/**
 * The LSPosed events module.
 *
 * This is a **separate APK** on purpose:
 *  * An LSPosed module must be installable and activatable on its own.
 *  * It must not depend on morphe-patcher or its ~35 MB of transitive dependencies, because it
 *    is loaded inside `system_server`.
 *
 * Its only job is to observe package install/update/removal and forward a broadcast to the main
 * app. It inherits the Xposed API's own `AndroidManifest` restrictions, hence the standalone variant.
 */
android {
    namespace = "dev.morphxposed.events"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.morphxposed.events"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Same signing identity strategy as the main app: sign when a keystore exists, so
            // the module APK is installable straight from the build output.
            val keystoreFile = rootProject.file("keystore/release.jks")
            signingConfig = if (keystoreFile.exists()) {
                signingConfigs.create("release") {
                    storeFile = keystoreFile
                    storePassword = System.getenv("MORPHX_KEYSTORE_PASSWORD") ?: "morphxposed-release"
                    keyAlias = System.getenv("MORPHX_KEY_ALIAS") ?: "morphxposed"
                    keyPassword = System.getenv("MORPHX_KEY_PASSWORD") ?: "morphxposed-release"
                }
            } else {
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            // The Xposed API jar carries its own metadata that must not leak into the APK.
            excludes += setOf("/META-INF/DEPENDENCIES", "/META-INF/LICENSE", "/META-INF/NOTICE")
        }
    }
}

dependencies {
    // Compile-only: at runtime these classes are provided by LSPosed itself.
    // Bundling them would clash with the framework's copy.
    compileOnly(files("$projectDir/libs/api-82.jar"))

    // Plain JVM tests for the argument-parsing logic; it needs neither Xposed nor Android.
    testImplementation(libs.junit)
    // The module implements an Xposed interface, so the API is needed to reference the class.
    testCompileOnly(files("$projectDir/libs/api-82.jar"))
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9.x provides Kotlin support itself; the standalone kotlin-android
    // plugin must NOT be applied alongside it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.morphexposed"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.morphexposed"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Sign release builds from keystore/release.jks when it is present, so the APK is
            // directly installable. Without it the build still succeeds, producing an unsigned
            // APK for manual signing (see scripts/build-release.ps1).
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
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The patcher version this APK actually ships, exposed so the app can tell whether a patch
    // bundle requires a newer engine before it wastes a download and a failed run. Taken from the
    // same version catalog the dependency uses, so the two cannot drift apart.
    defaultConfig {
        buildConfigField(
            "String",
            "MORPHE_PATCHER_VERSION",
            "\"${libs.versions.morphePatcher.get()}\""
        )
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.kotlin_module",
                "/META-INF/*.version",
                "/kotlin-tooling-metadata.json",
                "/DebugProbesKt.bin",
                // Patcher transitive dependencies ship desktop JNI bindings
                // that are never loaded on Android.
                "/win32-x86/**",
                "/win32-x86-64/**",
                "/darwin/**",
                "/linux-x86-64/**",
                "/linux-aarch64/**"
            )
        }
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        // Patch bundles and APKs must stay byte-identical to what is read back.
        noCompress += listOf("mpp", "apk", "jar")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources to start real Android
            // components on the JVM.
            isIncludeAndroidResources = true
            // android.util.Log and similar stubs return defaults instead of throwing.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.documentfile)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Official Morphe patching engine + shared library
    implementation(libs.morphe.patcher)
    implementation(libs.morphe.library)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation(libs.junit)
    // Needed to drive viewModelScope deterministically under Robolectric.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Verifies the signatures the app produces, using the same library the patcher signs with.
    testImplementation(libs.apksig)
    // Local server for the bundle download and checksum-verification path.
    testImplementation(libs.okhttp.mockwebserver)
    // WorkManager scheduling, and Compose composition, both need their own test harnesses.
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

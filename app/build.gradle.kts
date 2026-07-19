import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load release signing credentials from keystore.properties (gitignored) if present.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.guard"
    // compileSdk 34 = Android 14. Required so we can declare the
    // foregroundServiceType="specialUse" + its permission (both API 34).
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guard"
        // minSdk 28 (Android 9): significant-motion, alarm audio attributes and
        // adaptive icons are all available; the target device (S23) is API 34.
        minSdk = 28
        targetSdk = 34
        // Shown in-app in the footer (MainActivity.versionLine), read back from the
        // installed package — so what the phone reports is what is actually installed.
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking: cuts the mostly-Compose payload dramatically. Safe
            // here — no reflection/serialization; manifest components are kept
            // automatically.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign the release build if credentials are available.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            // Strip metadata this app never uses: the Kotlin reflection builtins
            // (no kotlin-reflect here — only Java Class refs) and various build
            // marker files. Pure size trimming; nothing here is needed at runtime.
            excludes += setOf(
                "/kotlin/**",
                "**/*.kotlin_builtins",
                "kotlin-tooling-metadata.json",
                "META-INF/*.version",
                "META-INF/**/*.version",
                "META-INF/androidx/**",
                "DebugProbesKt.bin",
            )
        }
    }
}

dependencies {
    // No *shipped* dependencies at all — pure Android framework + Kotlin stdlib.
    // The UI is framework Views and notifications use the framework Notification
    // APIs directly, so nothing from AndroidX (and its transitive coroutines /
    // lifecycle / profileinstaller tree) is pulled in.

    // Test-only, JVM-only: never packaged into the APK. The tested classes
    // (MotionAnalyzer, WatchGate, GuardState, the sensitivity mappings) are
    // deliberately free of Android types, so plain JUnit is enough — no
    // Robolectric, no instrumentation, no emulator.
    testImplementation(libs.junit)
}

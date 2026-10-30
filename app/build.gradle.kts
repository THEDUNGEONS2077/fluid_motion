// app/build.gradle.kts: the fluid_motion app: live wallpapers (OpenGL ES 3) and a
// Compose settings screen in cyberdeck_terminal's design.
//
// Versions: versionName/versionCode here, and a matching CHANGELOG.md entry.
// Release signing reads keystore.properties (not in git); without it, release
// builds are unsigned and only debug builds install.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key's details, if present on this machine.
val keystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.thedungeons2077.fluidmotion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thedungeons2077.fluidmotion"
        // Android 8.1: live wallpapers with OpenGL ES 3 on practically every device in use (and the
        // wallpaper telling the system its colours itself, which needs 8.1).
        minSdk = 27
        targetSdk = 36
        versionCode = 18
        versionName = "0.13.1"
    }

    signingConfigs {
        if (keystore.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Smaller and faster: R8 shrinks the code, unused resources go.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // FrameAllocationTest weighs what a frame allocates: without escape analysis the desktop JIT can't
        // quietly remove an allocation the phone's compiler might still make.
        unitTests.all { it.jvmArgs("-XX:-DoEscapeAnalysis") }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Compose without Material: the look is cyberdeck_terminal's own (ui/Deck.kt, ui/Components.kt).
    val compose = "1.9.3"
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.runtime:runtime:$compose")
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    testImplementation("junit:junit:4.13.2")
}

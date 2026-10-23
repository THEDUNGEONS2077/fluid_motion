// build.gradle.kts (root): plugin versions, applied in app/build.gradle.kts.
// The same Android Gradle Plugin and Kotlin as quest_log (already in the Gradle cache).
plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

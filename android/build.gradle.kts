plugins {
    // AGP 9 builds Kotlin itself; only the Compose compiler plugin is applied separately.
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
}

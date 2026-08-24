// AGP 9 compiles Kotlin itself, so the Kotlin Android plugin is deliberately
// absent. The Compose compiler plugin is still needed -- see app/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

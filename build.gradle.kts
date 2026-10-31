plugins {
    alias(libs.plugins.android.application) apply false
    // Resolve the requested Kotlin version while retaining AGP's built-in Kotlin.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.koin.compiler) apply false
}

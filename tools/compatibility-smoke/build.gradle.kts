plugins {
    alias(libs.plugins.android.library) apply false
    // AGP's built-in Kotlin replaces kotlin-android. Resolve the requested KGP
    // through the classpath without applying a second Android Kotlin plugin.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.koin.compiler) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ksp) apply false
}

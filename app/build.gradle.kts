import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.koin.compiler)
}

android {
    namespace = "com.valhalla.tavernforge"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.valhalla.tavernforge"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.6.0-internal"
    }
    buildTypes {
        release {
            // AGP 9.3+ enables code/resource optimization and default Android keep rules together.
            optimization { enable = true }
        }
    }
    buildFeatures { compose = true }
    testOptions.unitTests.isIncludeAndroidResources = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9.5 realizes preview tasks for both variants during task discovery. Each preview
// requires its host-test resource APK, even when only debug JVM tests are requested.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
koinCompiler { userLogs = true }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":data"))
    implementation(project(":format"))
    implementation(libs.datastore.preferences)
    implementation(libs.exifinterface)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.adaptive.navigation3)
    implementation(libs.koin.core)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

dependencyLocking { lockAllConfigurations() }

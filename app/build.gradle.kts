plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.titanide.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.titanide.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Set by CI or a local gradle.properties override; never committed.
        val githubClientId = providers.gradleProperty("titan.github.clientId").orElse("")
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${githubClientId.get()}\"")
    }

    // Release signing reads from the environment or an untracked gradle.properties, so no
    // keystore or password ever lands in the repo. CI falls back to the debug key, which
    // produces an installable but non-publishable APK rather than failing the build.
    signingConfigs {
        create("release") {
            val storePath = providers.gradleProperty("titan.signing.storeFile")
                .orElse(providers.environmentVariable("TITAN_SIGNING_STORE_FILE"))
                .orNull
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("titan.signing.storePassword")
                    .orElse(providers.environmentVariable("TITAN_SIGNING_STORE_PASSWORD"))
                    .orNull
                keyAlias = providers.gradleProperty("titan.signing.keyAlias")
                    .orElse(providers.environmentVariable("TITAN_SIGNING_KEY_ALIAS"))
                    .orNull
                keyPassword = providers.gradleProperty("titan.signing.keyPassword")
                    .orElse(providers.environmentVariable("TITAN_SIGNING_KEY_PASSWORD"))
                    .orNull
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/INDEX.LIST",
            "META-INF/*.kotlin_module",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

// Robolectric needs the ui-test-manifest AAR's <activity> declaration, and that AAR
// is debug-only by design (shipping it in release would put test code in the APK).
// Without it every Compose render test fails on the release variant with
// "Unable to resolve activity", which says nothing about the app. The same test
// sources still run against debug, so release unit tests are turned off rather
// than left permanently red. Release correctness is covered by the R8 build and
// the APK assertions in the build workflow.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:editor"))
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":core:git"))
    implementation(project(":core:ai"))
    implementation(project(":core:opencode"))
    implementation(project(":core:workbench"))
    implementation(project(":core:templates"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

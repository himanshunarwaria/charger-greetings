import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself -- applying `org.jetbrains.kotlin.android`
    // here is not merely redundant, it is a hard error ("no longer required for
    // Kotlin support since AGP 9.0").
    //
    // The Compose *compiler* plugin is still required though: enabling
    // buildFeatures.compose without it fails configuration outright. So exactly
    // one Kotlin plugin is applied, and it is not the obvious one.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing is configured from a keystore.properties file that is NOT in
 * source control (see keystore.properties.template and android/README.md).
 *
 * If that file is absent the release build still succeeds -- it just produces
 * an unsigned artifact instead of failing the build. No key material, password
 * or path is ever hard-coded here.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasSigningConfig = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.chargergreetings.app"

    // 37, not 36: Compose BOM 2026.08.00 ships androidx.compose 1.12.x, whose
    // AAR metadata requires consumers to compile against API 37 or later.
    // compileSdk only controls which APIs are available at compile time -- it
    // is deliberately decoupled from targetSdk below.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chargergreetings.app"

        // API 24 (Android 7.0). Chosen because it is the oldest release where
        // the manifest-registered ACTION_POWER_CONNECTED path, AudioAttributes
        // and the Doze exemptions all behave the way this app depends on --
        // and it still covers effectively the whole installed base.
        minSdk = 24
        targetSdk = 36

        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // PropertyEscape false-positives on local.properties: it rejects a
        // value that is byte-identical to the fix it suggests, so no amount of
        // escaping satisfies it. That file is machine-local and gitignored, and
        // has no bearing on the app, so the check is downgraded to a warning
        // rather than disabled outright -- it stays visible in reports.
        warning += "PropertyEscape"

        // Everything else still fails the build, including on release.
        abortOnError = true
        checkReleaseBuilds = true
    }

    androidResources {
        // WAV assets are already small and must not be re-compressed: the
        // greeting has to start instantly, with no decode step.
        noCompress += "wav"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

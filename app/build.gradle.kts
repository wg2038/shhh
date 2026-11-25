import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { stream ->
        keystoreProperties.load(stream)
    }
}

val envKeystoreFile = System.getenv("SHHH_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val envKeystorePassword = System.getenv("SHHH_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val envKeyAlias = System.getenv("SHHH_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val envKeyPassword = System.getenv("SHHH_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val hasEnvReleaseSigning = envKeystoreFile != null &&
    rootProject.file(envKeystoreFile).exists() &&
    envKeystorePassword != null &&
    envKeyAlias != null &&
    envKeyPassword != null

val hasReleaseSigning = keystorePropertiesFile.exists() || hasEnvReleaseSigning

android {
    namespace = "io.github.wg2038.shhh"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wg2038.shhh"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        // Work around lifecycle 2.8.0 / Compose 1.7 lint detector crashes under Kotlin 2.0.
        disable += setOf(
            "AutoboxingStateCreation",
            "FrequentlyChangingValue",
            "NullSafeMutableLiveData",
            "RememberInComposition",
        )
    }

    // Read release signing config from keystore.properties or environment variables; fall back to debug key.
    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            } else if (hasEnvReleaseSigning) {
                storeFile = rootProject.file(envKeystoreFile!!)
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("com.materialkolor:material-kolor:2.0.0")
    testImplementation("junit:junit:4.13.2")
}

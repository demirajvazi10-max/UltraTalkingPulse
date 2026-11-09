import java.util.Properties

plugins {
    id("com.android.application")
}

// The Presage API key is read from local.properties (never committed) or from the
// SMARTSPECTRA_API_KEY environment variable, and baked into BuildConfig.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val presageApiKey: String = (
    localProps.getProperty("presage.apiKey")
        ?: providers.environmentVariable("SMARTSPECTRA_API_KEY").orNull
        ?: ""
    ).trim()

fun String.asBuildConfigString(): String = replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.demirajvazi.ultratalkingpulse"
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.demirajvazi.ultratalkingpulse"
        minSdk = 28
        targetSdk = 36
        // the GitHub workflow passes the version of the tag (-PappVersion=1.0.0 -PappVersionCode=12)
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersion") as String?) ?: "1.0.0"
        buildConfigField("String", "PRESAGE_API_KEY", "\"${presageApiKey.asBuildConfigString()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.camera:camera-view:1.6.0")

    implementation("com.presagetech:smartspectra:3.4.0")
}

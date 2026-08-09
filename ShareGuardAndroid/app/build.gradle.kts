import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun buildConfigString(name: String): String {
    val value = localProperties.getProperty(name, "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$value\""
}

android {
    namespace = "com.shareguard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shareguard.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", buildConfigString("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString("SUPABASE_PUBLISHABLE_KEY"))
        buildConfigField("String", "PORTONE_STORE_ID", buildConfigString("PORTONE_STORE_ID"))
        buildConfigField("String", "PORTONE_CHANNEL_KEY", buildConfigString("PORTONE_CHANNEL_KEY"))
        buildConfigField("String", "PASS_VERIFY_FUNCTION", "\"verify-pass\"")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.github.portone-io:android-sdk:0.2.3")
}

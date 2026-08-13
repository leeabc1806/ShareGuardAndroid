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
    compileSdk = 33

    defaultConfig {
        applicationId = "com.shareguard.app"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", buildConfigString("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString("SUPABASE_PUBLISHABLE_KEY"))
        buildConfigField("String", "TOSS_CLIENT_KEY", buildConfigString("TOSS_CLIENT_KEY"))
        buildConfigField("String", "TOSS_CONFIRM_URL", buildConfigString("TOSS_CONFIRM_URL"))
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.5.0")
    implementation("com.github.tosspayments:payment-sdk-android:0.1.22")
}

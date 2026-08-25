import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

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
        versionCode = 3
        versionName = "1.2"

        buildConfigField("String", "SUPABASE_URL", buildConfigString("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString("SUPABASE_PUBLISHABLE_KEY"))
        buildConfigField("String", "TOSS_CLIENT_KEY", buildConfigString("TOSS_CLIENT_KEY"))
        buildConfigField("String", "TOSS_CONFIRM_URL", buildConfigString("TOSS_CONFIRM_URL"))
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.5.0")
    implementation("com.github.tosspayments:payment-sdk-android:0.1.22")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

// Some Windows workspace folders mark generated directories as read-only.
// Normalize existing outputs once per build so incremental DEX tasks can replace them.
val normalizedWindowsBuildOutputs = AtomicBoolean(false)

tasks.configureEach {
    doFirst {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ||
            !normalizedWindowsBuildOutputs.compareAndSet(false, true)
        ) {
            return@doFirst
        }

        val buildOutput = layout.buildDirectory.get().asFile.toPath()
        if (!Files.exists(buildOutput)) {
            return@doFirst
        }

        Files.walk(buildOutput).use { paths ->
            paths.filter(Files::isDirectory).forEach { path ->
                Files.setAttribute(path, "dos:readonly", false)
            }
        }
    }
}

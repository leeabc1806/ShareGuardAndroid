import java.nio.file.Files

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

group = "com.shareguard"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// This Windows workspace can mark generated paths as read-only. A separate prerequisite
// runs before Gradle's own stale-output cleanup, which happens before normal task actions.
val normalizeWindowsBuildOutputs = tasks.register("normalizeWindowsBuildOutputs") {
    doLast {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@doLast
        val buildOutput = layout.buildDirectory.get().asFile.toPath()
        if (!Files.exists(buildOutput)) return@doLast
        Files.walk(buildOutput).use { paths ->
            paths.forEach { path ->
                Files.setAttribute(path, "dos:readonly", false)
            }
        }
    }
}

tasks.configureEach {
    if (name != normalizeWindowsBuildOutputs.name) dependsOn(normalizeWindowsBuildOutputs)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    api(libs.okhttp)
    testImplementation(libs.junit)
}

tasks.test {
    // Integrationstestene mod Radicale kører kun, når NOTES_TEST_URL er sat
    inputs.property("notesTestUrl", providers.environmentVariable("NOTES_TEST_URL").orElse(""))
    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

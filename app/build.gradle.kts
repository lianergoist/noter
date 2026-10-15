plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dk.rpix.noter"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dk.rpix.noter"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release-signering læses fra ~/.gradle/gradle.properties (aldrig fra repoet).
    // Mangler egenskaberne, bygges release usigneret.
    val keystore = providers.gradleProperty("NOTER_KEYSTORE").orNull?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.gradleProperty("NOTER_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("NOTER_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("NOTER_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            // Eget pakkenavn, så debug og release kan ligge på samme telefon
            applicationIdSuffix = ".debug"
        }
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Ingen afhængighedsblok krypteret med Googles nøgle i APK'en (F-Droid afviser den)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    androidResources {
        // Sprogvalg pr. app i systemindstillingerne (Android 13+); standardsproget står i res/resources.properties
        generateLocaleConfig = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

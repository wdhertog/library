plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "nl.wdhertog.library"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.wdhertog.library"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.1"

        // Alleen ARM (alle gangbare telefoons); x86 is voor emulators en scheelt ~35 MB aan ML Kit-bibliotheken.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Releasesleutel staat buiten de repo; zet de gegevens in ~/.gradle/gradle.properties.
    // Alle releases moeten met dezelfde sleutel, anders installeert een update niet over de vorige heen.
    val releaseStore = providers.gradleProperty("libraryReleaseStoreFile").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("libraryReleaseStorePassword").get()
                keyAlias = providers.gradleProperty("libraryReleaseKeyAlias").get()
                keyPassword = providers.gradleProperty("libraryReleaseKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Releasecode (met R8) naast de gewone app op een toestel testen.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
}

kotlin {
    jvmToolchain(21)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

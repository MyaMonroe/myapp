import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val akujiSigningFile = rootProject.file(".signing/signing.properties")
val akujiSigning = Properties().apply {
    if (akujiSigningFile.isFile) {
        akujiSigningFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.deffrow.akuji"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deffrow.akuji"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.0"
    }

    signingConfigs {
        create("akujiRelease") {
            if (akujiSigningFile.isFile) {
                storeFile = rootProject.file(".signing/${akujiSigning.getProperty("storeFile")}")
                storePassword = akujiSigning.getProperty("storePassword")
                keyAlias = akujiSigning.getProperty("keyAlias")
                keyPassword = akujiSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (akujiSigningFile.isFile) {
                signingConfig = signingConfigs.getByName("akujiRelease")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file(".qwen/skills"))
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")

    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

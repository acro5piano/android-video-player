plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.acro5piano.futsalplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.acro5piano.futsalplayer"
        minSdk = 26
        targetSdk = 36
        // CI passes these on release; local builds fall back to the defaults.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "1.0.0"
    }

    signingConfigs {
        // Personal app: the keystore is committed so CI and local builds produce update-compatible APKs.
        create("release") {
            storeFile = file("release.jks")
            storePassword = "futsalplayer"
            keyAlias = "futsalplayer"
            keyPassword = "futsalplayer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
}

plugins {
    id("com.android.application")
}

android {
    namespace = "net.mixalich7b.totp"

    compileSdk = 37

    defaultConfig {
        applicationId = "net.mixalich7b.totp"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    val releaseStoreFile = providers.gradleProperty("TOTP_RELEASE_STORE_FILE").orNull
    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("TOTP_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("TOTP_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("TOTP_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sportprognoz.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sportprognoz.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.2"
    }

    // Постоянный ключ: каждая новая сборка ставится поверх старой без удаления
    signingConfigs {
        getByName("debug") {
            storeFile = file("release.keystore")
            storePassword = "sportprognoz"
            keyAlias = "sportprognoz"
            keyPassword = "sportprognoz"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

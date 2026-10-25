plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ronco.mobi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ronco.mobi"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Android Auto: telas do carro (velocimetro em superficie + menus com botoes)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-common:2.6.2")
    implementation("androidx.core:core:1.12.0")
}

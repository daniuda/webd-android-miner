plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.webdollar.miner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webdollar.miner"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        viewBinding = true
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
    implementation(libs.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Socket.IO v1.x = compatibil cu socket.io server 2.x (EIO v3)
    implementation("io.socket:socket.io-client:1.0.1") {
        exclude(group = "org.json", module = "json")
        exclude(group = "com.squareup.okhttp3")
    }
    // ed25519 semnare PoS
    implementation("net.i2p.crypto:eddsa:0.3.0")
    // Argon2d pentru PoW hashing
    implementation("de.mkammerer:argon2-jvm:2.11")

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit.ext)
}

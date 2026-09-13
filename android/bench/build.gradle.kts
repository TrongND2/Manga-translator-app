plugins {
    // AGP 9.0+ da tich hop san Kotlin.
    // Plugin org.jetbrains.kotlin.android KHONG con can va se lam build that bai.
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.mangatrans.bench"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mangatrans.bench"
        // minSdk 29 = Android 10, theo Stack cua spine.
        // LiteRT-LM khong cong bo minSdk -> day la [ASSUMPTION], build se noi cho biet neu sai.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-bench"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].kotlin.directories.add("src/main/kotlin")

    // Thu vien native cua LiteRT-LM chi co cho mot so ABI.
    // May ao la x86_64, Galaxy M52 la arm64-v8a -> can ca hai.
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
}

plugins {
    // AGP 9.0+ da tich hop san Kotlin. Them org.jetbrains.kotlin.android lam build hong.
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.mangatrans"
    compileSdk = 36

    // `android.util.Log` la stub nem RuntimeException trong test JVM. Day
    // chuyen co ghi log hinh hoc (`Pipeline`, `TranslateFilter`), nen thieu
    // dong nay la 7 test cua TranslateFilter do vi mot cau `Log.i`, khong phai
    // vi logic sai.
    testOptions { unitTests.isReturnDefaultValues = true }

    defaultConfig {
        applicationId = "app.mangatrans"
        minSdk = 29          // Android 10
        targetSdk = 36
        // `versionCode` la so ma AD-15 doi chieu voi khoang tuong thich khai
        // trong `package.json`. Nang no moi lan phat hanh, ke ca ban vá.
        versionCode = 15
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].kotlin.directories.add("src/main/kotlin")
    sourceSets["test"].kotlin.directories.add("src/test/kotlin")

    packaging { jniLibs { useLegacyPackaging = false } }

    /**
     * NFR-008: APK <= 100 MB.
     *
     * Native lib cua LiteRT-LM + ONNX Runtime rat nang. Neu gop ca 4 ABI thi
     * APK = 128.3 MB (arm64 38.4 + x86_64 45.0 + armeabi-v7a 12.5 + x86 20.1).
     *
     * Dien thoai that deu la arm64-v8a; x86_64 chi dung cho MAY AO.
     * 32-bit khong du RAM cho model 2.6 GB nen bo han.
     *
     * Tach APK theo ABI: moi ban chi mang lib cua chinh no.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")   // may that + may ao
            isUniversalApk = false
        }
    }

    // Khong dat them `ndk.abiFilters`: AGP cam dung dong thoi voi `splits.abi`.
    // Chi rieng `splits` da du — no vua tach APK vua loai ABI khong liet ke.
    // 32-bit bi loai vi khong du RAM cho model 2.6 GB (da do: RSS ~3.2 GB).
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

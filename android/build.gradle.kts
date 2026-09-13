// AGP 9.x di kem Kotlin 2.2.x, nhung LiteRT-LM 0.17.0 bien dich bang Kotlin 2.4.0.
// Kotlin 2.2 tu choi doc metadata 2.4 => phai nang KGP o day.
// KHONG dung -Xskip-metadata-version-check: no giau van de va de loi luc chay.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}

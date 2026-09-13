pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // LiteRT-LM nam tren repo Maven cua Google, KHONG co tren Maven Central.
        google()
        mavenCentral()
    }
}

rootProject.name = "mangatrans"
include(":app")     // app that
include(":bench")   // app do luong — dieu kien D2 bat moi story phai do lai tren M52

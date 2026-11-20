plugins {
    id("com.android.application")
}

android {
    namespace = "com.lethargicrunner.grokbrowserinspector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lethargicrunner.grokbrowserinspector"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
}

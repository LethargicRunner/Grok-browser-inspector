plugins {
    id("com.android.application")
}

android {
    namespace = "com.lethargicrunner.grokbrowserinspector"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.lethargicrunner.grokbrowserinspector"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"

        buildConfigField(
            "String",
            "GIT_COMMIT",
            "\"${System.getenv("GITHUB_SHA")?.take(12) ?: "local"}\""
        )
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

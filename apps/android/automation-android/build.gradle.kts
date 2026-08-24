plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.veyarc.automation.android"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(project(":core-engine"))
    implementation(project(":core-model"))
    implementation(libs.shizuku.api)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "com.veyarc.ui"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig { minSdk = libs.versions.min.sdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":core-model"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
}

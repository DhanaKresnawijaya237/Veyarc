import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

data class ReleaseSigningValues(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

val releaseStoreFileEnvironment = "VEYARC_RELEASE_STORE_FILE"
val releaseStorePasswordEnvironment = "VEYARC_RELEASE_STORE_PASSWORD"
val releaseKeyAliasEnvironment = "VEYARC_RELEASE_KEY_ALIAS"
val releaseKeyPasswordEnvironment = "VEYARC_RELEASE_KEY_PASSWORD"

val releaseAggregateTaskNames =
    setOf(
        "assemble",
        "build",
        "buildDependents",
        "buildNeeded",
        "bundle",
        "verifyReleaseSigning",
    )
val releaseArtifactTaskNames =
    setOf(
        "assembleRelease",
        "bundleRelease",
        "extractApksForRelease",
        "extractApksFromBundleForRelease",
        "installRelease",
        "makeApkFromBundleForRelease",
        "packageRelease",
        "packageReleaseBundle",
        "packageReleaseUniversalApk",
        "signReleaseBundle",
        "zipApksForRelease",
    )
val releasePackagingRequested =
    gradle.startParameter.taskNames.any { requestedTask ->
        val taskName = requestedTask.substringAfterLast(':')
        taskName in releaseAggregateTaskNames || taskName in releaseArtifactTaskNames
    }

@Suppress("DEPRECATION")
val releaseSigningValues =
    if (releasePackagingRequested) {
        if (gradle.startParameter.isConfigurationCacheRequested || gradle.startParameter.isBuildCacheEnabled) {
            throw GradleException(
                "Signed release builds must use --no-configuration-cache --no-build-cache so signing secrets are not retained in Gradle caches.",
            )
        }

        val environmentNames =
            listOf(
                releaseStoreFileEnvironment,
                releaseStorePasswordEnvironment,
                releaseKeyAliasEnvironment,
                releaseKeyPasswordEnvironment,
            )
        val values =
            environmentNames.associateWith { name ->
                providers.environmentVariable(name).orNull.orEmpty()
            }
        val missing = environmentNames.filter { name -> values.getValue(name).isBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Signed release build is missing required environment variables: ${missing.joinToString()}.",
            )
        }

        val configuredStoreFile = File(values.getValue(releaseStoreFileEnvironment))
        if (!configuredStoreFile.isAbsolute) {
            throw GradleException("$releaseStoreFileEnvironment must be an absolute path outside the repository.")
        }
        val canonicalStoreFile = configuredStoreFile.canonicalFile
        val repositoryRoot = rootProject.projectDir.resolve("../..").canonicalFile
        if (canonicalStoreFile.toPath().startsWith(repositoryRoot.toPath())) {
            throw GradleException("$releaseStoreFileEnvironment must point outside the repository.")
        }
        if (!canonicalStoreFile.isFile || !canonicalStoreFile.canRead()) {
            throw GradleException("$releaseStoreFileEnvironment does not point to a readable keystore file.")
        }

        ReleaseSigningValues(
            storeFile = canonicalStoreFile,
            storePassword = values.getValue(releaseStorePasswordEnvironment),
            keyAlias = values.getValue(releaseKeyAliasEnvironment),
            keyPassword = values.getValue(releaseKeyPasswordEnvironment),
        )
    } else {
        null
    }

android {
    namespace = "com.veyarc.app"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "com.veyarc.app"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.compile.sdk.get().toInt()
        versionCode = 3
        versionName = "1.1.0"
    }

    signingConfigs {
        releaseSigningValues?.let { values ->
            create("externalRelease") {
                storeFile = values.storeFile
                storePassword = values.storePassword
                keyAlias = values.keyAlias
                keyPassword = values.keyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            versionNameSuffix = "-dev"
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            releaseSigningValues?.let {
                signingConfig = signingConfigs.getByName("externalRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main").assets.directories.add("../../../docs")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val verifyReleaseSigning =
    tasks.register("verifyReleaseSigning") {
        group = "verification"
        description = "Fails unless the release signing key is externally supplied and safe to use."
        doLast {
            check(releaseSigningValues != null) {
                "Release signing configuration was not loaded."
            }
        }
    }

tasks.configureEach {
    if (name in releaseArtifactTaskNames) {
        dependsOn(verifyReleaseSigning)
        doFirst {
            check(releaseSigningValues != null) {
                "Refusing to create an unsigned release artifact. Use the documented signed-release command."
            }
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(project(":feature-assistant"))
    implementation(project(":automation-android"))
    implementation(project(":data"))
    implementation(project(":core-model"))
    implementation(project(":core-ui"))
    implementation(project(":core-engine"))
    implementation(project(":feature-editor"))
    implementation(project(":feature-recorder"))
    implementation(project(":feature-runner"))

    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.provider)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

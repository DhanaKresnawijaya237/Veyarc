import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable"),
        )
    }
    kotlinGradle {
        target("build.gradle.kts", "settings.gradle.kts", "*/build.gradle.kts")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            mapOf("ktlint_standard_chain-method-continuation" to "disabled"),
        )
    }
}

val junit4 = libs.junit4
val androidTestRunner = libs.androidx.test.runner
val androidTestJunit = libs.androidx.test.ext.junit
val espressoCore = libs.androidx.test.espresso.core
val androidProjectPaths =
    listOf(
        ":app",
        ":automation-android",
        ":data",
        ":feature-assistant",
        ":feature-editor",
        ":feature-recorder",
        ":feature-runner",
    )

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        dependencies.add("testImplementation", junit4)
    }

    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            lint {
                abortOnError = true
                checkReleaseBuilds = true
            }
        }
        dependencies.add("testImplementation", junit4)
        dependencies.add("androidTestImplementation", androidTestRunner)
        dependencies.add("androidTestImplementation", androidTestJunit)
        dependencies.add("androidTestImplementation", espressoCore)
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            lint {
                abortOnError = true
                checkReleaseBuilds = true
            }
        }
        dependencies.add("testImplementation", junit4)
        dependencies.add("androidTestImplementation", androidTestRunner)
        dependencies.add("androidTestImplementation", androidTestJunit)
        dependencies.add("androidTestImplementation", espressoCore)
    }
}

tasks.register("verifyLocal") {
    group = "verification"
    description = "Runs formatting, Android lint, and local JVM tests."
    dependsOn("spotlessCheck")
    dependsOn(subprojects.map { "${it.path}:test" })
    dependsOn(androidProjectPaths.map { "$it:lint" })
}

tasks.register("verifyDevice") {
    group = "verification"
    description = "Runs Android instrumentation tests on connected devices."
    dependsOn(androidProjectPaths.map { "$it:connectedAndroidTest" })
}

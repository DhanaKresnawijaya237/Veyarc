plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.veyarc.feature.assistant"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

abstract class AssistantSchemaTask : DefaultTask() {
    @get:InputFile abstract val schema: RegularFileProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun copySchema() {
        val destination = outputDirectory.file("workflow.schema.json").get().asFile
        destination.parentFile.mkdirs()
        schema.get().asFile.copyTo(destination, overwrite = true)
    }
}
androidComponents.onVariants { variant ->
    val schemaTask = tasks.register<AssistantSchemaTask>("generate${variant.name.replaceFirstChar(Char::uppercaseChar)}AssistantSchema") {
        schema.set(rootProject.layout.projectDirectory.file("../../spec/workflows/v1/workflow.schema.json"))
        outputDirectory.set(layout.buildDirectory.dir("generated/assistantSchema/${variant.name}"))
    }
    variant.sources.resources!!.addGeneratedSourceDirectory(schemaTask, AssistantSchemaTask::outputDirectory)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(composeBom)
    implementation(project(":data"))
    implementation(project(":core-model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation("org.commonmark:commonmark:0.30.0")
}

val assistantRuntimes = provider { listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").map(configurations::getByName) }
val assistantSources = fileTree("src/main/kotlin") { include("**/*.kt") }
tasks.register("verifyAssistantIsolation") {
    group = "verification"
    inputs.files(assistantSources)
    inputs.property(
        "projectPaths",
        assistantRuntimes.map { configurations ->
            configurations.flatMap { configuration ->
                configuration.incoming.resolutionResult.allComponents.mapNotNull {
                    (it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath
                }
            }
        },
    )
    doLast {
        val forbidden = setOf(":app", ":automation-android", ":feature-editor", ":feature-recorder", ":feature-runner", ":core-engine")
        val projects = inputs.properties.getValue("projectPaths") as List<*>
        check(projects.none { it in forbidden }) { "Assistant must not depend on execution modules: $projects" }
        inputs.files.files.forEach { source ->
            check(!Regex("com\\.veyarc\\.(engine|automation|feature\\.(editor|recorder|runner))\\.").containsMatchIn(source.readText())) {
                "Assistant source imports an execution module: ${source.name}"
            }
        }
    }
}

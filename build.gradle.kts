import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.conflictcourt"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

intellij {
    version.set("2024.1")
    type.set("IC")
    sandboxDir.set("${System.getProperty("java.io.tmpdir")}/conflictcourt-idea-sandbox")
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("241.*")
    }

    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    runIde {
        jvmArgs("-Xmx2g")
    }

    register<JavaExec>("runMainTestRunner") {
        group = "application"
        description = "Runs the ConflictCourt AI MainTestRunner from the terminal."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("com.conflictcourt.ai.MainTestRunnerKt")
    }

    register<JavaExec>("runBatchAiTestRunner") {
        group = "application"
        description = "Runs batch AI integration tests across JSON fixtures."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("com.conflictcourt.ai.BatchAiTestRunnerKt")
    }
}

kotlin {
    jvmToolchain(17)
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.conflictcourt"
version = "0.1.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2024.1")
    type.set("IC")
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
}

kotlin {
    jvmToolchain(17)
}

import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Sin tope superior: el plugin se instala from-disk en IDEs futuros sin re-empaquetar
            untilBuild = provider { null }
        }
    }
}

dependencies {
    // HTTP: java.net.http.HttpClient from the JDK — no extra HTTP dependency.
    // Coroutines are provided by the IntelliJ Platform classpath, so they are not declared here.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        // Git integration: read the project's git remotes to resolve the GitLab project.
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
}

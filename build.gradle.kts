import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

intellijPlatform {
    pluginConfiguration {
        // Marketplace listing description. Single source of truth: the section of README.md
        // between the "<!-- Plugin description -->" markers, injected verbatim into the plugin.xml
        // <description> at build time. The section is authored directly in the Marketplace-allowed
        // HTML subset (p, ul, li, b), so no Markdown-to-HTML conversion is applied here.
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md between '$start' and '$end'")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n")
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Sin tope superior: el plugin se instala from-disk en IDEs futuros sin re-empaquetar
            untilBuild = provider { null }
        }

        // "What's New" del Marketplace: la sección de CHANGELOG.md de la versión publicada
        // (fallback a Unreleased mientras esa sección no exista). Hasta 0.12.0 las notas se
        // ponían a mano en el Marketplace tras publicar; desde 0.12.1 salen del changelog.
        // Evaluado eager (String, no provider): un lambda capturaría la extensión changelog,
        // que arrastra Project y rompe la configuration cache activada en gradle.properties.
        changeNotes = with(changelog) {
            renderItem(
                (getOrNull(project.version.toString()) ?: getUnreleased())
                    .withHeader(false)
                    .withEmptySections(false),
                Changelog.OutputType.HTML,
            )
        }
    }

    // Firma del plugin: exigida por el Marketplace para publicar vía publishPlugin (release.yml).
    // Los tres valores llegan como secrets del repo; en local estas env no existen y la firma
    // simplemente no se ejecuta (buildPlugin no la necesita).
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Publicación en el Marketplace (canal Stable por defecto), disparada por release.yml.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

dependencies {
    // HTTP: java.net.http.HttpClient from the JDK — no extra HTTP dependency.
    // Coroutines are provided by the IntelliJ Platform classpath, so they are not declared here.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // Markdown → HTML rendering for the MR description (pure JetBrains parser, no Swing).
    // compileOnly: the IntelliJ Platform bundles this exact library (lib/lib-client.jar), so it is
    // provided at runtime — declaring it as a full dependency would package a copy of the IDE's own
    // classes into the plugin ZIP (Marketplace verification warning). Tests run outside the IDE, so
    // they need it on the test classpath explicitly.
    compileOnly("org.jetbrains:markdown:0.7.3")
    testImplementation("org.jetbrains:markdown:0.7.3")

    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        // Git integration: read the project's git remotes to resolve the GitLab project.
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
}

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Qué es

Plugin IntelliJ (Kotlin, JVM 21) para gestionar Merge Requests de GitLab sin salir del IDE: lista y detalle de MRs, review de diff con comentarios inline, merge, y pipelines con logs ANSI en vivo. Publicado en el Marketplace como **"Cockpit for GitLab"** (id 32942). Los tickets se referencian como `GLC-NN` (Linear) tanto en commits como en KDoc — el código ata cada decisión no obvia a su ticket de origen; mantén esa convención.

## Comandos

```bash
./gradlew test                 # Suite de unit tests (JUnit4)
./gradlew test --tests "dev.jota.gitlabcockpit.core.PipelineModelTest"   # Un solo test class
./gradlew check                # Lo que ejecuta el CI (tests + verificaciones)
./gradlew runIde               # IDE sandbox con el plugin instalado
./gradlew buildPlugin          # ZIP instalable en build/distributions/
./gradlew verifyPlugin         # Plugin Verifier (el mismo del Marketplace)
```

Requiere JDK 21+ (el CI usa Zulu 21). Para re-ejecutar la suite sin cambios usa `--rerun`.

> **Gotcha (build cache)**: tras cambiar la firma de una data class (p. ej. añadir un campo con default a un DTO), la build-cache de Kotlin puede servir bytecode de tests obsoleto y fallar con `NoSuchMethodError`. Se sanea con `./gradlew clean build --rerun-tasks`.

## Arquitectura

Cuatro paquetes bajo `src/main/kotlin/dev/jota/gitlabcockpit/`, con dependencias en una sola dirección (sin ciclos):

```
ui/  ──▶  core/  ──▶  api/
 │          │
 └───▶ settings/   (ui y core leen config/token)
```

- **`api/`** — `GitLabApiClient.kt` concentra los DTOs (`@Serializable`, con defaults tolerantes + `ignoreUnknownKeys` para que payloads futuros de GitLab no rompan) y todos los métodos HTTP (REST v4, `java.net.http.HttpClient`, auth `PRIVATE-TOKEN`). No conoce nada de la plataforma IntelliJ. Toda llamada devuelve `GitLabResult<T>` (`Success`/`HttpError`/`NetworkError`) — nunca excepciones sin envolver; el resto del código hace `when` exhaustivo sobre ese sealed class.
- **`core/`** — dominio sin Swing. `CockpitProjectService` (servicio de proyecto) es el único punto que instancia `GitLabApiClient` y expone el estado `CockpitState` (`NotConfigured`/`NoGitLabRemote`/`Loading`/`Ready`/`Error`) que la UI se limita a renderizar. Aquí viven la resolución del proyecto GitLab desde los remotes git (`GitLabProjectResolver`), los modelos de presentación puros (`PipelineModel`, `MrTimeline`…), los filtros, las utilidades de diff (`DiffAnchor`, `DiffLineMap`) y el subsistema de notificaciones (`MrNotificationsWatcher` compara snapshots entre pasadas; `BackgroundNotificationsPoller` lo ejecuta con el tool window cerrado). La lógica no trivial se extrae a funciones puras top-level a propósito, para testearla sin fixtures de IDE — es el patrón dominante.
- **`settings/`** — `GitLabCockpitSettings` (`PersistentStateComponent` de aplicación) y `TokenStore` (el PAT va a `PasswordSafe`, nunca al XML).
- **`ui/`** — toda la Swing/IntelliJ UI: tool window (`CockpitToolWindowPanel`), `MrDetailPanel.kt` (el fichero más grande y de mayor superficie de cambio), `PipelinesPanel`, render de Markdown y avatares. `ui/diff/` engancha los comentarios al editor de diff nativo vía `DiffExtension`; `ui/log/` abre los logs de job como pestañas de editor virtual (`JobLogVirtualFile` + providers).

### Convenciones que el código asume

- **Threading**: red/IO en coroutines sobre el `coroutineScope` de `CockpitProjectService`; toda actualización de UI hace `withContext(Dispatchers.EDT)` explícito. En diálogos modales, `Dispatchers.EDT + modality.asContextElement()` (sin ello la plataforma difiere la reanudación mientras el modal está abierto).
- **i18n**: ningún string de usuario hardcodeado — todo vía `CockpitBundle.message(...)` contra `messages/CockpitBundle.properties`.
- **Notificaciones**: dos `notificationGroup` registrados ("Cockpit for GitLab" BALLOON y "… (Sticky)" STICKY_BALLOON) porque el display type de un grupo es fijo al registrarse; se elige en runtime según el toggle de settings.
- **Dependencias**: la única dependencia empaquetada es `kotlinx-serialization-json`. Las coroutines las provee la plataforma (no declararlas). `org.jetbrains:markdown` es `compileOnly` deliberadamente: la plataforma la bundlea y empaquetarla duplicaría clases del IDE (warning del verifier); ojo con el skew de versiones entre la 0.7.3 de compile y las bundled (0.7.2 en 2025.2–2026.1, 0.7.7 en 2026.2+) — ver el comentario en `MarkdownRenderer.kt` antes de tocar APIs de esa librería.
- Depende del plugin bundled **Git4Idea** (resolver remotes/checkout); `sinceBuild = 252` sin `untilBuild`.

## Tests

JUnit4 por composición manual — sin MockK/Mockito ni fixtures de IDE (`BasePlatformTestCase` no se usa). Dos patrones:

- **`core/` y helpers de `ui/`**: tests de funciones puras, un `*Test.kt` por unidad de lógica.
- **`api/`**: fake de servidor HTTP **real** (`com.sun.net.httpserver.HttpServer` en puerto efímero) con aserciones sobre requests/respuestas reales, incluida la cabecera de auth y el mapeo a `GitLabResult`.

Un cambio en un DTO o endpoint empieza en `GitLabApiClient.kt` y su test correspondiente; un cambio de comportamiento visible suele tener su lógica en `core/` (testeable pura) y solo el cableado en `ui/`.

## Release

- La versión vive en `gradle.properties` (`version=`); los cambios se anotan en `CHANGELOG.md` bajo `[Unreleased]` (Keep a Changelog). Las change-notes del Marketplace se renderizan automáticamente desde el changelog en build time, y la descripción del listing se inyecta desde el bloque `<!-- Plugin description -->` de `README.md` — no editar `plugin.xml` para nada de eso.
- **Push a `main`** → `build.yml`: `buildPlugin` + `check` + `verifyPlugin` y crea una **draft release** en GitHub (borrando drafts anteriores) con las notas de `[Unreleased]`.
- **Publicar esa release** → `release.yml`: `publishPlugin` firmado contra el Marketplace (secrets `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`) y sube el ZIP como asset.

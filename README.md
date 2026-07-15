# Cockpit for GitLab

<!-- Plugin description -->
<p>Run the full GitLab merge request review cycle without leaving your IDE. Cockpit for GitLab adds a dedicated tool window where you browse, review and merge your merge requests, and follow their pipelines from first job to green.</p>
<ul>
  <li><b>Browse and filter</b> merge requests by role (author, reviewer, reviewer not yet approved) or by user, across a single project, every project of the instance, and multi-root repositories.</li>
  <li><b>Review in depth</b>: discussion threads with replies, draft reviews with bulk submit, and inline comments anchored to the diff, right inside the editor.</li>
  <li><b>Merge</b> with a status-aware button that knows when the request is mergeable, offers "merge when the pipeline succeeds", and shows the blocking reason otherwise.</li>
  <li><b>Pipelines</b> for each merge request: stages, jobs, retry, cancel and play actions, and live logs with ANSI colors streamed as the job runs.</li>
  <li><b>Images and attachments</b> from descriptions and comments are fetched with your token and rendered inline.</li>
  <li><b>Configurable notifications</b>, including background polling with the tool window closed and a per-merge-request watch toggle.</li>
</ul>
<p>Setup: open <b>Settings &gt; Tools &gt; Cockpit for GitLab</b>, add a personal access token with the <b>api</b> scope, and point it at your instance. Works with self-managed GitLab 17+ and gitlab.com.</p>
<!-- Plugin description end -->

Plugin para IDEs IntelliJ que permite gestionar Merge Requests de GitLab sin salir del IDE:
editar revisores y asignado, comentar y revisar diffs. Integra además los pipelines de la MR
en el propio IDE, con logs en vivo y retry de jobs.

## Estado

En desarrollo, Fase 1. Ver [docs/requisitos-v1.md](docs/requisitos-v1.md).

## Desarrollo

```bash
./gradlew runIde       # Levanta un IDE sandbox con el plugin instalado
./gradlew buildPlugin  # Genera el ZIP instalable del plugin
```

> **Gotcha conocido (build cache):** tras cambiar la firma de una data class (p. ej. añadir un
> campo con default a un modelo de la API), la build-cache de Kotlin puede servir bytecode de
> tests obsoleto y fallar con `NoSuchMethodError`. Se sanea con
> `./gradlew clean build --rerun-tasks` (reescribe las entradas de cache).

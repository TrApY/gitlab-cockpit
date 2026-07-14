# Cockpit for GitLab

<!-- Plugin description -->
Manage GitLab merge requests without leaving your IDE: edit reviewers and assignees, comment, review diffs, and control the merge request's pipelines — view live logs, retry or cancel jobs.
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

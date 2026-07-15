# Plugin IntelliJ — Cockpit de Merge Requests GitLab · Requisitos V1 (rev. 2)

**Fecha:** 2026-07-14 (rev. 2, pivote MR-céntrico) · **Estado:** cerrado, pendiente de OK
final de JoTa (nombre + repo + decisión sobre alcance del review inline)

## 1. Visión

Plugin para IDEs IntelliJ centrado en **gestionar Merge Requests de GitLab** sin salir del
IDE: ver y administrar la MR (descripción, revisores, asignado, comentarios), navegar sus
ficheros modificados con diff en el editor, comentar sobre el diff, y ver/operar los
pipelines que la MR ha disparado. Primer entorno real: GitLab de Europcar
(`la instancia corporativa`, API v4). Uso personal + aprendizaje, publicación final en Marketplace.

## 2. Contexto competitivo (actualizado tras el pivote)

**IntelliJ ya trae un plugin GitLab bundled** (open source, en
`intellij-community/plugins/gitlab`) que cubre: lista de MRs con filtros (estado, autor,
assignee, reviewer, label), overview + timeline con comentarios generales, checkout de la
rama, Review Mode con comentarios inline en el diff/editor (incl. drafts y Submit
Review/Approve, multiline en GitLab 18.6+, adjuntos), y merge/squash/close.

**Gaps reales del nativo** (nuestro valor diferencial):
1. **No edita la MR**: no permite añadir/quitar revisores ni asignado, ni editar
   título/descripción.
2. **Cero CI**: no muestra pipelines de la MR, ni stages/jobs, ni logs, ni retry.
3. **Filtros limitados**: no existe "soy revisor y aún no he aprobado" ni vistas por
   usuario arbitrario combinadas.
4. Notificaciones pobres (un punto en el tool window).

Otros plugins: "GitLab CICD" (ideguru) cubre solo CI; "Merge Request Integration CE"
(open source, nhat-phan) hace review pero está poco mantenido. Nadie une gestión de MR +
review + pipelines en una sola vista: **ese es el hueco**.

El plugin bundled es además nuestra **referencia de implementación** (Apache 2.0) para la
parte más difícil: los comentarios inline sobre el diff.

## 3. Decisiones técnicas cerradas

| Tema | Decisión | Por qué |
|---|---|---|
| Lenguaje | Kotlin | Estándar del ecosistema; template oficial Kotlin-first. |
| Base | `intellij-platform-plugin-template` + IntelliJ Platform Gradle Plugin 2.x | CI, versionado y publicación resueltos. |
| Compat mínima | `sinceBuild` 252 provisional (= plataforma de build 2025.2; declarar menos sería mentir sobre APIs). Se bajará junto con la plataforma cuando JoTa confirme la versión de su IDE en Europcar | Coherencia build/declaración; margen real pendiente de dato. |
| API GitLab | REST v4 con cliente propio ligero (`java.net.http` + kotlinx.serialization) | Superficie pequeña; gitlab4j arrastra Jakarta/Jersey al sandbox. GraphQL solo como plan B para filtros/review si REST se queda corto (el plugin bundled usa GraphQL — señal de que puede hacer falta). |
| Auth | PAT scope `api` en PasswordSafe | `read_api` no permite editar MR/retry; nunca en claro. |
| Instancias | 1 configurable; settings modelados como lista | Multi-instancia futura sin migración. |
| Diff de ficheros | Sin checkout: contenidos base/head vía API (`/repository/files` raw a `diff_refs.base_sha`/`head_sha`) → `SimpleDiffRequest` en el editor | Ver el diff no debe obligar a hacer checkout. Checkout opcional (como el nativo) para probar en local. |
| Comentarios inline | Discussions API con `position` (SHAs de `diff_refs` + old/new path/line) + Draft Notes API para review por lotes | Es el mecanismo oficial; el UI se estudia del plugin bundled. |
| Convivencia | Nuestro plugin NO sustituye al nativo; tool window propia | El usuario decide cuál usa para cada cosa. |
| Retry de stage | Simulado por jobs (la API no tiene retry de stage) | Ya validado con JoTa. |
| Streaming de logs | Poll incremental de `/jobs/:id/trace` con offset, render ANSI | REST no tiene streaming real. |
| Distribución | ZIP + Install from Disk hasta la fase final; luego Marketplace | Publicar al final. |

## 4. Alcance funcional por fases

Cada fase termina en algo instalable y usable por JoTa sobre la instancia corporativa.

### Fase 1 — Cockpit de MRs (gestión)

- Tool Window propia con lista de MRs del proyecto detectado por git remote (override
  manual).
- Filtros combinables: estado · soy autor · soy revisor · **soy revisor y no he
  aprobado** · assignee · por usuario arbitrario · label.
- Detalle de MR: descripción renderizada (markdown), estado de aprobaciones y conflictos.
- **Editar la MR**: añadir/quitar revisores y asignado (picker de miembros del proyecto,
  `PUT` con `reviewer_ids`/`assignee_ids`); editar título/descripción.
- Comentarios generales de la MR (crear + ver hilo de notas de usuario; los system notes
  se filtran).
- Aprobar / quitar aprobación. Abrir en navegador.

### Fase 2 — Pipelines de la MR

- En el detalle de la MR: pipelines que ha disparado, con árbol pipeline → stage → job.
- Acciones: retry/cancel de pipeline · retry/cancel/play de job · retry de stage
  (simulado) · crear pipeline sobre la rama.
- Logs: completos para jobs terminados + streaming en vivo con ANSI.
- Notificaciones del IDE: pipeline de mis MRs pasa a failed/success; nueva MR donde soy
  revisor.
- Auto-refresh configurable con backoff.

### Fase 3 — Ficheros modificados y diff (lectura)

- Tree de ficheros de la MR (agrupado por carpetas, con contador de comentarios por
  fichero; fuente `GET /merge_requests/:iid/diffs`).
- Clic en fichero → diff base/head en el editor del IDE (sin checkout).
- Comentarios existentes del diff visibles y anclados a su línea (lectura + responder).

### Fase 4 — Review inline (la parte dura)

- Añadir comentario desde el gutter del diff sobre una línea (old/new side).
- Draft notes → revisión por lotes con Submit/Approve (bulk publish).
- Resolver/reabrir hilos. Multiline si el GitLab es ≥ 18.6 (degradar si no).
- Implementación guiada por el código del plugin bundled (`plugins/gitlab` en
  intellij-community).

### Fase 5 — Publicación

- Pulido UX, iconos, README con screenshots, listing, nota de privacidad del token.
- Alta en Marketplace (revisión inicial ~2-3 días laborables), release automatizada.

### Fuera de alcance V1

Crear MRs desde el IDE · merge/close desde el plugin (lo hace el nativo) · multi-instancia
simultánea · soporte GitHub · sugerencias de código (`suggestion:`) · AI.

## 5. Requisitos no funcionales

- Nada de red en el EDT: coroutines + `Dispatchers.IO`.
- Token solo en PasswordSafe; jamás logueado.
- Errores de red → estado "desconectado" + backoff, sin spam.
- Textos en inglés con bundle preparado para ES.
- Tests: unitarios del cliente REST (mock server) + smoke del Tool Window.
- **Pruebas de aceptación: JoTa sobre la instancia corporativa con sus MRs reales.** El desarrollo
  y los tests automáticos usan un proyecto de prueba en gitlab.com + mocks (no tocamos
  la instancia corporativa desde CI).

## 6. Riesgos y gotchas conocidos

1. **Review inline es la pieza más compleja del catálogo de plugin dev** (a JetBrains le
   llevó varias releases). Mitigación: va en Fase 4, con las fases 1-3 ya usables, y con
   el código del bundled como referencia. Si se atasca, plan B: botón "abrir este fichero
   en Review Mode del nativo" mientras tanto.
2. **`position` de Discussions API es quisquilloso**: exige `base_sha`/`start_sha`/
   `head_sha` coherentes con `diff_refs` y línea old/new correcta; los 400 son crípticos.
   Se encapsula en el cliente con tests dedicados.
3. **Filtro "revisor sin aprobar"**: no existe directo en REST → cruce con approvals por
   MR (cache, paginación corta); plan B GraphQL.
4. **Versión GitLab de la instancia corporativa desconocida**: el plugin consulta `/api/v4/version` y
   degrada (multiline ≥18.6; draft notes ≥16.x aprox.). Endpoints core estables desde 13+.
5. **Solapamiento con el nativo**: riesgo de construir algo que ya existe. Mitigación: el
   diferencial (gestión MR + pipelines + filtros) va PRIMERO (fases 1-2).

## 7. Plan de arranque del repo

1. Repo privado en GitHub (TrApY) desde `intellij-platform-plugin-template`.
2. Renombrar plugin id/grupo (`dev.jota.<nombre>`), limpiar ejemplo.
3. `runIde` en sandbox contra proyecto de prueba en gitlab.com.
4. Capas: `api-client` (REST + modelos + tests de position) · `core` (servicios, polling,
   estado) · `ui` (Tool Window, detalle MR, visor logs, settings).
5. Hitos Fase 1: **H1** settings + conexión + test `/version` · **H2** lista MRs +
   filtros · **H3** detalle + edición reviewers/assignee · **H4** comentarios generales +
   aprobar → primera instalación from-disk.
6. Registro de tareas en Linear (equipo nuevo) si JoTa da OK.

## 8. Pendiente de JoTa

- **Nombre del plugin** (propuestas: `MergePilot` · `GitLab Cockpit` · `MR Deck`).
- OK a repo privado en TrApY + equipo Linear.
- Confirmar el orden de fases propuesto (gestión+pipelines antes que review inline) o
  invertirlo si el review inline es lo que más le urge en el día a día.

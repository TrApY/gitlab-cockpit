package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.api.GitLabPipeline

/**
 * One pipeline stage: its [name], the jobs that belong to it (in their original order) and the
 * [status] aggregated from those jobs by [aggregateStatus].
 */
data class StageGroup(
    val name: String,
    val jobs: List<GitLabJob>,
    val status: String,
)

/**
 * Groups [jobs] into stages ordered by pipeline *execution* order (`.pre` → build → … → deploy),
 * not by the order GitLab happens to return them in. A stage's position is its smallest
 * [GitLabJob.id]: job ids grow with creation, which follows the pipeline's stage definition order, so
 * the stage whose first job was created earliest runs earliest. The jobs inside each stage are sorted
 * by [GitLabJob.id] ascending for the same reason, and each group's status is aggregated (from those
 * jobs) with [aggregateStatus].
 *
 * Both the sort of the stages and the sort within a stage are stable, so stages that (impossibly)
 * tied on their smallest id would keep their first-appearance order.
 */
fun groupByStage(jobs: List<GitLabJob>): List<StageGroup> {
    val byStage = LinkedHashMap<String, MutableList<GitLabJob>>()
    for (job in jobs) {
        byStage.getOrPut(job.stage) { mutableListOf() }.add(job)
    }
    return byStage
        .map { (name, stageJobs) ->
            val ordered = stageJobs.sortedBy { it.id }
            StageGroup(name, ordered, aggregateStatus(ordered))
        }
        .sortedBy { group -> group.jobs.minOf { it.id } }
}

/**
 * Aggregates a stage's status from its [jobs] with a strict "worst-of" precedence, from worst to
 * best:
 *
 * 1. `failed`   — any job that failed and is **not** `allow_failure`.
 * 2. `running`  — any job still running.
 * 3. `pending`  — any job `pending` or `created` (both map to this single bucket).
 * 4. `manual`   — any job awaiting a manual trigger.
 * 5. `canceled` — any canceled job.
 * 6. `warning`  — otherwise, if any job failed **with** `allow_failure` (a failure that does not
 *    fail the pipeline: treated as success, but flagged).
 * 7. `success`  — nothing above applied.
 *
 * A `failed` job with `allowFailure == true` never contributes to the top `failed` tier; it only
 * raises the aggregate to `warning` when the stage would otherwise be `success`. Statuses outside
 * these tiers (e.g. `skipped`) do not raise the aggregate above `success`. An empty stage is
 * `success`.
 */
fun aggregateStatus(jobs: List<GitLabJob>): String {
    if (jobs.any { it.status == "failed" && !it.allowFailure }) return "failed"
    if (jobs.any { it.status == "running" }) return "running"
    if (jobs.any { it.status == "pending" || it.status == "created" }) return "pending"
    if (jobs.any { it.status == "manual" }) return "manual"
    if (jobs.any { it.status == "canceled" }) return "canceled"
    if (jobs.any { it.status == "failed" && it.allowFailure }) return "warning"
    return "success"
}

/**
 * Whether a pipeline is still *alive* — worth polling — i.e. at least one of its [jobs] has not
 * finished (`created` / `pending` / `running`, exactly the [isJobCancelable] set). A pipeline with no
 * such job is terminal (all jobs `success` / `failed` / `canceled` / `manual` / `skipped`), so the
 * live-status poller does one last pass and stops. An empty job list is not live (nothing running).
 * Pure so the poller's start/stop decision (GLC-43 B) is unit-testable without Swing or the network.
 */
fun isPipelineLive(jobs: List<GitLabJob>): Boolean = jobs.any { isJobCancelable(it.status) }

/**
 * Which stage names the pipelines tree should show expanded after an in-place refresh (GLC-43 B): a
 * stage is expanded when it is `failed` (the auto-expand rule of the first render) **or** it was
 * [previouslyExpanded] by the user and still exists among [stages]. Keeping the set keyed by stage
 * *name* (not by node identity) survives the tree rebuild the 5-second poll does, so a running
 * pipeline never collapses a stage the user opened. Pure and platform-free for a direct unit test.
 */
fun stagesToExpand(previouslyExpanded: Set<String>, stages: List<StageGroup>): Set<String> =
    stages.asSequence()
        .filter { it.status == "failed" || it.name in previouslyExpanded }
        .map { it.name }
        .toSet()

/**
 * One row of the pipelines tree in its compact, attention-first shape (GLC-59), produced by
 * [compactStages] from the [groupByStage] output. The compact view's premise: green stages carry no
 * signal, so they fold into one [Summary] row, while every stage that needs attention keeps its own
 * row — flattened to a single line when it holds a single job ([FlatStage]).
 */
sealed interface PipelineRow {

    /** A stage rendered as a parent node with one child row per job — the classic tree shape. */
    data class Stage(val stage: StageGroup) : PipelineRow

    /**
     * A single-job stage flattened to one `stage · job` row: the job's status and duration shown
     * directly on the stage's line, with no expandable parent, so a one-job stage costs one row
     * instead of two. Only ever built for a stage with exactly one job ([compactStageRow]).
     */
    data class FlatStage(val stage: StageGroup) : PipelineRow {
        /** The stage's single job, whose status/duration the flattened row displays. */
        val job: GitLabJob get() = stage.jobs.first()
    }

    /**
     * Every fully successful stage of the pipeline folded into one collapsed "N stages passed
     * (M jobs)" row. Deliberately *all* of them, even when they are not consecutive: the compact view
     * shows pipeline *state*, not execution order — the real order is still visible in the stage
     * strip and in the show-all tree. Only built when there are at least two such stages.
     */
    data class Summary(val stages: List<StageGroup>) : PipelineRow {
        /** Total number of jobs across the summarized [stages], for the "(M jobs)" suffix. */
        val jobCount: Int get() = stages.sumOf { it.jobs.size }
    }
}

/**
 * Compacts the [groupByStage] output into the rows the pipelines tree shows (GLC-59):
 *
 * - With [showAll] every stage becomes a classic [PipelineRow.Stage] in pipeline order — no summary,
 *   no flattening — restoring the traditional tree.
 * - Otherwise the stages that need attention (any aggregate but `success`: `failed`, `running`,
 *   `pending`, `manual`, `canceled` and `warning`) come first, in pipeline order, each shaped by
 *   [compactStageRow]; the fully successful stages fold into a single [PipelineRow.Summary] appended
 *   *last*, after the rows the user actually needs to look at.
 * - A summary needs at least two successful stages; with one (or none) every stage stays an
 *   individual row, in plain pipeline order, still shaped by [compactStageRow].
 *
 * Pure and platform-free so the summarize/flatten decisions are unit-testable without Swing.
 */
fun compactStages(stages: List<StageGroup>, showAll: Boolean): List<PipelineRow> {
    if (showAll) return stages.map { PipelineRow.Stage(it) }
    val passed = stages.filter { it.status == "success" }
    if (passed.size < 2) return stages.map { compactStageRow(it) }
    return stages.filterNot { it.status == "success" }.map { compactStageRow(it) } + PipelineRow.Summary(passed)
}

/**
 * The compact view's flattening rule for one stage: a single-job stage becomes a
 * [PipelineRow.FlatStage] (one `stage · job` line), anything else a classic [PipelineRow.Stage].
 * Shared by [compactStages] and by the UI when it shapes the rows *inside* an expanded summary, so
 * both apply the exact same rule.
 */
fun compactStageRow(stage: StageGroup): PipelineRow =
    if (stage.jobs.size == 1) PipelineRow.FlatStage(stage) else PipelineRow.Stage(stage)

/** A job whose [status] allows a retry: it has finished (`failed` / `canceled` / `success`). */
fun isJobRetryable(status: String): Boolean = status in RETRYABLE_STATUSES

/** A job whose [status] allows cancellation: it has not finished (`created` / `pending` / `running`). */
fun isJobCancelable(status: String): Boolean = status in CANCELABLE_STATUSES

/** A `manual` job can be played (started). */
fun isJobPlayable(status: String): Boolean = status == "manual"

private val RETRYABLE_STATUSES = setOf("failed", "canceled", "success")
private val CANCELABLE_STATUSES = setOf("created", "pending", "running")

/**
 * Folds an MR's [head] pipeline into the list the `/pipelines` endpoint returned. GitLab omits
 * externally reported pipelines (e.g. Jenkins) from that endpoint but still exposes them as the MR's
 * `head_pipeline`, which otherwise leaves the Pipelines tab empty. The rules:
 *
 * - [head] null → [pipelines] unchanged (nothing to merge).
 * - [head] already in [pipelines] (same [GitLabPipeline.id]) → [pipelines] unchanged (no duplicate).
 * - otherwise → [head] prepended, so the head pipeline shows first (as the newest).
 */
fun mergeHeadPipeline(pipelines: List<GitLabPipeline>, head: GitLabPipeline?): List<GitLabPipeline> {
    if (head == null) return pipelines
    if (pipelines.any { it.id == head.id }) return pipelines
    return listOf(head) + pipelines
}

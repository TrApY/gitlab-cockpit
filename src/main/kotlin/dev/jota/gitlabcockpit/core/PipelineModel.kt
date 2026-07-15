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
 * Groups [jobs] into stages, preserving the order in which each stage first appears in the list
 * (GitLab already returns jobs stage-ordered). Each group's status is aggregated with
 * [aggregateStatus].
 */
fun groupByStage(jobs: List<GitLabJob>): List<StageGroup> {
    val byStage = LinkedHashMap<String, MutableList<GitLabJob>>()
    for (job in jobs) {
        byStage.getOrPut(job.stage) { mutableListOf() }.add(job)
    }
    return byStage.map { (name, stageJobs) -> StageGroup(name, stageJobs, aggregateStatus(stageJobs)) }
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

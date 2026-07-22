package dev.jota.gitlabcockpit.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.MrRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a failed head-pipeline fetch should be retried yet, given how many [attempts] have failed so
 * far, the epoch-ms of the [lastFailureMs] and the current [nowMs]. The backoff schedule is
 * 30s → 2min → 5min: after the 1st failure a retry is allowed 30s later, after the 2nd 2min later,
 * after the 3rd 5min later. After the 3rd failure (attempts ≥ 4, or a defensive ≤ 0) the MR is given
 * up on until its `updated_at` changes, so this returns false. Pure and side-effect-free so it is unit
 * tested directly; the caller supplies the clock via [nowMs].
 */
fun shouldRetry(attempts: Int, lastFailureMs: Long, nowMs: Long): Boolean {
    val delayMs = when (attempts) {
        1 -> RETRY_DELAY_1_MS
        2 -> RETRY_DELAY_2_MS
        3 -> RETRY_DELAY_3_MS
        else -> return false
    }
    return nowMs - lastFailureMs >= delayMs
}

private const val RETRY_DELAY_1_MS = 30_000L
private const val RETRY_DELAY_2_MS = 120_000L
private const val RETRY_DELAY_3_MS = 300_000L

/**
 * Enriches the MR list rows with their head-pipeline status, which the list endpoint does not carry.
 * For each row it fetches the MR detail (`GET /projects/:id/merge_requests/:iid`, via
 * [CockpitProjectService.getMrDetail]) in the background and remembers `head_pipeline.status`, keyed
 * by [MrRef] and the MR's `updated_at` so an unchanged MR is never re-fetched. The renderer reads the
 * cached status synchronously via [statusOf]; a row with no status yet simply shows no pipeline icon
 * (no spinner, no noise).
 *
 * A *successful* fetch — including a success with no head pipeline — is cached as an [Entry.Observed]
 * exactly as before. A *failed* fetch (HTTP or network error) is **not** cached as "no pipeline":
 * caching an error would hide the pipeline until the MR next changed. Instead the failure is recorded
 * as an [Entry.Failed] with its attempt count and timestamp, and re-fetched on a backoff schedule
 * (30s → 2min → 5min, [shouldRetry]); after 3 failed attempts the MR is left alone until its
 * `updated_at` changes.
 *
 * Each [enrich] call is one cancellable batch: it cancels the previous batch's [Job] (so a fresh list
 * refresh supersedes an in-flight one) and fetches the changed/retryable rows in parallel, capped at
 * [MAX_CONCURRENT] by a [Semaphore]. The [scope] is injected by the platform.
 */
@Service(Service.Level.PROJECT)
class MrListEnrichment(
    private val project: Project,
    private val scope: CoroutineScope,
) {

    /** A cache entry for one MR: either a completed observation or a run of recorded failures. */
    private sealed interface Entry {
        /**
         * A completed fetch for [updatedAt]: the observed head-pipeline [status] (null = the MR has no
         * head pipeline). Served synchronously by [statusOf] while the MR's `updated_at` is unchanged.
         */
        data class Observed(val updatedAt: String, val status: String?) : Entry

        /**
         * Failed fetches for [updatedAt]: how many [attempts] have failed and the [lastFailureMs] epoch
         * of the most recent one, driving the [shouldRetry] backoff. Never surfaces a status.
         */
        data class Failed(val updatedAt: String, val attempts: Int, val lastFailureMs: Long) : Entry
    }

    private val cache = ConcurrentHashMap<MrRef, Entry>()

    @Volatile
    private var batchJob: Job? = null

    /**
     * The cached head-pipeline status for [mr], or `null` when it has not been observed yet, the MR
     * changed since (its `updated_at` no longer matches), or only failures are recorded for it. A
     * non-null [Entry.Observed] with a `null` status means "fetched, but the MR has no head pipeline" —
     * also rendered as no icon.
     */
    fun statusOf(mr: GitLabMergeRequest): String? =
        (cache[MrRef(mr.projectId, mr.iid)] as? Entry.Observed)
            ?.takeIf { it.updatedAt == mr.updatedAt }
            ?.status

    /**
     * Starts a background batch that fetches the head-pipeline status of every row in [mrs] that needs
     * it ([needsFetch]: never observed, changed since, or a failure whose backoff has elapsed), invoking
     * [onRowUpdated] on the EDT whenever a fetch yields a status worth painting (so the list repaints).
     * Cancels any previous batch first; a batch with nothing to fetch is a no-op.
     */
    fun enrich(mrs: List<GitLabMergeRequest>, onRowUpdated: () -> Unit) {
        batchJob?.cancel()
        val now = System.currentTimeMillis()
        val stale = mrs.filter { needsFetch(it, now) }
        if (stale.isEmpty()) return
        val service = CockpitProjectService.getInstance(project)
        batchJob = scope.launch {
            val semaphore = Semaphore(MAX_CONCURRENT)
            stale.map { mr ->
                async {
                    semaphore.withPermit {
                        val ref = MrRef(mr.projectId, mr.iid)
                        when (val r = service.getMrDetail(ref)) {
                            is GitLabResult.Success -> {
                                val status = r.data.headPipeline?.status
                                cache[ref] = Entry.Observed(mr.updatedAt, status)
                                if (status != null) withContext(Dispatchers.EDT) { onRowUpdated() }
                            }
                            // An error is recorded (not cached as "no pipeline") so a retry is scheduled.
                            else -> recordFailure(ref, mr.updatedAt)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * True when [mr] needs a (re)fetch at [now]: no entry yet, an observation/failure recorded for a
     * different `updated_at` (the MR changed — fetch afresh), or a failure for the current `updated_at`
     * whose backoff window ([shouldRetry]) has elapsed. A matching [Entry.Observed] needs nothing; a
     * failure past its 3 attempts is left alone until the MR changes.
     */
    private fun needsFetch(mr: GitLabMergeRequest, now: Long): Boolean {
        val ref = MrRef(mr.projectId, mr.iid)
        return when (val entry = cache[ref]) {
            null -> true
            is Entry.Observed -> entry.updatedAt != mr.updatedAt
            is Entry.Failed ->
                entry.updatedAt != mr.updatedAt || shouldRetry(entry.attempts, entry.lastFailureMs, now)
        }
    }

    /**
     * Records a failed fetch for [ref] at [updatedAt], incrementing the attempt count when the previous
     * failure was for the same `updated_at` (a fresh `updated_at` restarts at attempt 1). Atomic via
     * [ConcurrentHashMap.compute] so overlapping records never lose an increment.
     */
    private fun recordFailure(ref: MrRef, updatedAt: String) {
        val now = System.currentTimeMillis()
        cache.compute(ref) { _, existing ->
            val priorAttempts = (existing as? Entry.Failed)?.takeIf { it.updatedAt == updatedAt }?.attempts ?: 0
            Entry.Failed(updatedAt, priorAttempts + 1, now)
        }
    }

    companion object {
        /** Max detail fetches running at once per batch; the rest queue on the [Semaphore]. */
        private const val MAX_CONCURRENT = 4

        fun getInstance(project: Project): MrListEnrichment = project.service()
    }
}

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
 * Enriches the MR list rows with their head-pipeline status, which the list endpoint does not carry.
 * For each row it fetches the MR detail (`GET /projects/:id/merge_requests/:iid`, via
 * [CockpitProjectService.getMrDetail]) in the background and remembers `head_pipeline.status`, keyed
 * by [MrRef] and the MR's `updated_at` so an unchanged MR is never re-fetched. The renderer reads the
 * cached status synchronously via [statusOf]; a row with no status yet simply shows no pipeline icon
 * (no spinner, no noise).
 *
 * Each [enrich] call is one cancellable batch: it cancels the previous batch's [Job] (so a fresh list
 * refresh supersedes an in-flight one) and fetches the changed rows in parallel, capped at
 * [MAX_CONCURRENT] by a [Semaphore]. The [scope] is injected by the platform.
 */
@Service(Service.Level.PROJECT)
class MrListEnrichment(
    private val project: Project,
    private val scope: CoroutineScope,
) {

    /** A cached status observation for one MR: the `updated_at` it was fetched for and the status. */
    private data class Cached(val updatedAt: String, val status: String?)

    private val cache = ConcurrentHashMap<MrRef, Cached>()

    @Volatile
    private var batchJob: Job? = null

    /**
     * The cached head-pipeline status for [mr], or `null` when it has not been fetched yet or the MR
     * changed since (its `updated_at` no longer matches the cached observation). A non-null cache entry
     * with a `null` status means "fetched, but the MR has no head pipeline" — also rendered as no icon.
     */
    fun statusOf(mr: GitLabMergeRequest): String? =
        cache[MrRef(mr.projectId, mr.iid)]?.takeIf { it.updatedAt == mr.updatedAt }?.status

    /**
     * Starts a background batch that fetches the head-pipeline status of every row in [mrs] not
     * already cached for its current `updated_at`, invoking [onRowUpdated] on the EDT whenever a fetch
     * yields a status worth painting (so the list repaints). Cancels any previous batch first; a batch
     * with nothing to fetch is a no-op.
     */
    fun enrich(mrs: List<GitLabMergeRequest>, onRowUpdated: () -> Unit) {
        batchJob?.cancel()
        val stale = mrs.filter { needsFetch(it) }
        if (stale.isEmpty()) return
        val service = CockpitProjectService.getInstance(project)
        batchJob = scope.launch {
            val semaphore = Semaphore(MAX_CONCURRENT)
            stale.map { mr ->
                async {
                    semaphore.withPermit {
                        val ref = MrRef(mr.projectId, mr.iid)
                        val status = when (val r = service.getMrDetail(ref)) {
                            is GitLabResult.Success -> r.data.headPipeline?.status
                            else -> null
                        }
                        cache[ref] = Cached(mr.updatedAt, status)
                        if (status != null) withContext(Dispatchers.EDT) { onRowUpdated() }
                    }
                }
            }.awaitAll()
        }
    }

    /** True when [mr] has no cached status for its current `updated_at` (cache miss or MR changed). */
    private fun needsFetch(mr: GitLabMergeRequest): Boolean =
        cache[MrRef(mr.projectId, mr.iid)]?.updatedAt != mr.updatedAt

    companion object {
        /** Max detail fetches running at once per batch; the rest queue on the [Semaphore]. */
        private const val MAX_CONCURRENT = 4

        fun getInstance(project: Project): MrListEnrichment = project.service()
    }
}

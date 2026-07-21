package dev.jota.gitlabcockpit.core

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Per-project, per-MR record of which changed files the user has already reviewed. Keyed by
 * `"projectId:iid"` and captured at a given head SHA: when the MR gets new commits (its head SHA
 * changes) the previously reviewed set is stale, so it is discarded lazily on the first access at
 * the new SHA — a review always reflects the diff the user actually looked at.
 *
 * Persisted in the workspace file (session/machine-local, not shared through VCS), so reviewed state
 * survives IDE restarts but does not travel with the project. The state shape is a plain map of
 * [Entry] beans so the platform's XML serializer can round-trip it without custom bindings.
 *
 * A "reviewed" file is stored by its tree path — `new_path`, or `old_path` for a deleted file — the
 * same key [dev.jota.gitlabcockpit.ui.ChangesPanel]'s tree and the diff auto-mark use, so lookups
 * line up across the UI.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "GitLabCockpitReviewedFiles",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class ReviewedFiles : PersistentStateComponent<ReviewedFiles.State> {

    /** One MR's reviewed record: the [headSha] it was captured at and the reviewed file [paths]. */
    class Entry {
        var headSha: String = ""
        var paths: MutableSet<String> = LinkedHashSet()
    }

    /** Root serialized state: one [Entry] per MR, keyed by `"projectId:iid"`. */
    class State {
        var entries: MutableMap<String, Entry> = LinkedHashMap()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** Whether [path] is marked reviewed for [ref] at [sha] (a stale-SHA entry reads as empty). */
    fun isReviewed(ref: MrRef, sha: String, path: String): Boolean =
        entryFor(ref, sha, create = false)?.paths?.contains(path) == true

    /** Marks [path] reviewed for [ref] at [sha]. Idempotent: marking an already-marked path is a no-op. */
    fun mark(ref: MrRef, sha: String, path: String) {
        entryFor(ref, sha, create = true)!!.paths.add(path)
    }

    /** Flips [path]'s reviewed state for [ref] at [sha]; returns the new state (true = now reviewed). */
    fun toggle(ref: MrRef, sha: String, path: String): Boolean {
        val paths = entryFor(ref, sha, create = true)!!.paths
        return if (paths.remove(path)) false else paths.add(path)
    }

    /**
     * How many of [paths] (the change's current file paths) are marked reviewed for [ref] at [sha].
     * Only paths actually present in [paths] count, so a reviewed file that no longer exists in the
     * current diff is not counted.
     */
    fun reviewedCount(ref: MrRef, sha: String, paths: Collection<String>): Int {
        val reviewed = entryFor(ref, sha, create = false)?.paths ?: return 0
        return paths.count { it in reviewed }
    }

    /**
     * Resolves [ref]'s entry, first discarding it (clearing the paths, adopting [sha]) when the stored
     * head SHA no longer matches — the lazy "new commits reset the review" rule. Returns null instead
     * of allocating an entry for a pure read ([create] false) that finds nothing.
     */
    private fun entryFor(ref: MrRef, sha: String, create: Boolean): Entry? {
        val key = keyOf(ref)
        val existing = state.entries[key]
        if (existing != null) {
            if (existing.headSha != sha) {
                existing.headSha = sha
                existing.paths.clear()
            }
            return existing
        }
        if (!create) return null
        return Entry().apply { headSha = sha }.also { state.entries[key] = it }
    }

    private fun keyOf(ref: MrRef): String = "${ref.projectId}:${ref.iid}"

    companion object {
        fun getInstance(project: Project): ReviewedFiles = project.service()
    }
}

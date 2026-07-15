package dev.jota.gitlabcockpit.ui.diff

import com.intellij.openapi.util.Key
import dev.jota.gitlabcockpit.api.DiffRefs
import dev.jota.gitlabcockpit.api.GitLabDiffFile
import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.core.MrRef

/**
 * Everything [CockpitDiffExtension] needs to render one file's review threads inside its diff:
 * the MR's [mrRef], the changed [file], the MR's diff [refs] and the [discussions] anchored to the
 * file (the slice of `discussionsByFile` for it). [dev.jota.gitlabcockpit.ui.ChangesPanel] attaches
 * an instance to each `SimpleDiffRequest` it opens via [KEY] — plain request user-data, no global
 * state, so every opened diff carries exactly the threads that were loaded when it was opened.
 */
data class CockpitDiffContext(
    val mrRef: MrRef,
    val file: GitLabDiffFile,
    val refs: DiffRefs,
    val discussions: List<GitLabDiscussion>,
) {
    companion object {
        /** The request user-data slot [CockpitDiffExtension] looks for. */
        val KEY: Key<CockpitDiffContext> = Key.create("dev.jota.gitlabcockpit.diff.context")
    }
}

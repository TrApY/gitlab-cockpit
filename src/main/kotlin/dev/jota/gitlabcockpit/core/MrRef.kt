package dev.jota.gitlabcockpit.core

/**
 * The full identity of a merge request: the project it lives in ([projectId]) plus its per-project
 * [iid]. An `iid` alone is only unique within a project, so every detail operation (comments,
 * changes, pipelines, approve, edit…) is keyed by an [MrRef] — this is what makes the "All projects"
 * mode work, where MRs from many projects are shown together and each one must be operated on against
 * its own project's REST path, not the git-resolved project's.
 */
data class MrRef(val projectId: Long, val iid: Long)

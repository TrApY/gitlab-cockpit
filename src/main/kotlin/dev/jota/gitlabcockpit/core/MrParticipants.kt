package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabUser

/**
 * A role a user can hold on a merge request. The declaration order is the priority order used by
 * [mrParticipants]: a user's roles are listed in this order, and — after the author — participants are
 * ordered by their highest-priority (first) role.
 */
enum class MrRole { AUTHOR, ASSIGNEE, REVIEWER }

/** One deduplicated MR participant: the [user] and every [roles] they hold, in [MrRole] priority order. */
data class MrParticipant(val user: GitLabUser, val roles: List<MrRole>)

/**
 * Composes the deduplicated participant list for the Info header's people row (GLC-37). A user that is
 * both, say, author and reviewer appears once with both roles combined ([MrRole.AUTHOR],
 * [MrRole.REVIEWER]) instead of twice. Users are keyed by [GitLabUser.id].
 *
 * Ordering: the author comes first; the remaining users follow, ordered by their first (highest
 * priority) role — assignees before reviewers — preserving the input order within each role group.
 * Because the roles are folded in [MrRole] priority order (author, then assignees, then reviewers), a
 * simple first-seen insertion order already yields this ordering. Pure and platform-free so the
 * composition is unit-testable without Swing.
 */
fun mrParticipants(
    author: GitLabUser,
    assignees: List<GitLabUser>,
    reviewers: List<GitLabUser>,
): List<MrParticipant> {
    val byId = LinkedHashMap<Long, MutableList<MrRole>>()
    val users = LinkedHashMap<Long, GitLabUser>()
    fun fold(role: MrRole, list: List<GitLabUser>) {
        for (user in list) {
            val roles = byId.getOrPut(user.id) { mutableListOf() }
            if (role !in roles) roles.add(role)
            users.putIfAbsent(user.id, user)
        }
    }
    fold(MrRole.AUTHOR, listOf(author))
    fold(MrRole.ASSIGNEE, assignees)
    fold(MrRole.REVIEWER, reviewers)
    return users.map { (id, user) -> MrParticipant(user, byId.getValue(id).toList()) }
}

package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabUser

/**
 * Incremental member search shared by the assignee/reviewer pickers and the "By user" filter
 * autocomplete. A blank (or whitespace-only) [query] returns [members] unchanged; otherwise a member
 * matches when the (trimmed) [query] is a case-insensitive substring of its display name **or** its
 * username. Pure and platform-free so the dialogs and the completion provider all share one contract
 * that can be unit tested directly. The input order is preserved.
 */
fun filterMembers(members: List<GitLabUser>, query: String): List<GitLabUser> {
    val q = query.trim()
    if (q.isEmpty()) return members
    return members.filter { member ->
        member.name.contains(q, ignoreCase = true) || member.username.contains(q, ignoreCase = true)
    }
}

/**
 * The platform-free selection state behind [dev.jota.gitlabcockpit.ui] reviewer editing: the full
 * [members] roster plus the set of currently checked ids. It survives filtering — a member checked
 * while unfiltered stays checked even while a [query] hides it from [visibleItems], so the user can
 * search, tick a few people, refine the search and still submit everyone they picked.
 *
 * The dialog is pure glue: on each keystroke it repopulates its list with [visibleItems] and marks
 * each row from [isChecked]; on a toggle it calls [setChecked]; on OK it reads [selectedIds]. All the
 * conservation logic lives here, testable without a UI.
 *
 * @param members the full member list, in the order they should appear (defines [selectedIds] order).
 * @param initialCheckedIds the ids checked when the dialog opens (the MR's current reviewers).
 */
class ReviewerSelectionModel(
    private val members: List<GitLabUser>,
    initialCheckedIds: Set<Long>,
) {

    private val checked: MutableSet<Long> = initialCheckedIds.toMutableSet()

    /** The members matching [query] (see [filterMembers]); checked state is orthogonal to this. */
    fun visibleItems(query: String): List<GitLabUser> = filterMembers(members, query)

    /** Whether [id] is currently checked (regardless of whether it is visible under the filter). */
    fun isChecked(id: Long): Boolean = id in checked

    /** Checks ([value] true) or unchecks [id]; the change persists across filter changes. */
    fun setChecked(id: Long, value: Boolean) {
        if (value) checked.add(id) else checked.remove(id)
    }

    /**
     * The checked ids, in the stable order they appear in [members] (not insertion order). Ids that
     * are checked but absent from [members] are dropped — the result is always a subset of the roster.
     */
    fun selectedIds(): List<Long> = members.map { it.id }.filter { it in checked }
}

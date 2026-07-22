package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabLabel

/**
 * Incremental label search for the Edit-MR label picker (GLC-42). A blank (or whitespace-only)
 * [query] returns [labels] unchanged; otherwise a label matches when the (trimmed) [query] is a
 * case-insensitive substring of its [GitLabLabel.name]. Pure and platform-free — the [LabelSelectionModel]
 * and the dialog share one contract that can be unit tested directly. Input order is preserved.
 */
fun filterLabels(labels: List<GitLabLabel>, query: String): List<GitLabLabel> {
    val q = query.trim()
    if (q.isEmpty()) return labels
    return labels.filter { it.name.contains(q, ignoreCase = true) }
}

/**
 * The platform-free selection state behind the reviewer-style label picker (GLC-42): the full
 * [labels] roster plus the set of currently checked names. Like [ReviewerSelectionModel] the checks
 * survive filtering — a label ticked while unfiltered stays checked even while a [query] hides it from
 * [visibleItems] — so the user can search, tick a few, refine the search and still submit everyone
 * they picked. Keyed by label *name* (labels have no stable numeric id in the MR CSV contract).
 *
 * @param labels the full label roster, in display order (defines [selectedNames] order).
 * @param initialCheckedNames the names checked when the dialog opens (the MR's current labels).
 */
class LabelSelectionModel(
    private val labels: List<GitLabLabel>,
    initialCheckedNames: Set<String>,
) {

    private val checked: MutableSet<String> = initialCheckedNames.toMutableSet()

    /** The labels matching [query] (see [filterLabels]); checked state is orthogonal to this. */
    fun visibleItems(query: String): List<GitLabLabel> = filterLabels(labels, query)

    /** Whether [name] is currently checked (regardless of whether it is visible under the filter). */
    fun isChecked(name: String): Boolean = name in checked

    /** Checks ([value] true) or unchecks [name]; the change persists across filter changes. */
    fun setChecked(name: String, value: Boolean) {
        if (value) checked.add(name) else checked.remove(name)
    }

    /**
     * The checked names, in the stable order they appear in [labels] (not insertion order). Names that
     * are checked but absent from [labels] are dropped — the result is always a subset of the roster.
     */
    fun selectedNames(): List<String> = labels.map { it.name }.filter { it in checked }
}

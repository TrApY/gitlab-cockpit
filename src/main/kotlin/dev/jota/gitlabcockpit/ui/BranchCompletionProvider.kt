package dev.jota.gitlabcockpit.ui

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider

/**
 * Autocompletion candidates for the Edit dialog's "Destination branch" field (GLC-57): the project's
 * branch names, fed in already ordered (default first, then alphabetical — see
 * [dev.jota.gitlabcockpit.core.orderBranchNames]) via [TextFieldWithAutoCompletion.setVariants], which
 * lands in the inherited [myVariants]. The completion string is the branch name itself.
 *
 * Calqued on [MemberCompletionProvider]: it matches by case-insensitive substring (not just a prefix, so
 * `hotfix` surfaces `feature/hotfix-1`) and preserves the fed order, using the same two overrides:
 *
 * - [getItems] returns the substring matches over the current [myVariants], in their order, so the
 *   default-first ordering survives and mid-name matches are kept.
 * - [createPrefixMatcher] returns an always-matching matcher so the platform does not then re-filter the
 *   already-filtered list by a plain prefix. Because accepting a lookup then replaces an EMPTY prefix
 *   (leaving the typed letters in front), [createLookupBuilder] rewrites the whole document with the
 *   accepted branch name — the field holds exactly one branch, the same fix [MemberCompletionProvider]
 *   applies (GLC-51).
 */
class BranchCompletionProvider :
    TextFieldWithAutoCompletionListProvider<String>(emptyList()) {

    override fun getLookupString(item: String): String = item

    override fun createLookupBuilder(item: String): LookupElementBuilder =
        LookupElementBuilder.create(item, item)
            .withInsertHandler { context, _ ->
                context.document.setText(item)
                context.editor.caretModel.moveToOffset(item.length)
            }

    override fun getItems(
        prefix: String,
        cached: Boolean,
        parameters: CompletionParameters,
    ): Collection<String> {
        val query = prefix.trim()
        val variants = myVariants.toList()
        return if (query.isEmpty()) variants
        else variants.filter { it.contains(query, ignoreCase = true) }
    }

    override fun createPrefixMatcher(prefix: String): PrefixMatcher = PlainPrefixMatcher("")
}

package dev.jota.gitlabcockpit.ui

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.core.filterMembers

/**
 * Autocompletion candidates for the "By user" filter: the project members, matched with the exact
 * [filterMembers] semantics used by the pickers (case-insensitive substring over the member's display
 * name **or** username). The candidate set is fed in with [TextFieldWithAutoCompletion.setVariants],
 * which lands in the inherited [myVariants].
 *
 * The lookup string is the username — the value inserted into the field and sent verbatim as
 * `author_username` — while the popup presents `Name (@username)`. Two overrides make the contains
 * match win over the platform's default prefix matching:
 *
 * - [getItems] returns [filterMembers] applied to the current prefix, so name-only matches survive.
 * - [createPrefixMatcher] returns an always-matching matcher, so the platform does not then re-filter
 *   the already-filtered list by a plain username prefix.
 */
class MemberCompletionProvider :
    TextFieldWithAutoCompletionListProvider<GitLabUser>(emptyList()) {

    override fun getLookupString(item: GitLabUser): String = item.username

    override fun createLookupBuilder(item: GitLabUser): LookupElementBuilder =
        LookupElementBuilder.create(item, item.username)
            .withPresentableText(item.name.ifBlank { item.username })
            .withTailText(" (@${item.username})", true)
            // The always-matching prefix matcher below keeps the platform from re-filtering, but it
            // also means accepting a lookup replaces an EMPTY prefix — the typed letters were left in
            // front of the inserted username (GLC-51). The field holds exactly one username, so the
            // insert handler rewrites the whole document with the accepted value instead.
            .withInsertHandler { context, _ ->
                context.document.setText(item.username)
                context.editor.caretModel.moveToOffset(item.username.length)
            }

    override fun getItems(
        prefix: String,
        cached: Boolean,
        parameters: CompletionParameters,
    ): Collection<GitLabUser> = filterMembers(myVariants.toList(), prefix)

    override fun createPrefixMatcher(prefix: String): PrefixMatcher = PlainPrefixMatcher("")
}

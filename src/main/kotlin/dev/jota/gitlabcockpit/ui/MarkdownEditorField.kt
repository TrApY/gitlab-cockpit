package dev.jota.gitlabcockpit.ui

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TextExpression
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.ui.LanguageTextField
import dev.jota.gitlabcockpit.core.MarkdownMarker
import dev.jota.gitlabcockpit.core.wrapMarkdown

/**
 * The rich markdown input of the comment composer and the Edit MR description (GLC-54): a real
 * multiline [EditorTextField] (soft-wrapped, platform editor shortcuts) whose format bar wraps the
 * selection through the pure [wrapMarkdown] and — the reference's signature move — expands **live
 * templates** for the empty-selection cases: `[text](url)` with Tab-navigable placeholder boxes, the
 * 2×2 table skeleton with a stop per cell, and `**text**`-style inline markers with the body
 * selected. Templates run through the platform [TemplateManager], which is what draws the reference's
 * placeholder boxes and segment guides; no Markdown-plugin dependency is involved.
 */
class MarkdownEditorField(
    private val project: Project,
    initialText: String,
) : LanguageTextField(PlainTextLanguage.INSTANCE, project, initialText, false) {
    // LanguageTextField (vs the plain EditorTextField constructor) backs the document with a real
    // PsiFile — without it TemplateManager.startTemplate silently does nothing (GLC-55: the dead
    // Table button).

    init {
        addSettingsProvider { editor: EditorEx ->
            editor.settings.isUseSoftWraps = true
            editor.settings.isLineNumbersShown = false
            editor.settings.isFoldingOutlineShown = false
            editor.setHorizontalScrollbarVisible(false)
            editor.setVerticalScrollbarVisible(true)
        }
    }

    /**
     * Applies [marker] to the current selection. A non-empty selection is wrapped in place via
     * [wrapMarkdown] (document edit + the selection the wrap yields); an empty one expands the
     * marker's live template so the caret lands inside a placeholder box (Tab moves on, like the
     * reference). QUOTE always takes the document path — quoting the current line needs no template.
     */
    fun applyMarker(marker: MarkdownMarker) {
        val editor = this.editor ?: return
        val hasSelection = editor.selectionModel.hasSelection()
        if (!hasSelection && marker != MarkdownMarker.QUOTE) {
            if (startTemplate(markerTemplate(marker))) return
            // Template engine unavailable → same result without the Tab boxes (GLC-55).
        }
        val start = editor.selectionModel.selectionStart
        val end = editor.selectionModel.selectionEnd
        val result = wrapMarkdown(text, start, end, marker)
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText(result.text)
        }
        editor.selectionModel.setSelection(result.selectionStart, result.selectionEnd)
        editor.caretModel.moveToOffset(result.selectionEnd)
        requestFocusInWindow()
    }

    /**
     * Expands the 2×2 markdown table template — one Tab stop per header and cell (GLC-54). If the
     * template engine does not engage (belt-and-braces, GLC-55), a plain table skeleton is inserted
     * with the first `header` selected instead.
     */
    fun insertTable() {
        val manager = TemplateManager.getInstance(project)
        val template = manager.createTemplate("", TEMPLATE_GROUP)
        template.addTextSegment("| ")
        template.addVariable("H1", TextExpression("header"), TextExpression("header"), true)
        template.addTextSegment(" | ")
        template.addVariable("H2", TextExpression("header"), TextExpression("header"), true)
        template.addTextSegment(" |\n| --- | --- |\n| ")
        template.addVariable("C1", TextExpression("cell"), TextExpression("cell"), true)
        template.addTextSegment(" | ")
        template.addVariable("C2", TextExpression("cell"), TextExpression("cell"), true)
        template.addTextSegment(" |\n")
        template.isToReformat = false
        if (!startTemplate(template)) {
            val editor = this.editor ?: return
            val offset = editor.caretModel.offset
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(offset, PLAIN_TABLE)
            }
            val headerAt = offset + PLAIN_TABLE.indexOf("header")
            editor.selectionModel.setSelection(headerAt, headerAt + "header".length)
            editor.caretModel.moveToOffset(headerAt + "header".length)
            requestFocusInWindow()
        }
    }

    /** Inserts [snippet] (an emoji from the picker) at the caret. */
    fun insertAtCaret(snippet: String) {
        val editor = this.editor ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.caretModel.offset, snippet)
        }
        editor.caretModel.moveToOffset(editor.caretModel.offset + snippet.length)
        requestFocusInWindow()
    }

    /** The empty-selection live template of [marker]: placeholders exactly like the reference's. */
    private fun markerTemplate(marker: MarkdownMarker): com.intellij.codeInsight.template.Template {
        val manager = TemplateManager.getInstance(project)
        val template = manager.createTemplate("", TEMPLATE_GROUP)
        when (marker) {
            MarkdownMarker.BOLD -> template.inlineWithPlaceholder("**")
            MarkdownMarker.ITALIC -> template.inlineWithPlaceholder("*")
            MarkdownMarker.STRIKE -> template.inlineWithPlaceholder("~~")
            MarkdownMarker.CODE -> template.inlineWithPlaceholder("`")
            MarkdownMarker.CODE_BLOCK -> {
                template.addTextSegment("```\n")
                template.addVariable("CODE", TextExpression("code"), TextExpression("code"), true)
                template.addTextSegment("\n```\n")
            }
            MarkdownMarker.LINK -> {
                template.addTextSegment("[")
                template.addVariable("TEXT", TextExpression("text"), TextExpression("text"), true)
                template.addTextSegment("](")
                template.addVariable("URL", TextExpression("url"), TextExpression("url"), true)
                template.addTextSegment(")")
            }
            MarkdownMarker.QUOTE -> error("QUOTE always takes the document path")
        }
        template.isToReformat = false
        return template
    }

    private fun com.intellij.codeInsight.template.Template.inlineWithPlaceholder(symbol: String) {
        addTextSegment(symbol)
        addVariable("TEXT", TextExpression("text"), TextExpression("text"), true)
        addTextSegment(symbol)
    }

    /** Starts [template] on the field's editor; false when the engine did not engage (GLC-55). */
    private fun startTemplate(template: com.intellij.codeInsight.template.Template): Boolean {
        val editor = this.editor ?: return false
        val before = editor.document.text
        requestFocusInWindow()
        TemplateManager.getInstance(project).startTemplate(editor, template)
        return TemplateManager.getInstance(project).getActiveTemplate(editor) != null ||
            editor.document.text != before
    }

    companion object {
        /** Group id of the ad-hoc templates the format bar expands. */
        private const val TEMPLATE_GROUP = "GitLabCockpit"

        /** The plain 2×2 table skeleton the fallback inserts when the template engine is unavailable. */
        private const val PLAIN_TABLE = "| header | header |\n| --- | --- |\n| cell | cell |\n"
    }
}

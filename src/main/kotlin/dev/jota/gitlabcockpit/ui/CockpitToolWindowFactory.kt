package dev.jota.gitlabcockpit.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.jota.gitlabcockpit.CockpitBundle

/**
 * Registers the "GitLab Cockpit" tool window. Its first, non-closeable content is the merge-request
 * list ([CockpitToolWindowPanel]); opening an MR from the list adds a closeable per-MR tab
 * ([MrDetailPanel]) alongside it. Only the list tab is created here, so a project reopen restores just
 * the list — per-MR tabs are session-only.
 */
class CockpitToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CockpitToolWindowPanel(project, toolWindow)
        val content = ContentFactory.getInstance()
            .createContent(panel, CockpitBundle.message("toolwindow.tab.list"), false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

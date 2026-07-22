package dev.jota.gitlabcockpit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.jota.gitlabcockpit.api.GitLabMergeRequest

/**
 * Cross-cutting navigation into the plugin's tool window for callers that live outside the panel and
 * do not hold a reference to it — the background poller and the event notifications (GLC-54). Also the
 * single home of the tool window id declared in `plugin.xml`, so it is no longer duplicated per caller.
 */
object CockpitNavigation {

    /** Id of the plugin tool window, as declared in `plugin.xml`. */
    const val TOOL_WINDOW_ID: String = "GitLab Cockpit"

    /**
     * Activates the plugin tool window and opens (or re-selects) [mr]'s own tab. Works with the tool
     * window **closed** — [com.intellij.openapi.wm.ToolWindow.activate] shows it and creates its content
     * first, so the list panel exists by the time the callback runs; this is the background poller's
     * case, where the "Open in Cockpit" balloon action fires while nothing is open (GLC-54). The
     * callback runs on the EDT. A no-op if the tool window is not registered (e.g. dumb/early startup).
     */
    fun openMr(project: Project, mr: GitLabMergeRequest) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? CockpitToolWindowPanel }
            panel?.openMrTab(mr)
        }
    }
}

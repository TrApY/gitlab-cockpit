package dev.jota.gitlabcockpit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.core.CockpitProjectService
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A non-modal viewer for a single CI job's log, delegating the console and trace streaming to a
 * [JobLogConsole]. Opened from [PipelinesPanel] by double-clicking a job node or via the "View log"
 * context-menu action.
 *
 * The dialog offers only a Close button and remembers its size via [getDimensionServiceKey]; when the
 * job finishes, the embedded console reports the final status and the title is updated to match.
 */
class JobLogDialog(
    project: Project,
    service: CockpitProjectService,
    private val job: GitLabJob,
) : DialogWrapper(project, false, IdeModalityType.MODELESS) {

    private val logConsole = JobLogConsole(project, service, job, disposable) { status ->
        title = CockpitBundle.message("log.title", job.name, status)
    }

    init {
        title = CockpitBundle.message("log.title", job.name, job.status)
        cancelAction.putValue(Action.NAME, CockpitBundle.message("log.close"))
        init()
        logConsole.start()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(logConsole.component, BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(900, 600)
        return panel
    }

    /** Only a Close button (the renamed cancel action); no OK. */
    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    companion object {
        private const val DIMENSION_KEY = "dev.jota.gitlabcockpit.JobLogDialog"
    }
}

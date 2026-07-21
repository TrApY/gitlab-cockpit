package dev.jota.gitlabcockpit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.ui.components.JBTabbedPane
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.StageGroup
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A non-modal viewer for every job in a pipeline [stage]: one [JobLogConsole] per job, each in its
 * own tab of a [JBTabbedPane]. Every tab streams independently — all consoles are started when the
 * dialog opens — and a tab's title (`job-name (status)`) is refreshed to the final status when that
 * job's streaming ends.
 *
 * Opened from [PipelinesPanel] for stages with more than one job; a single-job stage opens the plain
 * [JobLogDialog] instead. Offers only a Close button and remembers its size via
 * [getDimensionServiceKey].
 */
class StageLogsDialog(
    project: Project,
    service: CockpitProjectService,
    projectId: Long,
    private val stage: StageGroup,
) : DialogWrapper(project, false, IdeModalityType.MODELESS) {

    private val tabbedPane = JBTabbedPane()
    private val consoles = mutableListOf<JobLogConsole>()

    init {
        title = CockpitBundle.message("log.stage.title", stage.name)
        cancelAction.putValue(Action.NAME, CockpitBundle.message("log.close"))
        stage.jobs.forEachIndexed { index, job ->
            val console = JobLogConsole(project, service, projectId, job, disposable) { status ->
                tabbedPane.setTitleAt(index, CockpitBundle.message("log.tab.title", job.name, status))
            }
            consoles.add(console)
            tabbedPane.addTab(CockpitBundle.message("log.tab.title", job.name, job.status), console.component)
        }
        init()
        consoles.forEach { it.start() }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(tabbedPane, BorderLayout.CENTER)
        panel.preferredSize = CockpitTheme.LOG_DIALOG_SIZE
        return panel
    }

    /** Only a Close button (the renamed cancel action); no OK. */
    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    companion object {
        private const val DIMENSION_KEY = "dev.jota.gitlabcockpit.StageLogsDialog"
    }
}

package dev.jota.gitlabcockpit.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.isJobCancelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A non-modal viewer for a single CI job's log, built on the IDE [ConsoleView] (which renders ANSI
 * color escapes on its own). Opened from [PipelinesPanel] by double-clicking a job node or via the
 * "View log" context-menu action.
 *
 * On open it loads the full trace (offset 0) and prints it. If the job is not yet terminal
 * ([isJobCancelable] — created / pending / running) it then **streams**: every
 * [POLL_INTERVAL_MS] ms it polls the trace from the current offset and prints only the new fragment,
 * and every [STATUS_POLL_EVERY] iterations it polls the job's status; once the job leaves the
 * cancelable set it does one last trace pass, updates the title to the final status and stops.
 * Repeated network failures ([MAX_CONSECUTIVE_FAILURES] in a row) print a notice and stop streaming.
 *
 * All network work runs on the service's coroutine scope (never the EDT); the streaming [Job] is
 * tied to the dialog's [disposable] so it is cancelled when the dialog closes. Prints are marshaled
 * to the EDT for consistency (though [ConsoleView.print] is itself thread-safe). The dialog offers
 * only a Close button and remembers its size via [getDimensionServiceKey].
 */
class JobLogDialog(
    private val project: Project,
    private val service: CockpitProjectService,
    private val job: GitLabJob,
) : DialogWrapper(project, false, IdeModalityType.MODELESS) {

    private val console: ConsoleView =
        TextConsoleBuilderFactory.getInstance().createBuilder(project).console

    private var streamJob: Job? = null

    init {
        title = CockpitBundle.message("log.title", job.name, job.status)
        cancelAction.putValue(Action.NAME, CockpitBundle.message("log.close"))
        init()
        Disposer.register(disposable, console)
        Disposer.register(disposable) { streamJob?.cancel() }
        startStreaming()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(console.component, BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(900, 600)
        return panel
    }

    /** Only a Close button (the renamed cancel action); no OK. */
    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    private fun startStreaming() {
        streamJob = service.coroutineScope.launch {
            var offset = 0L
            when (val first = service.getJobTrace(job.id, 0L)) {
                is GitLabResult.Success -> {
                    printNormal(first.data.content)
                    offset = first.data.nextOffset
                }
                else -> {
                    printError(CockpitBundle.message("log.error.load", describe(first)))
                    return@launch
                }
            }

            // Already finished when opened: show the full log and stop, no streaming.
            if (!isJobCancelable(job.status)) return@launch

            var consecutiveFailures = 0
            var iteration = 0
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                iteration++
                when (val chunk = service.getJobTrace(job.id, offset)) {
                    is GitLabResult.Success -> {
                        consecutiveFailures = 0
                        if (chunk.data.content.isNotEmpty()) printNormal(chunk.data.content)
                        offset = chunk.data.nextOffset
                    }
                    else -> {
                        consecutiveFailures++
                        printError(CockpitBundle.message("log.streaming.error", describe(chunk)))
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            printError(CockpitBundle.message("log.streaming.stopped"))
                            return@launch
                        }
                        continue
                    }
                }
                if (iteration % STATUS_POLL_EVERY == 0) {
                    val jobResult = service.getJob(job.id)
                    if (jobResult is GitLabResult.Success && !isJobCancelable(jobResult.data.status)) {
                        // Final trace pass to capture the tail written after the last poll, then stop.
                        val tail = service.getJobTrace(job.id, offset)
                        if (tail is GitLabResult.Success && tail.data.content.isNotEmpty()) {
                            printNormal(tail.data.content)
                        }
                        updateTitle(jobResult.data.name, jobResult.data.status)
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun printNormal(text: String) = withContext(Dispatchers.EDT) {
        console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
    }

    private suspend fun printError(text: String) = withContext(Dispatchers.EDT) {
        console.print(text + "\n", ConsoleViewContentType.ERROR_OUTPUT)
    }

    private suspend fun updateTitle(name: String, status: String) = withContext(Dispatchers.EDT) {
        title = CockpitBundle.message("log.title", name, status)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1500L
        private const val STATUS_POLL_EVERY = 3
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val DIMENSION_KEY = "dev.jota.gitlabcockpit.JobLogDialog"

        private fun describe(result: GitLabResult<*>): String = when (result) {
            is GitLabResult.HttpError -> "HTTP ${result.status}"
            is GitLabResult.NetworkError -> result.cause.message ?: result.cause.javaClass.simpleName
            is GitLabResult.Success<*> -> ""
        }
    }
}

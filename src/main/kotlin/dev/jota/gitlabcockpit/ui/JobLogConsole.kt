package dev.jota.gitlabcockpit.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.api.GitLabResult
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.JobTraceProcessor
import dev.jota.gitlabcockpit.core.isJobCancelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

/**
 * A reusable "one console + streaming of one job" component built on the IDE [ConsoleView]. GitLab's
 * raw trace is not printed directly: it is first run through a per-console [JobTraceProcessor] (which
 * strips GitLab 17+ timestamped prefixes, joins continuation lines, drops section markers and removes
 * non-SGR ANSI while keeping color escapes), and the cleaned text is then colorized by a per-console
 * [AnsiEscapeDecoder] that maps each SGR run to a [ConsoleViewContentType] before printing — the plain
 * [ConsoleView] does not interpret ANSI on its own. Owners embed [component] wherever they need it and
 * call [start] once to begin loading and streaming the job's trace.
 *
 * On [start] it loads the full trace (offset 0) and prints it. If the job is not yet terminal
 * ([isJobCancelable] — created / pending / running) it then **streams**: every [POLL_INTERVAL_MS] ms
 * it polls the trace from the current offset and prints only the new fragment, and every
 * [STATUS_POLL_EVERY] iterations it polls the job's status; once the job leaves the cancelable set it
 * does one last trace pass, reports the final status through [onStatusChange] and stops. Repeated
 * network failures ([MAX_CONSECUTIVE_FAILURES] in a row) print a notice and stop streaming. The trace
 * processor buffers the last (still-incomplete) logical line, so streaming ends with a [flushLog] to
 * emit whatever it was holding.
 *
 * All network work runs on the service's coroutine scope (never the EDT); the console and the
 * streaming [Job] are registered on [parentDisposable], so both are cleaned up when the owner is
 * disposed. Prints are marshaled to the EDT for consistency (though [ConsoleView.print] is itself
 * thread-safe).
 *
 * @param onStatusChange invoked on the EDT with the job's final status when streaming ends because
 *   the job became terminal; used by owners to update titles / tab labels.
 */
class JobLogConsole(
    private val project: Project,
    private val service: CockpitProjectService,
    private val projectId: Long,
    private val job: GitLabJob,
    parentDisposable: Disposable,
    private val onStatusChange: ((String) -> Unit)? = null,
) {

    private val console: ConsoleView =
        TextConsoleBuilderFactory.getInstance().createBuilder(project).console

    /** Stateful cleaner for this console's trace; instantiated per console so its buffers stay isolated. */
    private val processor = JobTraceProcessor()

    /** Turns the processor's SGR-bearing text into colored [ConsoleView] prints; also per console. */
    private val ansiDecoder = AnsiEscapeDecoder()

    private var streamJob: Job? = null

    /** The Swing component to embed; owned by this console and disposed with [parentDisposable]. */
    val component: JComponent get() = console.component

    init {
        Disposer.register(parentDisposable, console)
        Disposer.register(parentDisposable) { streamJob?.cancel() }
    }

    /** Loads the initial trace and, if the job is not terminal, begins streaming. Call once. */
    fun start() {
        streamJob = service.coroutineScope.launch {
            var offset = 0L
            when (val first = service.getJobTrace(projectId, job.id, 0L)) {
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
            if (!isJobCancelable(job.status)) {
                flushLog()
                return@launch
            }

            var consecutiveFailures = 0
            var iteration = 0
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                iteration++
                when (val chunk = service.getJobTrace(projectId, job.id, offset)) {
                    is GitLabResult.Success -> {
                        consecutiveFailures = 0
                        if (chunk.data.content.isNotEmpty()) printNormal(chunk.data.content)
                        offset = chunk.data.nextOffset
                    }
                    else -> {
                        consecutiveFailures++
                        printError(CockpitBundle.message("log.streaming.error", describe(chunk)))
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            flushLog()
                            printError(CockpitBundle.message("log.streaming.stopped"))
                            return@launch
                        }
                        continue
                    }
                }
                if (iteration % STATUS_POLL_EVERY == 0) {
                    val jobResult = service.getJob(projectId, job.id)
                    if (jobResult is GitLabResult.Success && !isJobCancelable(jobResult.data.status)) {
                        // Final trace pass to capture the tail written after the last poll, then stop.
                        val tail = service.getJobTrace(projectId, job.id, offset)
                        if (tail is GitLabResult.Success && tail.data.content.isNotEmpty()) {
                            printNormal(tail.data.content)
                        }
                        flushLog()
                        notifyStatus(jobResult.data.status)
                        return@launch
                    }
                }
            }
        }
    }

    /** Feeds a raw trace chunk through the processor and prints whatever complete lines it emits. */
    private suspend fun printNormal(text: String) = emitProcessed(processor.feed(text))

    /** Emits the last logical line the processor is still holding; call once the stream ends. */
    private suspend fun flushLog() = emitProcessed(processor.flush())

    /**
     * Colorizes the processor's SGR-bearing output with [ansiDecoder] and prints each run on the EDT
     * under the [ConsoleViewContentType] its ANSI attributes map to. Empty text is a no-op so a chunk
     * that produced only buffered (not-yet-complete) lines prints nothing.
     */
    private suspend fun emitProcessed(text: String) {
        if (text.isEmpty()) return
        withContext(Dispatchers.EDT) {
            ansiDecoder.escapeText(text, ProcessOutputTypes.STDOUT) { chunk, key ->
                console.print(chunk, ConsoleViewContentType.getConsoleViewType(key))
            }
        }
    }

    private suspend fun printError(text: String) = withContext(Dispatchers.EDT) {
        console.print(text + "\n", ConsoleViewContentType.ERROR_OUTPUT)
    }

    private suspend fun notifyStatus(status: String) = withContext(Dispatchers.EDT) {
        onStatusChange?.invoke(status)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1500L
        private const val STATUS_POLL_EVERY = 3
        private const val MAX_CONSECUTIVE_FAILURES = 5

        private fun describe(result: GitLabResult<*>): String = when (result) {
            is GitLabResult.HttpError -> "HTTP ${result.status}"
            is GitLabResult.NetworkError -> result.cause.message ?: result.cause.javaClass.simpleName
            is GitLabResult.Success<*> -> ""
        }
    }
}

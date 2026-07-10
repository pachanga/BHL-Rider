package com.bitdotgames.bhl.rider.lsp

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.eclipse.lsp4j.MessageType
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

private val LOG = logger<BhlLspConsoleService>()

/**
 * Owns the console shown in the "BHL LSP" tool window and receives log lines from the BHL
 * language server. Lines that arrive before the console UI exists are buffered (capped) and
 * flushed once the tool window is created.
 */
@Service(Service.Level.PROJECT)
class BhlLspConsoleService(private val project: Project) : Disposable {
    @Volatile
    private var console: ConsoleView? = null
    private val pending = ConcurrentLinkedQueue<Pair<ConsoleViewContentType, String>>()
    private val autoShown = AtomicBoolean(false)

    /** Called by the tool window factory (on EDT) once the console UI exists. */
    fun attachConsole(view: ConsoleView) {
        console = view
        while (true) {
            val (type, text) = pending.poll() ?: break
            view.print(text, type)
        }
    }

    fun logServerMessage(type: MessageType, message: String) =
        append(contentTypeFor(type), "[${type.name.lowercase()}] $message")

    /**
     * [reveal] controls whether this line may auto-open the tool window (first line only).
     * Pass `false` for lines that fire in projects with no BHL activity, so the panel does
     * not pop up in unrelated projects.
     */
    fun logInfo(message: String, reveal: Boolean = true) =
        append(ConsoleViewContentType.LOG_INFO_OUTPUT, "[info] $message", reveal = reveal)

    fun logTrace(message: String) = append(ConsoleViewContentType.LOG_VERBOSE_OUTPUT, "[trace] $message")

    /** Mirrors a platform LSP log record (already in idea.log) — don't re-mirror it there. */
    fun logPlatform(level: Level, message: String) {
        val contentType = when {
            level.intValue() >= Level.SEVERE.intValue() -> ConsoleViewContentType.LOG_ERROR_OUTPUT
            level.intValue() >= Level.WARNING.intValue() -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            else -> ConsoleViewContentType.LOG_VERBOSE_OUTPUT
        }
        // reveal=false: these records can come from any LSP server in any project.
        append(contentType, "[lsp] $message", mirrorToIdeaLog = false, reveal = false)
    }

    /**
     * Logs one raw JSON-RPC frame ([direction] is "-->" client→server or "<--" server→client).
     * Not mirrored to idea.log (frames are large and frequent) and never reveals the panel.
     */
    fun logWire(direction: String, frame: String) =
        append(ConsoleViewContentType.LOG_VERBOSE_OUTPUT, "$direction $frame", mirrorToIdeaLog = false, reveal = false)

    /** Prints a raw chunk of the server's stderr as-is (it already carries its own newlines). */
    fun printServerStderr(text: String) = printRaw(ConsoleViewContentType.LOG_ERROR_OUTPUT, text)

    private fun printRaw(contentType: ConsoleViewContentType, text: String) {
        LOG.info(text.trimEnd())
        val view = console
        if (view != null) {
            view.print(text, contentType)
        } else {
            pending.add(contentType to text)
            while (pending.size > MAX_PENDING) pending.poll()
        }
        ensureVisibleOnce()
    }

    private fun append(
        contentType: ConsoleViewContentType,
        rawLine: String,
        mirrorToIdeaLog: Boolean = true,
        reveal: Boolean = true,
    ) {
        // Mirror into idea.log so the trail survives even if the console UI isn't visible.
        if (mirrorToIdeaLog) LOG.info(rawLine)
        val line = if (rawLine.endsWith("\n")) rawLine else "$rawLine\n"
        val view = console
        if (view != null) {
            view.print(line, contentType)
        } else {
            pending.add(contentType to line)
            while (pending.size > MAX_PENDING) pending.poll()
        }
        if (reveal) ensureVisibleOnce()
    }

    /** Reveal the tool window once, on the first message, without stealing focus afterwards. */
    private fun ensureVisibleOnce() {
        if (!autoShown.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show(null)
            }
        }
    }

    private fun contentTypeFor(type: MessageType) = when (type) {
        MessageType.Error -> ConsoleViewContentType.LOG_ERROR_OUTPUT
        MessageType.Warning -> ConsoleViewContentType.LOG_WARNING_OUTPUT
        MessageType.Info -> ConsoleViewContentType.LOG_INFO_OUTPUT
        else -> ConsoleViewContentType.LOG_VERBOSE_OUTPUT
    }

    override fun dispose() {
        // The ConsoleView is disposed together with the tool window content.
    }

    companion object {
        const val TOOL_WINDOW_ID = "BHL LSP"
        private const val MAX_PENDING = 500

        fun getInstance(project: Project): BhlLspConsoleService = project.service()
    }
}

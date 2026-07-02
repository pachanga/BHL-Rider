package com.bitdotgames.bhl.rider.lsp

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.eclipse.lsp4j.MessageType
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the console shown in the "BHL LSP" tool window and receives log lines from the BHL
 * language server. Lines that arrive before the console UI exists are buffered (capped) and
 * flushed once the tool window is created.
 */
@Service(Service.Level.PROJECT)
class BhlLspConsoleService(private val project: Project) {
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

    fun logInfo(message: String) = append(ConsoleViewContentType.LOG_INFO_OUTPUT, "[info] $message")

    fun logTrace(message: String) = append(ConsoleViewContentType.LOG_VERBOSE_OUTPUT, "[trace] $message")

    private fun append(contentType: ConsoleViewContentType, rawLine: String) {
        val line = if (rawLine.endsWith("\n")) rawLine else "$rawLine\n"
        val view = console
        if (view != null) {
            view.print(line, contentType)
        } else {
            pending.add(contentType to line)
            while (pending.size > MAX_PENDING) pending.poll()
        }
        ensureVisibleOnce()
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

    companion object {
        const val TOOL_WINDOW_ID = "BHL LSP"
        private const val MAX_PENDING = 500

        fun getInstance(project: Project): BhlLspConsoleService = project.service()
    }
}

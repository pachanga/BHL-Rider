package com.bitdotgames.bhl.rider.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Root category of the platform's built-in LSP client. IntelliJ's Logger.getInstance(Class)
 * names JUL loggers "#" + FQN, so the '#' prefix is required — without it the handler sits
 * on a logger outside the "#com.intellij..." parent chain and never receives records.
 */
private const val PLATFORM_LSP_CATEGORY = "#com.intellij.platform.lsp"

/**
 * Bridges IntelliJ's platform LSP logging (the `com.intellij.platform.lsp.*` loggers, which
 * are JUL-backed) into the "BHL LSP" console, so Rider's own view of the LSP client — server
 * start/stop, init timeouts, the kill that produced exit 137, etc. — is visible in the tab.
 *
 * INFO/WARN/SEVERE records show without extra setup; enabling DEBUG for
 * `com.intellij.platform.lsp` in Help ▸ Diagnostic Tools ▸ Debug Log Settings makes the
 * finer records flow here too. Note: this captures ALL LSP servers, not just BHL — in Rider
 * that is normally only the BHL server.
 */
class BhlPlatformLspLogBridge private constructor(private val project: Project) : Handler() {
    override fun publish(record: LogRecord) {
        val name = record.loggerName ?: return
        if (!name.startsWith(PLATFORM_LSP_CATEGORY)) return
        if (project.isDisposed) return
        val message = record.message ?: return
        val short = name.substringAfterLast('.')
        val console = BhlLspConsoleService.getInstance(project)
        console.logPlatform(record.level, "$short: $message")
        record.thrown?.let { console.logPlatform(Level.SEVERE, it.stackTraceToString()) }
    }

    override fun flush() {}

    override fun close() {}

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(BhlPlatformLspLogBridge::class.java)

        /** Attaches the bridge for [project]; auto-detaches when [parent] is disposed. */
        fun install(project: Project, parent: Disposable) {
            runCatching {
                val logger = Logger.getLogger(PLATFORM_LSP_CATEGORY)
                val handler = BhlPlatformLspLogBridge(project).apply { level = Level.ALL }
                logger.addHandler(handler)
                Disposable { logger.removeHandler(handler) }.also {
                    com.intellij.openapi.util.Disposer.register(parent, it)
                }
            }.onFailure {
                LOG.warn("Failed to install platform LSP log bridge; [lsp] console mirroring disabled", it)
            }
        }
    }
}

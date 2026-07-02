package com.bitdotgames.bhl.rider.lsp

import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import org.eclipse.lsp4j.LogTraceParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import java.util.concurrent.CompletableFuture

/**
 * Wraps the platform's [LspServerNotificationsHandler], mirroring the BHL server's
 * log/message/diagnostics notifications into the "BHL LSP" console before delegating to the
 * real handler (so normal IDE behaviour — balloons, diagnostics, etc. — is preserved).
 */
class BhlLoggingNotificationsHandler(
    private val delegate: LspServerNotificationsHandler,
    private val console: BhlLspConsoleService,
) : LspServerNotificationsHandler by delegate {

    override fun logMessage(params: MessageParams) {
        console.logServerMessage(params.type, params.message)
        delegate.logMessage(params)
    }

    override fun showMessage(params: MessageParams) {
        console.logServerMessage(params.type, "showMessage: ${params.message}")
        delegate.showMessage(params)
    }

    override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
        console.logServerMessage(params.type, "showMessageRequest: ${params.message}")
        return delegate.showMessageRequest(params)
    }

    override fun logTrace(params: LogTraceParams) {
        val verbose = params.verbose?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""
        console.logTrace(params.message + verbose)
        delegate.logTrace(params)
    }

    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        console.logInfo("diagnostics: ${params.diagnostics.size} for ${params.uri}")
        delegate.publishDiagnostics(params)
    }
}

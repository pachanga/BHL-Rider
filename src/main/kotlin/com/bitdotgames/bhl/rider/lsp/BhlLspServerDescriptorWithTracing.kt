package com.bitdotgames.bhl.rider.lsp

import com.bitdotgames.bhl.rider.settings.BhlSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.impl.connector.LspCommunicationLogger
import com.intellij.platform.lsp.impl.connector.LspCommunicationLoggerProvider
import java.nio.file.Path

// Deliberately NOT `logger<BhlLspServerDescriptorWithTracing>()`: that reified generic call
// resolves `BhlLspServerDescriptorWithTracing::class.java` right here, in a top-level property
// initializer that runs unconditionally in this file's <clinit> the moment anything in this
// file is touched (including newBhlLspServerDescriptor() below) — which forces classloading of
// the tracing subclass (and therefore the unstable interface it implements) regardless of the
// reflective guard, defeating the whole point of this file. A plain string category has no
// such effect.
private val LOG = Logger.getInstance("com.bitdotgames.bhl.rider.lsp.BhlLspServerDescriptorWithTracing")

/** Console truncation for traced JSON-RPC frames (semantic-token responses can be huge). */
private const val MAX_TRACED_FRAME_LENGTH = 10_000

/**
 * Builds a [BhlLspServerDescriptor], upgraded with JSON-RPC traffic tracing when the internal
 * platform API it relies on is present on this IDE build.
 *
 * This file is the *only* place in the plugin that references
 * `com.intellij.platform.lsp.impl.connector.*` — internal, unstable IntelliJ Platform API, not
 * part of the public plugin SDK. It was found present via decompilation on one IDE build, but
 * on at least one other install that exact class wasn't resolvable, which crashed loading of
 * the descriptor class entirely (and therefore the whole plugin) with a PluginException: a
 * class fails to load the moment any interface in its own supertype list can't be resolved.
 *
 * Checking for the class with reflection first, and only referencing it from a subclass that
 * is never even touched (and therefore never classloaded) unless the check passes, means the
 * base descriptor — and everything else the plugin does — keeps working regardless of whether
 * this specific internal class exists on a given IDE version; only tracing itself becomes
 * unavailable there.
 */
fun newBhlLspServerDescriptor(project: Project, workDir: Path): BhlLspServerDescriptor =
    if (isTracingSupportAvailable) BhlLspServerDescriptorWithTracing(project, workDir) else BhlLspServerDescriptor(project, workDir)

private val isTracingSupportAvailable: Boolean by lazy {
    try {
        // Use the always-loadable base descriptor's classloader, not the tracing subclass's —
        // referencing the subclass itself here is exactly the mistake explained above.
        Class.forName(
            "com.intellij.platform.lsp.impl.connector.LspCommunicationLoggerProvider",
            false,
            BhlLspServerDescriptor::class.java.classLoader,
        )
        true
    } catch (e: Throwable) {
        LOG.info("BHL LSP traffic tracing unavailable on this IDE (internal API not found): $e")
        false
    }
}

private class BhlLspServerDescriptorWithTracing(project: Project, workDir: Path) :
    BhlLspServerDescriptor(project, workDir), LspCommunicationLoggerProvider {

    /**
     * Receives every raw JSON-RPC frame from the platform's connector (which checks
     * `descriptor is LspCommunicationLoggerProvider`). The "Trace LSP traffic" setting is
     * read per frame, so toggling it applies without a server restart. When tracing is off,
     * frames still go to LOG.debug — enable this class's category in Debug Log Settings to
     * get them in idea.log (this replaces the connector's own default debug logging).
     *
     * Fires for as long as the server is alive, which can outlast a dynamic plugin reload
     * (installing an updated build without restarting the IDE): the project-service lookups
     * below may then resolve against a *different* PluginClassLoader than the one this
     * connected server was itself loaded by, throwing `ClassCastException: class X cannot be
     * cast to class X` for what looks like the same class. `runCatching` keeps that
     * (console-logging-only, non-critical) failure from surfacing as an unhandled exception.
     */
    override fun createCommunicationLogger(): LspCommunicationLogger = object : LspCommunicationLogger {
        override fun logInbound(message: CharSequence) {
            logFrame("<--", message)
        }

        override fun logOutbound(message: CharSequence) {
            logFrame("-->", message)
        }

        private fun logFrame(direction: String, message: CharSequence) = runCatching {
            if (BhlSettings.getInstance(project).traceLsp) {
                val text = if (message.length > MAX_TRACED_FRAME_LENGTH) {
                    "${message.subSequence(0, MAX_TRACED_FRAME_LENGTH)}… [truncated, ${message.length} chars]"
                } else {
                    message.toString()
                }
                BhlLspConsoleService.getInstance(project).logWire(direction, text)
            } else {
                LOG.debug { "$direction $message" }
            }
        }
    }
}

package com.bitdotgames.bhl.rider.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManagerListener

/**
 * Mirrors client-side LSP lifecycle into the "BHL LSP" console: server state transitions
 * (Initializing → Running → Shutdown…) and documents the client opens for the server.
 *
 * This listener is registered once per project session and can outlive a dynamic plugin
 * reload (e.g. installing an updated build without restarting the IDE): each call below
 * re-fetches the project service, which after a reload may come from a *different*
 * PluginClassLoader than the one this listener instance was itself loaded by, causing a
 * `ClassCastException: class X cannot be cast to class X` for what looks like the same
 * class. `runCatching` keeps that (console-logging-only, non-critical) failure from
 * surfacing as an unhandled exception.
 */
class BhlLspManagerListener(private val project: Project) : LspServerManagerListener {
    private val console get() = BhlLspConsoleService.getInstance(project)

    private fun isBhlServer(server: LspServer) =
        server.providerClass == BhlLspServerSupportProvider::class.java

    override fun serverStateChanged(lspServer: LspServer) {
        if (!isBhlServer(lspServer)) return
        runCatching { console.logInfo("server state -> ${lspServer.state}") }
    }

    override fun fileOpened(lspServer: LspServer, file: VirtualFile) {
        if (!isBhlServer(lspServer)) return
        runCatching { console.logInfo("didOpen ${file.name}") }
    }
}

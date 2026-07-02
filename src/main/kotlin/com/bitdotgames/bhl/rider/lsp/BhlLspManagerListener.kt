package com.bitdotgames.bhl.rider.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManagerListener

/**
 * Mirrors client-side LSP lifecycle into the "BHL LSP" console: server state transitions
 * (Initializing → Running → Shutdown…) and documents the client opens for the server.
 */
class BhlLspManagerListener(private val project: Project) : LspServerManagerListener {
    private val console get() = BhlLspConsoleService.getInstance(project)

    private fun isBhlServer(server: LspServer) =
        server.providerClass == BhlLspServerSupportProvider::class.java

    override fun serverStateChanged(lspServer: LspServer) {
        if (!isBhlServer(lspServer)) return
        console.logInfo("server state -> ${lspServer.state}")
    }

    override fun fileOpened(lspServer: LspServer, file: VirtualFile) {
        if (!isBhlServer(lspServer)) return
        console.logInfo("didOpen ${file.name}")
    }
}

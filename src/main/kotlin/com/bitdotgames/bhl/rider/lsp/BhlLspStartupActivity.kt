package com.bitdotgames.bhl.rider.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.lsp.api.LspServerManager

/** Registers the BHL LSP manager listener so console logging works from the first server event. */
class BhlLspStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val console = BhlLspConsoleService.getInstance(project)
        console.logInfo("BHL plugin initialized; open a .bhl file to start the language server")
        LspServerManager.getInstance(project)
            .addLspServerManagerListener(BhlLspManagerListener(project), console, true)
    }
}

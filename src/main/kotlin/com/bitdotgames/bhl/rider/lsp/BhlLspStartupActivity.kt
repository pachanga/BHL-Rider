package com.bitdotgames.bhl.rider.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.lsp.api.LspServerManager

/** Registers the BHL LSP manager listener so console logging works from the first server event. */
class BhlLspStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val console = BhlLspConsoleService.getInstance(project)
        // reveal=false: this line fires in every project, BHL or not — it must not pop the
        // tool window in unrelated projects. It still lands in the console buffer/idea.log.
        console.logInfo("BHL plugin initialized; open a .bhl file to start the language server", reveal = false)
        LspServerManager.getInstance(project)
            .addLspServerManagerListener(BhlLspManagerListener(project), console, true)
        // Mirror Rider's own platform LSP logs into the BHL LSP console.
        BhlPlatformLspLogBridge.install(project, console)
    }
}

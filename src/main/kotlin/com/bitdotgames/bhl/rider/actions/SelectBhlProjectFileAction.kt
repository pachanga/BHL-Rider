package com.bitdotgames.bhl.rider.actions

import com.bitdotgames.bhl.rider.lsp.BhlLspServerSupportProvider
import com.bitdotgames.bhl.rider.lsp.BhlProjectFileResolver
import com.bitdotgames.bhl.rider.settings.BhlSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerManager

class SelectBhlProjectFileAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = BhlProjectFileResolver.findProjectFiles(project)
        if (files.isEmpty()) {
            return
        }
        BhlProjectFileResolver.promptForChoice(files) { chosen ->
            // Persist the pick as the top-priority "BHL project directory" override so it
            // reliably wins over walk-up and project-wide discovery.
            BhlSettings.getInstance(project).projectDirectory = chosen.parent.path
            restartLspServer(project)
        }
    }

    private fun restartLspServer(project: Project) {
        val manager = LspServerManager.getInstance(project)
        manager.stopAndRestartIfNeeded(BhlLspServerSupportProvider::class.java)
    }
}

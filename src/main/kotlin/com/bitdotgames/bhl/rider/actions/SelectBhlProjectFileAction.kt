package com.bitdotgames.bhl.rider.actions

import com.bitdotgames.bhl.rider.lsp.BhlLspServerSupportProvider
import com.bitdotgames.bhl.rider.lsp.BhlProjectFileResolver
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
        BhlProjectFileResolver.promptForChoice(project, files) {
            restartLspServer(project)
        }
    }

    private fun restartLspServer(project: Project) {
        val manager = LspServerManager.getInstance(project)
        manager.stopAndRestartIfNeeded(BhlLspServerSupportProvider::class.java)
    }
}

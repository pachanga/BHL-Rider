package com.bitdotgames.bhl.rider.actions

import com.bitdotgames.bhl.rider.lsp.BhlLspServerSupportProvider
import com.bitdotgames.bhl.rider.lsp.BhlProjectFileResolver
import com.bitdotgames.bhl.rider.settings.BhlSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project

class SelectBhlProjectFileAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // If the IDE already indexed bhl.proj files, offer a quick pick among them.
        val discovered = BhlProjectFileResolver.findProjectFiles(project)
        if (discovered.isNotEmpty()) {
            BhlProjectFileResolver.promptForChoice(project, discovered) { chosen ->
                applyChoice(project, chosen.parent.path)
            }
            return
        }

        // Otherwise (e.g. a C# solution with BHL scripts outside the indexed content),
        // fall back to a file browser so the action always does something visible.
        val descriptor = FileChooserDescriptor(true, true, false, false, false, false)
            .withTitle("Select BHL Project")
            .withDescription("Choose bhl.proj (or the directory that contains it)")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val dir = if (chosen.isDirectory) chosen else chosen.parent
        applyChoice(project, dir.path)
    }

    /** Persist the pick as the top-priority "BHL project directory" override and restart. */
    private fun applyChoice(project: Project, directoryPath: String) {
        BhlSettings.getInstance(project).projectDirectory = directoryPath
        restartLspServer(project)
    }

    private fun restartLspServer(project: Project) {
        com.intellij.platform.lsp.api.LspServerManager.getInstance(project)
            .stopAndRestartIfNeeded(BhlLspServerSupportProvider::class.java)
    }
}

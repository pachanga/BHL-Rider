package com.bitdotgames.bhl.rider.lsp

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class BhlLspConsoleToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        BhlLspConsoleService.getInstance(project).attachConsole(console)

        val content = ContentFactory.getInstance().createContent(console.component, "", false)
        content.setDisposer(console)
        toolWindow.contentManager.addContent(content)
    }
}

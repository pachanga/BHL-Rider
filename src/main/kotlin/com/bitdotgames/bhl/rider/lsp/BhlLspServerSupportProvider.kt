@file:Suppress("UnstableApiUsage")

package com.bitdotgames.bhl.rider.lsp

import com.bitdotgames.bhl.rider.BhlFileType
import com.bitdotgames.bhl.rider.BhlIcons
import com.bitdotgames.bhl.rider.settings.BhlSettings
import com.bitdotgames.bhl.rider.settings.BhlSettingsConfigurable
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem
import org.eclipse.lsp4j.InitializeResult
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class BhlLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (file.fileType != BhlFileType) return
        BhlProjectFileResolver.resolveWorkingDirectory(project, file) { workDir ->
            serverStarter.ensureServerStarted(BhlLspServerDescriptor(project, workDir))
        }
    }

    override fun createLspServerWidgetItem(lspServer: LspServer, currentFile: VirtualFile?): LspServerWidgetItem =
        LspServerWidgetItem(lspServer, currentFile, BhlIcons.FILE, BhlSettingsConfigurable::class.java)
}

class BhlLspServerDescriptor(project: Project, private val workDir: Path) :
    ProjectWideLspServerDescriptor(project, "BHL Language Server") {

    override fun isSupportedFile(file: VirtualFile): Boolean = file.fileType == BhlFileType

    override fun createCommandLine(): GeneralCommandLine {
        val settings = BhlSettings.getInstance(project)
        val executablePath = settings.executablePath.ifBlank { "bhl" }

        // Java can't exec a .bat file directly; mirror the VSCode client's `shell: true`
        // fallback by routing it through cmd.exe on Windows.
        val isWindowsBatch = System.getProperty("os.name").lowercase().startsWith("windows") &&
            executablePath.lowercase().endsWith(".bat")

        val commandLine = if (isWindowsBatch) {
            GeneralCommandLine("cmd.exe", "/c", executablePath)
        } else {
            GeneralCommandLine(executablePath)
        }

        commandLine.addParameter("lsp")
        if (settings.logFile.isNotBlank()) {
            commandLine.addParameter("--log-file=${settings.logFile}")
        }

        commandLine.withWorkDirectory(workDir.toFile())
        commandLine.withCharset(StandardCharsets.UTF_8)

        if (settings.forceRebuild) {
            commandLine.withEnvironment(mapOf("BHL_REBUILD" to "1", "BHL_SILENT" to "1"))
        }

        BhlLspConsoleService.getInstance(project)
            .logInfo("launching: ${commandLine.commandLineString} (cwd=$workDir)")

        return commandLine
    }

    /** Route the BHL server's notifications into the "BHL LSP" console. */
    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        super.createLsp4jClient(
            BhlLoggingNotificationsHandler(handler, BhlLspConsoleService.getInstance(project)),
        )

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(result: InitializeResult) {
            val info = result.serverInfo
            val suffix = info?.let { ": ${it.name} ${it.version ?: ""}".trimEnd() } ?: ""
            BhlLspConsoleService.getInstance(project).logInfo("server initialized$suffix")
        }

        override fun serverStopped(crashed: Boolean) {
            BhlLspConsoleService.getInstance(project)
                .logInfo(if (crashed) "server crashed" else "server stopped")
        }
    }
}

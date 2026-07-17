package com.bitdotgames.bhl.rider.lsp

import com.bitdotgames.bhl.rider.settings.BhlSettings
import com.bitdotgames.bhl.rider.settings.BhlSettingsChangeListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerManager
import java.nio.file.Paths

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
        installBhlDocumentListener(project, console)
        installBhlSharedFileSelectionListener(project, console)

        // SETTINGS_CHANGED_TOPIC was being published (from Settings apply() and immediately on
        // a successful binary download) but had no subscriber anywhere — so executablePath,
        // forceRebuild, useCustomInstallation and the downloaded binary path all silently had
        // no effect on an already-running server until the whole IDE was restarted. Restart via
        // the same stopAndRestartIfNeeded already used by SelectBhlProjectFileAction.
        project.messageBus.connect(console).subscribe(
            BhlSettings.SETTINGS_CHANGED_TOPIC,
            object : BhlSettingsChangeListener {
                override fun settingsChanged() {
                    console.logInfo("settings changed — restarting BHL server if running", reveal = false)
                    LspServerManager.getInstance(project).stopAndRestartIfNeeded(BhlLspServerSupportProvider::class.java)
                }
            },
        )

        // Eagerly learn about every bhl.proj in the project (not just the ones a file has been
        // opened under yet) so shared-directory ambiguity between them can be detected from
        // the start, rather than only once each project's own files have been visited.
        // runReadActionInSmartMode: findProjectFiles's FilenameIndex search isn't dumb-mode
        // safe, and this runs the moment the project opens — right when indexing is most
        // likely still in progress.
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val files = DumbService.getInstance(project).runReadActionInSmartMode<List<VirtualFile>> {
                    BhlProjectFileResolver.findProjectFiles(project)
                }
                console.logInfo("eager bhl.proj discovery: found ${files.size}: ${files.map { it.path }}", reveal = false)
                val cache = BhlResolvedProjectsCache.getInstance(project)
                files.forEach { cache.remember(Paths.get(it.parent.path)) }
            }.onFailure {
                console.logInfo("eager bhl.proj discovery failed: $it", reveal = false)
            }
        }
    }
}

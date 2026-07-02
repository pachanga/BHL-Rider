package com.bitdotgames.bhl.rider.lsp

import com.bitdotgames.bhl.rider.settings.BhlSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Path
import java.nio.file.Paths

const val BHL_PROJECT_FILE_NAME = "bhl.proj"

/**
 * Locates `bhl.proj` in the project and resolves the directory the BHL LSP server
 * should be launched from, mirroring the project-file discovery in BHL-VSCode.
 */
object BhlProjectFileResolver {
    private val LOG = logger<BhlProjectFileResolver>()

    fun findProjectFiles(project: Project): List<VirtualFile> =
        ReadAction.compute<List<VirtualFile>, Throwable> {
            FilenameIndex.getVirtualFilesByName(BHL_PROJECT_FILE_NAME, GlobalSearchScope.projectScope(project))
                .sortedBy { it.path }
        }

    /**
     * Resolves the working directory to launch the LSP server in.
     * Invokes [onResolved] once determined; does nothing if no `bhl.proj` exists in the
     * project or the user dismisses the disambiguation prompt.
     */
    fun resolveWorkingDirectory(project: Project, onResolved: (Path) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val files = findProjectFiles(project)
            when {
                files.isEmpty() ->
                    LOG.info("No $BHL_PROJECT_FILE_NAME found in project ${project.name}; BHL LSP server will not start.")

                files.size == 1 -> onResolved(Paths.get(files[0].parent.path))

                else -> {
                    val settings = BhlSettings.getInstance(project)
                    val remembered = files.firstOrNull { it.path == settings.selectedProjectFile }
                    if (remembered != null) {
                        onResolved(Paths.get(remembered.parent.path))
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            promptForChoice(project, files, onResolved)
                        }
                    }
                }
            }
        }
    }

    fun promptForChoice(project: Project, files: List<VirtualFile>, onResolved: (Path) -> Unit) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(files)
            .setTitle("Select BHL Project File")
            .setRenderer { _, value, _, _, _ ->
                javax.swing.JLabel(value.path)
            }
            .setItemChosenCallback { chosen ->
                BhlSettings.getInstance(project).selectedProjectFile = chosen.path
                onResolved(Paths.get(chosen.parent.path))
            }
            .createPopup()
            .showInFocusCenter()
    }
}

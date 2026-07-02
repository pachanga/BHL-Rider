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
import java.nio.file.Files
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
     * Resolves the working directory to launch the LSP server in, in priority order:
     *  1. the explicit "BHL project directory" setting, if configured and valid;
     *  2. the nearest `bhl.proj` found by walking up from [contextFile] (works even for
     *     files outside the project's indexed scope);
     *  3. a project-wide index search for `bhl.proj` (0 → don't start, 1 → use it,
     *     many → remembered choice or a disambiguation prompt).
     *
     * Invokes [onResolved] once determined; does nothing if none of the strategies find a
     * `bhl.proj` or the user dismisses the disambiguation prompt.
     */
    fun resolveWorkingDirectory(project: Project, contextFile: VirtualFile?, onResolved: (Path) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // 1. Explicit override.
            val configured = configuredProjectDirectory(project)
            if (configured != null) {
                onResolved(configured)
                return@executeOnPooledThread
            }

            // 2. Walk up from the opened file to the nearest bhl.proj.
            val walkedUp = contextFile?.let { findNearestProjectDir(it) }
            if (walkedUp != null) {
                onResolved(Paths.get(walkedUp.path))
                return@executeOnPooledThread
            }

            // 3. Project-wide index search.
            val files = findProjectFiles(project)
            when {
                files.isEmpty() ->
                    LOG.info("No $BHL_PROJECT_FILE_NAME found for project ${project.name}; BHL LSP server will not start.")

                files.size == 1 -> onResolved(Paths.get(files[0].parent.path))

                else -> {
                    val settings = BhlSettings.getInstance(project)
                    val remembered = files.firstOrNull { it.path == settings.selectedProjectFile }
                    if (remembered != null) {
                        onResolved(Paths.get(remembered.parent.path))
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            promptForChoice(project, files) { chosen ->
                                settings.selectedProjectFile = chosen.path
                                onResolved(Paths.get(chosen.parent.path))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The directory configured via the "BHL project directory" setting, or `null` when
     * unset or invalid. A valid value is an existing directory (a warning is logged if it
     * doesn't actually contain a `bhl.proj`, but it is still used as the override).
     */
    private fun configuredProjectDirectory(project: Project): Path? {
        val configured = BhlSettings.getInstance(project).projectDirectory
        if (configured.isBlank()) return null
        val dir = Paths.get(configured)
        if (!Files.isDirectory(dir)) {
            LOG.warn("Configured BHL project directory '$configured' is not an existing directory; falling back to auto-detection.")
            return null
        }
        if (!Files.isRegularFile(dir.resolve(BHL_PROJECT_FILE_NAME))) {
            LOG.warn("Configured BHL project directory '$configured' does not contain a $BHL_PROJECT_FILE_NAME; using it anyway.")
        }
        return dir
    }

    /** Walks up from [file] (or its parent) to the nearest directory containing a `bhl.proj`. */
    private fun findNearestProjectDir(file: VirtualFile): VirtualFile? {
        var dir: VirtualFile? = if (file.isDirectory) file else file.parent
        while (dir != null) {
            if (dir.findChild(BHL_PROJECT_FILE_NAME)?.isDirectory == false) {
                return dir
            }
            dir = dir.parent
        }
        return null
    }

    /** Shows a popup listing [files] and invokes [onChosen] with the picked `bhl.proj`. */
    fun promptForChoice(project: Project, files: List<VirtualFile>, onChosen: (VirtualFile) -> Unit) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(files)
            .setTitle("Select BHL Project File")
            .setRenderer { _, value, _, _, _ ->
                javax.swing.JLabel(value.path)
            }
            .setItemChosenCallback { chosen -> onChosen(chosen) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}

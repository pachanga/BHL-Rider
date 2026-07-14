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
     * Fast, **synchronous** resolution used from `fileOpened`: the explicit "BHL project
     * directory" override, then walk-up from [contextFile] to the nearest `bhl.proj`.
     *
     * Must be synchronous: the platform reads the LspServerStarter's descriptor right after
     * `fileOpened` returns, so `serverStarter.ensureServerStarted(...)` has to be called
     * within that call — not from a background thread.
     *
     * Returns `null` if neither strategy resolves a directory (caller may then fall back to
     * [resolveViaProjectIndex]).
     */
    fun resolveSync(project: Project, contextFile: VirtualFile?): Path? {
        val console = BhlLspConsoleService.getInstance(project)
        val cache = BhlResolvedProjectsCache.getInstance(project)
        configuredProjectDirectory(project)?.let {
            console.logInfo("bhl.proj: using configured project directory $it")
            cache.remember(it)
            return it
        }
        contextFile?.let { findNearestProjectDir(it) }?.let {
            console.logInfo("bhl.proj: found via walk-up in ${it.path}")
            val dir = Paths.get(it.path)
            cache.remember(dir)
            return dir
        }
        // The file may be outside its owning bhl.proj's own directory entirely (e.g. a
        // shared folder one of that project's src_dirs points at) — walk-up above can't find
        // such a bhl.proj since it isn't an ancestor. If we've already resolved that project
        // once this session (its src_dirs are cached), match against those instead.
        contextFile?.let { cache.findOwning(it) }?.let {
            console.logInfo("bhl.proj: matched cached src_dirs, using $it")
            return it
        }
        return null
    }

    /**
     * Fallback resolution via a project-wide index search for `bhl.proj` (0 → don't start,
     * 1 → use it, many → remembered choice or a picker). Runs asynchronously, so callers
     * must start the server via [LspServerManager] rather than the fileOpened starter.
     */
    fun resolveViaProjectIndex(project: Project, onResolved: (Path) -> Unit) {
        val console = BhlLspConsoleService.getInstance(project)
        val cache = BhlResolvedProjectsCache.getInstance(project)
        val remember: (Path) -> Unit = { cache.remember(it); onResolved(it) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val files = findProjectFiles(project)
            when {
                files.isEmpty() -> {
                    val msg = "no $BHL_PROJECT_FILE_NAME found — server not started. " +
                        "Open a .bhl file under a $BHL_PROJECT_FILE_NAME, or set 'BHL project directory' in Settings."
                    LOG.info("$msg (project ${project.name})")
                    console.logInfo("bhl.proj: $msg")
                }

                files.size == 1 -> {
                    console.logInfo("bhl.proj: found ${files[0].path}")
                    remember(Paths.get(files[0].parent.path))
                }

                else -> {
                    val settings = BhlSettings.getInstance(project)
                    val remembered = files.firstOrNull { it.path == settings.selectedProjectFile }
                    if (remembered != null) {
                        console.logInfo("bhl.proj: using remembered ${remembered.path}")
                        remember(Paths.get(remembered.parent.path))
                    } else {
                        console.logInfo("bhl.proj: ${files.size} candidates — prompting for a choice")
                        ApplicationManager.getApplication().invokeLater {
                            promptForChoice(project, files) { chosen ->
                                settings.selectedProjectFile = chosen.path
                                remember(Paths.get(chosen.parent.path))
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

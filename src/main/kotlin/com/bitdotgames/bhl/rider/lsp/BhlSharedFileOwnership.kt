package com.bitdotgames.bhl.rider.lsp

import com.bitdotgames.bhl.rider.BhlFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JLabel

/**
 * Which `bhl.proj` currently "owns" a file that sits in a directory shared between more than
 * one BHL project (e.g. a common library folder listed in several projects' `src_dirs`, each
 * with different compile-time constants) — [BhlLspServerDescriptor.isSupportedFile] only lets
 * the chosen owner's server claim the file, so its diagnostics/highlighting/hover reflect one
 * project's interpretation at a time instead of an arbitrary, unpredictable mix of both.
 */
@Service(Service.Level.PROJECT)
class BhlSharedFileOwnershipService {
    private val chosenOwner = ConcurrentHashMap<VirtualFile, Path>()
    private val suppressNextPrompt: MutableSet<VirtualFile> = Collections.newSetFromMap(ConcurrentHashMap())
    private val reconnectedOnce: MutableSet<VirtualFile> = Collections.newSetFromMap(ConcurrentHashMap())

    fun currentOwner(file: VirtualFile): Path? = chosenOwner[file]

    /** Records the user's choice; returns `true` if it actually changed the previous owner. */
    fun setOwner(file: VirtualFile, workDir: Path): Boolean {
        val previous = chosenOwner.put(file, workDir)
        return previous != workDir
    }

    /** Marks [file]'s next tab-selection event as caused by our own reconnect, not the user. */
    fun suppressNextPromptFor(file: VirtualFile) = suppressNextPrompt.add(file)

    /** Consumes the suppression flag; `true` means this selection event should be skipped. */
    fun consumeSuppression(file: VirtualFile): Boolean = suppressNextPrompt.remove(file)

    /** `true` the first time it's called for [file] this session; `false` afterwards. */
    fun markReconnectedOnce(file: VirtualFile): Boolean = reconnectedOnce.add(file)

    companion object {
        fun getInstance(project: Project): BhlSharedFileOwnershipService = project.service()
    }
}

/**
 * Prompts on every focus switch into a shared-directory file (not just its first open) since
 * the whole point of switching which project owns it is to compare Client vs Server behavior
 * for the same shared script within one session.
 */
fun installBhlSharedFileSelectionListener(project: Project, disposable: Disposable) {
    val reconnectAlarm = Alarm(disposable)
    project.messageBus.connect(disposable).subscribe(
        FileEditorManagerListener.FILE_EDITOR_MANAGER,
        object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                val file = event.newFile ?: return
                if (file.fileType != BhlFileType) return

                val ownership = BhlSharedFileOwnershipService.getInstance(project)
                if (ownership.consumeSuppression(file)) return

                val cache = BhlResolvedProjectsCache.getInstance(project)
                val candidates = cache.findAllOwning(file)

                if (candidates.size > 1) {
                    ApplicationManager.getApplication().invokeLater {
                        promptForOwningProject(project, file, candidates) { chosen ->
                            if (ownership.setOwner(file, chosen)) {
                                ownership.suppressNextPromptFor(file)
                                val fem = FileEditorManager.getInstance(project)
                                fem.closeFile(file)
                                fem.openFile(file, true)
                            }
                        }
                    }
                    return
                }

                // Works around a startup race: BhlContentRoots.ensureBhlContentRoots registers
                // src_dirs entries outside the bhl.proj's own directory (e.g. a shared library
                // folder) as content roots asynchronously (needs a write action), so a file
                // under one of them opened right away can be routed before that registration
                // has landed — ProjectFileIndex.isInContent still says no, so the platform's
                // LspOpenedFilesService never even reaches our isSupportedFile, and the file
                // never gets a didOpen. One retry, once the registration's had time to land,
                // works around it; markReconnectedOnce ensures this only ever fires once.
                if (cache.isSecondaryRoot(file) && ownership.markReconnectedOnce(file)) {
                    reconnectAlarm.addRequest(
                        {
                            ownership.suppressNextPromptFor(file)
                            val fem = FileEditorManager.getInstance(project)
                            fem.closeFile(file)
                            fem.openFile(file, true)
                        },
                        500,
                    )
                }
            }
        },
    )
}

private fun promptForOwningProject(project: Project, file: VirtualFile, candidates: List<Path>, onChosen: (Path) -> Unit) {
    JBPopupFactory.getInstance()
        .createPopupChooserBuilder(candidates)
        .setTitle("${file.name} is shared — view it as part of:")
        .setRenderer { _, value, _, _, _ -> JLabel(value.fileName?.toString() ?: value.toString()) }
        .setItemChosenCallback(onChosen)
        .createPopup()
        .showCenteredInCurrentWindow(project)
}

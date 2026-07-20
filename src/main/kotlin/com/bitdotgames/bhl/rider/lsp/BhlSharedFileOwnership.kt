package com.bitdotgames.bhl.rider.lsp

import com.bitdotgames.bhl.rider.BhlFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Which `bhl.proj` currently "owns" a file that sits in a directory shared between more than
 * one BHL project (e.g. a common library folder listed in several projects' `src_dirs`, each
 * with different compile-time constants) — [BhlLspServerDescriptor.isSupportedFile] only lets
 * the chosen owner's server claim the file, so its diagnostics/highlighting/hover reflect one
 * project's interpretation at a time instead of an arbitrary, unpredictable mix of both.
 *
 * No longer prompts on open — see [BhlSharedFileWidget] for the on-demand switcher.
 */
@Service(Service.Level.PROJECT)
class BhlSharedFileOwnershipService {
    private val chosenOwner = ConcurrentHashMap<VirtualFile, Path>()
    private val suppressNextPrompt: MutableSet<VirtualFile> = Collections.newSetFromMap(ConcurrentHashMap())
    private val reconnectedOnce: MutableSet<VirtualFile> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var lastActiveProject: Path? = null

    fun currentOwner(file: VirtualFile): Path? = chosenOwner[file]

    /** Records the user's choice; returns `true` if it actually changed the previous owner. */
    fun setOwner(file: VirtualFile, workDir: Path): Boolean {
        val previous = chosenOwner.put(file, workDir)
        return previous != workDir
    }

    /** Tracks which project you're likely working in, from focusing an unambiguous BHL file. */
    fun recordActiveProject(workDir: Path) {
        lastActiveProject = workDir
    }

    /**
     * The owner to use for [file] without asking: its previously chosen owner if that's still
     * one of [candidates]; otherwise the project you were last working in, if it's a candidate;
     * otherwise just the first candidate. Persists whatever it picks, so this is stable across
     * refocusing the same file (until explicitly changed via [BhlSharedFileWidget]) and so
     * [currentOwner] (used by `isSupportedFile`) agrees with what got displayed/used.
     */
    fun resolveOwner(file: VirtualFile, candidates: List<Path>): Path {
        chosenOwner[file]?.let { if (it in candidates) return it }
        val resolved = lastActiveProject?.takeIf { it in candidates } ?: candidates.first()
        chosenOwner[file] = resolved
        return resolved
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
 * Silently resolves shared-file ownership on open/focus (see [BhlSharedFileOwnershipService
 * .resolveOwner]) instead of prompting — switching which project owns a shared file is now done
 * on demand via [BhlSharedFileWidget] in the status bar, so you're never blocked from just
 * looking at a file while it's already open.
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
                when {
                    candidates.size == 1 -> ownership.recordActiveProject(candidates[0])
                    candidates.size > 1 -> ownership.resolveOwner(file, candidates)
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
                    reconnectAlarm.addRequest({ reconnectBhlFile(project, file, ownership) }, 500)
                }
            }
        },
    )
}

/** Force-closes then reopens [file] — works around the startup race above (content roots not
 * registered yet when the file was first opened, so it never got routed to any server at all).
 * NOT sufficient for switching an *already-connected* shared file's owner — see
 * [BhlSharedFileWidget]'s owner-switch handler for why that needs a full server restart instead. */
fun reconnectBhlFile(project: Project, file: VirtualFile, ownership: BhlSharedFileOwnershipService) {
    ownership.suppressNextPromptFor(file)
    val fem = FileEditorManager.getInstance(project)
    fem.closeFile(file)
    fem.openFile(file, true)
}

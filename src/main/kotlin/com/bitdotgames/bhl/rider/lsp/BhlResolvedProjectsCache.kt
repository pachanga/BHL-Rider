package com.bitdotgames.bhl.rider.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Remembers `bhl.proj` directories this project has already resolved (and their `src_dirs`),
 * so a `bhl.proj` outside a file's own ancestor chain — e.g. a shared library folder a
 * `src_dirs` entry points at, well outside the referencing project's own directory — can still
 * be resolved *synchronously* the first time one of its files is opened directly.
 *
 * Without this, [BhlProjectFileResolver.resolveSync] only finds `bhl.proj` by walking up from
 * the opened file itself, which fails for such a file (its nearest `bhl.proj` ancestor doesn't
 * exist), forcing the async project-index fallback — too late to link *that* `fileOpened` call
 * to the server (see the comment in `BhlLspServerSupportProvider.fileOpened`), so the file never
 * gets a server connection at all. Once any `bhl.proj` has been resolved once this session
 * (typically from opening the referencing project's own files first), this cache lets a
 * directly-opened shared file be matched to it immediately.
 */
@Service(Service.Level.PROJECT)
class BhlResolvedProjectsCache(private val project: Project) {
    @Volatile
    private var resolutions: List<Pair<Path, List<Path>>> = emptyList()

    @Synchronized
    fun remember(workDir: Path) {
        if (resolutions.any { it.first == workDir }) return
        val srcDirs = resolveBhlSrcDirs(workDir)
        resolutions = resolutions + (workDir to srcDirs)
        BhlLspConsoleService.getInstance(project)
            .logInfo("resolvedProjectsCache: remembered $workDir -> $srcDirs (total known: ${resolutions.size})", reveal = false)
    }

    /** The cached `bhl.proj` directory whose `src_dirs` (or own directory) contain [file], if any. */
    fun findOwning(file: VirtualFile): Path? = findAllOwning(file).firstOrNull()

    /**
     * Every cached `bhl.proj` directory whose `src_dirs` contain [file]. More than one entry
     * means [file] sits in a directory shared between multiple BHL projects (e.g. a common
     * library folder referenced by more than one project's `src_dirs`) — see
     * [BhlSharedFileOwnershipService] for how that ambiguity gets resolved.
     */
    fun findAllOwning(file: VirtualFile): List<Path> = resolutions.filter { (_, srcDirs) ->
        srcDirs.any { dir ->
            val vDir = LocalFileSystem.getInstance().findFileByNioFile(dir)
            vDir != null && VfsUtilCore.isAncestor(vDir, file, false)
        }
    }.map { it.first }

    /**
     * True if [file] is only reachable through a `src_dirs` entry *other than* its owning
     * project's own directory (e.g. a shared library folder) — as opposed to being under the
     * `bhl.proj` directory itself, which was already part of the IDE's project content before
     * this plugin ever ran. See [BhlSharedFileOwnership.kt] for why this distinction matters.
     */
    fun isSecondaryRoot(file: VirtualFile): Boolean = resolutions.any { (workDir, srcDirs) ->
        val underOwnWorkDir = LocalFileSystem.getInstance().findFileByNioFile(workDir)
            ?.let { VfsUtilCore.isAncestor(it, file, false) } == true
        !underOwnWorkDir && srcDirs.any { dir ->
            val vDir = LocalFileSystem.getInstance().findFileByNioFile(dir)
            vDir != null && VfsUtilCore.isAncestor(vDir, file, false)
        }
    }

    companion object {
        fun getInstance(project: Project): BhlResolvedProjectsCache = project.service()
    }
}

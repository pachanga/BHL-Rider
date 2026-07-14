package com.bitdotgames.bhl.rider.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.storage.EntitySource

private val LOG = Logger.getInstance("com.bitdotgames.bhl.rider.lsp.BhlContentRoots")

private const val BHL_MODULE_NAME = "bhl.shared-roots"

private object BhlContentRootsEntitySource : EntitySource

/**
 * Registers [dirs] as content roots of a dedicated, plugin-owned module directly in the
 * workspace model, so `ProjectFileIndex.isInContent(file)` returns true for files under them.
 *
 * This matters because `com.intellij.platform.lsp.impl.LspServerImpl
 * .isSupportedFile$intellij_platform_lsp_impl` — the platform's own wrapper around our
 * `LspServerDescriptor.isSupportedFile()` — checks `ProjectFileIndex.isInContent(file)` FIRST
 * and returns false immediately if it's false, *before* our own descriptor's `isSupportedFile`
 * (or its `roots`) are ever consulted (confirmed by decompiling that method, and independently
 * confirmed at runtime: `isInContent` measured `false` for a `src_dirs` folder even though our
 * own `isSupportedFile` — reached separately via the LSP status widget, which bypasses this
 * check entirely — kept reporting `true`, which is what made the underlying problem look fixed
 * when it wasn't). A `bhl.proj` `src_dirs` entry pointing at a directory outside the actual
 * .NET solution/module structure — e.g. a shared library folder no `.csproj` references —
 * therefore never gets a server connection at all (no semantic highlighting, no signature
 * help, etc.), regardless of how widely our own descriptor declares its roots.
 *
 * An earlier version of this used the classic `ModuleManager`/`ModifiableRootModel`
 * content-entry API, which appeared to work (no exception) but never actually stuck: measured
 * directly at runtime, `isInContent` stayed `false` afterwards. That API is a compatibility
 * bridge over the same `ModuleEntity`/`ContentRootEntity` workspace-model entities Rider's own
 * .NET solution sync owns, and that sync's `replaceBySource` most likely wipes entities it
 * didn't create shortly after. Adding the *same* entity types directly to the workspace model,
 * tagged with our own private [EntitySource] (rather than whatever generic bridge source the
 * classic API uses), is what actually isolates them from that resync — the same mechanism
 * IntelliJ itself relies on for e.g. scratch files.
 *
 * The entity construction itself lives in [BhlWorkspaceEntityHelper], written in Java:
 * `ModuleEntity`/`ContentRootEntity`'s `create(...)` factories are Kotlin-internal to the
 * platform's own module — they resolve fine via `javap` on the raw bytecode, but the Kotlin
 * compiler rejects calling them from plugin code (confirmed by trying it). Kotlin's `internal`
 * visibility is enforced by the compiler reading Kotlin metadata, not by the JVM, so plain Java
 * has no such restriction.
 */
fun ensureBhlContentRoots(project: Project, dirs: List<VirtualFile>) {
    if (dirs.isEmpty()) return
    val fileIndex = ProjectFileIndex.getInstance(project)
    if (dirs.all { fileIndex.isInContent(it) }) return
    val console = BhlLspConsoleService.getInstance(project)

    ApplicationManager.getApplication().invokeLater {
        runCatching {
            WriteAction.run<Throwable> {
                val workspaceModel = WorkspaceModel.getInstance(project)
                val urlManager = workspaceModel.getVirtualFileUrlManager()
                workspaceModel.updateProjectModel("BHL: register shared src_dirs as content roots") { storage ->
                    storage.resolve(ModuleId(BHL_MODULE_NAME))?.let { storage.removeEntity(it) }
                    val urls = dirs.map { urlManager.getOrCreateFromUrl(it.url) }
                    BhlWorkspaceEntityHelper.registerModuleWithContentRoots(
                        storage,
                        BHL_MODULE_NAME,
                        BhlContentRootsEntitySource,
                        urls,
                    )
                }
            }
            console.logInfo("registered content roots for: ${dirs.map { it.path }}", reveal = false)
        }.onFailure {
            LOG.warn("failed to register BHL content roots for ${dirs.map { it.path }}", it)
            console.logInfo("failed to register BHL content roots for ${dirs.map { it.path }}: $it", reveal = false)
        }
    }
}

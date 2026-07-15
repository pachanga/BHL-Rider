@file:Suppress("UnstableApiUsage")

package com.bitdotgames.bhl.rider.lsp

import com.bitdotgames.bhl.rider.BhlFileType
import com.bitdotgames.bhl.rider.BhlIcons
import com.bitdotgames.bhl.rider.BhlTextAttributes
import com.bitdotgames.bhl.rider.settings.BhlSettings
import com.bitdotgames.bhl.rider.settings.BhlSettingsConfigurable
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
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
        val isBhl = file.fileType == BhlFileType || file.extension.equals("bhl", ignoreCase = true)
        if (!isBhl) return
        val console = BhlLspConsoleService.getInstance(project)
        console.logInfo("opened ${file.name} (fileType=${file.fileType.name}); resolving $BHL_PROJECT_FILE_NAME…")

        // Fast path: resolve synchronously and start via the fileOpened starter, so the
        // platform can link this file to the server (it reads the starter's descriptor
        // right after fileOpened returns — an async callback would be too late).
        val workDir = BhlProjectFileResolver.resolveSync(project, file)
        if (workDir != null) {
            console.logInfo("ensuring BHL server is started (workDir=$workDir)")
            serverStarter.ensureServerStarted(newBhlLspServerDescriptor(project, workDir))
            return
        }

        // Fallback: project-wide index search runs async, so start via the manager.
        console.logInfo("bhl.proj: not resolved synchronously for ${file.path} — falling back to async index search")
        BhlProjectFileResolver.resolveViaProjectIndex(project) { resolved ->
            console.logInfo("ensuring BHL server is started (workDir=$resolved)")
            LspServerManager.getInstance(project)
                .ensureServerStarted(BhlLspServerSupportProvider::class.java, newBhlLspServerDescriptor(project, resolved))
        }
    }

    override fun createLspServerWidgetItem(lspServer: LspServer, currentFile: VirtualFile?): LspServerWidgetItem =
        LspServerWidgetItem(lspServer, currentFile, BhlIcons.FILE, BhlSettingsConfigurable::class.java)
}

open class BhlLspServerDescriptor private constructor(
    project: Project,
    protected val workDir: Path,
    protected val srcDirs: List<Path>,
) : LspServerDescriptor(project, "BHL Language Server", *resolveRoots(srcDirs)) {

    constructor(project: Project, workDir: Path) : this(project, workDir, resolveBhlSrcDirs(workDir, project))

    init {
        ensureBhlContentRoots(project, srcDirs.mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it) })
    }

    /** VirtualFiles for [srcDirs] that currently exist; recomputed lazily since [workDir]'s
     * `bhl.proj` (and therefore its resolved `src_dirs`) can appear after this descriptor is
     * constructed. */
    private val srcDirFiles: List<VirtualFile>
        get() = srcDirs.mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it) }

    /**
     * Scoped to files under [srcDirs] — [workDir] plus any `src_dirs` the project's `bhl.proj`
     * declares outside it (e.g. a shared library folder referenced via `"../shared"`) — not
     * just any `.bhl` file: with several BHL directories attached to one project (each
     * resolving to its own descriptor/server, see [BhlLspServerDescriptor.equals]), the
     * platform decides which of the *already running* servers a given open file is routed to
     * by calling `isSupportedFile` on each one — so without this check, a file in directory A
     * would also be sent to directory B's server.
     */
    override fun isSupportedFile(file: VirtualFile): Boolean {
        val isBhl = file.fileType == BhlFileType || file.extension.equals("bhl", ignoreCase = true)
        if (!isBhl) return false
        val roots = srcDirFiles
        if (roots.isNotEmpty() && roots.none { VfsUtilCore.isAncestor(it, file, false) }) return false

        // A directory can be a src_dirs entry of more than one bhl.proj (e.g. a shared library
        // folder referenced by both a Client and a Server project with different compile-time
        // constants) — only the project the user picked for this file (see
        // BhlSharedFileOwnership.kt) gets to claim it, so its diagnostics/highlighting reflect
        // one project's interpretation instead of an unpredictable mix of both.
        val owners = BhlResolvedProjectsCache.getInstance(project).findAllOwning(file)
        return if (owners.size > 1) {
            BhlSharedFileOwnershipService.getInstance(project).currentOwner(file) == workDir
        } else {
            true
        }
    }

    // Two descriptors for the same working directory are the same server, so starting from
    // both fileOpened and the action reuses one server instead of spawning duplicates. This
    // equals()/hashCode() override is NOT what the platform's own dedup uses, though — see
    // resolveRoots below for the part that actually matters for that.
    override fun equals(other: Any?): Boolean =
        other is BhlLspServerDescriptor && other.project == project && other.workDir == workDir

    override fun hashCode(): Int = workDir.hashCode()

    companion object {
        /**
         * Roots scoped to [srcDirs], not the whole project. `LspServerManagerImpl.ensureServerStarted`
         * decides whether a server "already exists" for a new descriptor by comparing
         * `(providerClass, getRoots())` — NOT our `equals()`/`hashCode()` override above. The
         * previous base class, `ProjectWideLspServerDescriptor`, sets roots to the *project's*
         * base directories, identical for every instance regardless of `workDir` — so with two
         * BHL directories attached to one project, the second one's `ensureServerStarted` call
         * would see matching roots against the first server and silently skip starting its own,
         * never noticing they're different directories. Scoping roots to `srcDirs` fixes that
         * dedup check directly, and also feeds the platform's file-routing pre-filter (which
         * separately checks `VfsUtilCore.isAncestor` against `getRoots()`, in addition to
         * `isSupportedFile` above) — including for files in a shared `src_dirs` folder outside
         * `workDir`, which otherwise never gets routed to this server at all.
         */
        private fun resolveRoots(srcDirs: List<Path>): Array<VirtualFile> =
            srcDirs.mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it) }.toTypedArray()
    }

    /**
     * Point the server at the selected bhl.proj directory (and any other `src_dirs` it
     * declares). The BHL server reads its project from `workspaceFolders` (falling back to
     * rootUri); the IDE otherwise fills these with the solution's own roots, which are not the
     * BHL project directory.
     */
    @Suppress("DEPRECATION") // rootUri is deprecated in LSP; set as a fallback for older servers
    override fun createInitializeParams(): InitializeParams {
        val params = super.createInitializeParams()
        val folders = srcDirFiles.ifEmpty { listOfNotNull(LocalFileSystem.getInstance().findFileByNioFile(workDir)) }
            .map { WorkspaceFolder(getFileUri(it), it.name) }
            .ifEmpty { listOf(WorkspaceFolder(workDir.toUri().toString(), workDir.fileName?.toString() ?: "bhl")) }
        params.setRootUri(folders.first().uri)
        params.workspaceFolders = folders.toMutableList()
        BhlLspConsoleService.getInstance(project).logInfo("workspace folders: ${folders.joinToString { it.uri }}")
        return params
    }

    override fun createCommandLine(): GeneralCommandLine {
        val settings = BhlSettings.getInstance(project)
        // executablePath/forceRebuild are ignored — not just hidden — when "use custom BHL
        // installation path" is off, so toggling it off reliably falls back to the downloaded
        // release (or bare "bhl" on PATH) even if a custom path is still saved from a previous
        // session.
        val executablePath = when {
            settings.useCustomInstallation -> settings.executablePath.ifBlank { "bhl" }
            settings.downloadedBinaryPath.isNotBlank() -> settings.downloadedBinaryPath
            else -> "bhl"
        }

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

        if (settings.useCustomInstallation && settings.forceRebuild) {
            commandLine.withEnvironment(mapOf("BHL_REBUILD" to "1", "BHL_SILENT" to "1"))
        }

        BhlLspConsoleService.getInstance(project)
            .logInfo("launching: ${commandLine.commandLineString} (cwd=$workDir)")

        return commandLine
    }

    /** Capture the server process's stderr and exit code into the "BHL LSP" console. */
    override fun startServerProcess(): OSProcessHandler {
        val console = BhlLspConsoleService.getInstance(project)
        val handler = try {
            super.startServerProcess()
        } catch (e: ExecutionException) {
            // Most commonly the executable isn't found (e.g. bare "bhl" not on the IDE's PATH).
            console.logInfo(
                "failed to launch server: ${e.message}. " +
                    "Set an absolute 'Executable path' in Settings > Languages & Frameworks > BHL.",
            )
            throw e
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // stdout is the JSON-RPC channel and must not be touched; only log stderr.
                if (outputType == ProcessOutputTypes.STDERR) {
                    event.text?.let { console.printServerStderr(it) }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                console.logInfo("server process exited with code ${event.exitCode}")
            }
        })
        return handler
    }

    /** Route the BHL server's notifications into the "BHL LSP" console. */
    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        super.createLsp4jClient(
            BhlLoggingNotificationsHandler(handler, BhlLspConsoleService.getInstance(project)),
        )

    /**
     * The platform's default mapping sends type/class/variable/parameter to attribute keys
     * that stock IntelliJ schemes leave uncolored (unlike VSCode themes). Route them (and
     * function, plain on light schemes) to BHL keys with colors shipped per scheme; the
     * remaining types (keyword/string/number/property/operator) have good defaults.
     */
    override val lspSemanticTokensSupport: LspSemanticTokensSupport = object : LspSemanticTokensSupport() {
        override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? =
            when (tokenType) {
                "type", "class" -> BhlTextAttributes.TYPE
                "function" -> BhlTextAttributes.FUNCTION
                "variable" -> BhlTextAttributes.VARIABLE
                "parameter" -> BhlTextAttributes.PARAMETER
                else -> super.getTextAttributesKey(tokenType, modifiers)
            }
    }

    /**
     * These callbacks fire for as long as this server instance is alive, which can outlast a
     * dynamic plugin reload (installing an updated build without restarting the IDE): the
     * fresh `getInstance` lookup below may then return a project-service instance from a
     * *different* PluginClassLoader than the one this listener was itself loaded by, throwing
     * `ClassCastException: class X cannot be cast to class X` for what looks like the same
     * class. `runCatching` keeps that (console-logging-only, non-critical) failure from
     * surfacing as an unhandled exception.
     */
    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(result: InitializeResult) {
            runCatching {
                val info = result.serverInfo
                val suffix = info?.let { ": ${it.name} ${it.version ?: ""}".trimEnd() } ?: ""
                BhlLspConsoleService.getInstance(project).logInfo("server initialized$suffix")
            }
        }

        override fun serverStopped(crashed: Boolean) {
            runCatching {
                BhlLspConsoleService.getInstance(project)
                    .logInfo(if (crashed) "server crashed" else "server stopped")
            }
        }
    }
}

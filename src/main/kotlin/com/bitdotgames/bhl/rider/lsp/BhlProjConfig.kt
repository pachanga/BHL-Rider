package com.bitdotgames.bhl.rider.lsp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val LOG = Logger.getInstance("com.bitdotgames.bhl.rider.lsp.BhlProjConfig")

/**
 * Resolves the source directories a `bhl.proj` declares via its `src_dirs` entries, mirroring
 * the BHL server's own resolution (`ProjectConfShort.NormalizePath` in src/front/proj_conf.cs):
 * absolute paths pass through, and relative paths are resolved against the proj file's own
 * directory. Entries commonly point at a "shared" directory outside [workDir] entirely (e.g.
 * `"../shared"`) — the server indexes and serves symbols from wherever `src_dirs` points
 * regardless of the client's declared workspace root, so this plugin has to widen its own
 * server roots / `isSupportedFile` scope to match, or files in those directories never get
 * routed to the running server (no semantic highlighting, no signature help, etc.).
 *
 * Always includes [workDir] itself, even if `bhl.proj` can't be read or has no `src_dirs`.
 */
fun resolveBhlSrcDirs(workDir: Path, project: Project? = null): List<Path> {
    val projFile = workDir.resolve(BHL_PROJECT_FILE_NAME)
    val entries = runCatching {
        Files.newBufferedReader(projFile).use { Gson().fromJson(it, JsonObject::class.java) }
            ?.getAsJsonArray("src_dirs")
            ?.mapNotNull { it.asString }
    }.onFailure {
        LOG.info("could not read src_dirs from $projFile: $it")
        project?.let { p -> BhlLspConsoleService.getInstance(p).logInfo("src_dirs: could not read $projFile: $it") }
    }.getOrNull().orEmpty()

    val resolved = entries.map { entry ->
        val path = Paths.get(entry)
        if (path.isAbsolute) path.normalize() else workDir.resolve(entry).normalize()
    }

    val result = (listOf(workDir) + resolved).distinct()
    project?.let { p ->
        BhlLspConsoleService.getInstance(p)
            .logInfo("src_dirs: $projFile raw=$entries resolved=$result", reveal = false)
    }
    return result
}

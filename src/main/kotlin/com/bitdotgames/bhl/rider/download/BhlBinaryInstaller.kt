package com.bitdotgames.bhl.rider.download

import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

private const val RELEASES_URL = "https://api.github.com/repos/bitdotgames/BHL/releases?per_page=30"

/**
 * Downloads and installs a self-contained `bhl` LSP server binary from
 * [bitdotgames/BHL releases](https://github.com/bitdotgames/BHL/releases) (tagged `lsp-vX.Y.Z`)
 * — no local `dotnet` install required, unlike running the language's own build from source.
 *
 * Installed binaries are cached under the IDE's system directory, keyed by release tag and
 * platform, so re-selecting an already-downloaded version in Settings is instant and shared
 * across projects (this plugin's settings are per-project, but there's no reason to re-download
 * the same binary for each one).
 */
object BhlBinaryInstaller {
    /** All non-draft `lsp-v*` releases, newest first (GitHub's own listing order). */
    fun fetchReleases(indicator: ProgressIndicator): List<BhlRelease> {
        indicator.text = "Fetching BHL releases…"
        val json = HttpRequests.request(RELEASES_URL)
            .accept("application/vnd.github+json")
            .userAgent("BHL-Rider-Plugin")
            .readString(indicator)
        val releases = Gson().fromJson(json, Array<BhlRelease>::class.java).toList()
        return releases.filter { !it.draft && it.tagName.startsWith("lsp-v") }
    }

    /** e.g. `"osx-arm64"`, `"linux-x64"`, `"win-x64"` — `null` if this platform has no published asset. */
    fun currentPlatformSuffix(): String? {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val os = when {
            osName.contains("mac") || osName.contains("darwin") -> "osx"
            osName.contains("win") -> "win"
            osName.contains("linux") -> "linux"
            else -> return null
        }
        val arch = when {
            osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
            osArch.contains("x86_64") || osArch.contains("amd64") -> "x64"
            else -> return null
        }
        // No win-arm64 asset is published as of lsp-v0.3.1.
        if (os == "win" && arch == "arm64") return null
        return "$os-$arch"
    }

    fun findAsset(release: BhlRelease, platformSuffix: String): BhlReleaseAsset? =
        release.assets.firstOrNull {
            it.name.endsWith("-$platformSuffix.tar.gz") || it.name.endsWith("-$platformSuffix.zip")
        }

    private fun findChecksumAsset(release: BhlRelease, binaryAsset: BhlReleaseAsset): BhlReleaseAsset? {
        val prefix = binaryAsset.name.removeSuffix(".tar.gz").removeSuffix(".zip")
        return release.assets.firstOrNull { it.name == "$prefix.sha256" }
    }

    /**
     * Downloads, verifies (against the release's `.sha256` sibling asset, when present) and
     * extracts [release]'s binary for the current platform, returning the path to the
     * executable. A no-op (besides an existence check) if it's already installed.
     */
    fun install(release: BhlRelease, indicator: ProgressIndicator): Path {
        val platformSuffix = currentPlatformSuffix()
            ?: error("No BHL binary is published for this platform (${System.getProperty("os.name")}/${System.getProperty("os.arch")})")
        val asset = findAsset(release, platformSuffix)
            ?: error("Release ${release.tagName} has no binary for $platformSuffix")

        // Only one release is ever "the" installed one at a time (see BhlSettings
        // .downloadedReleaseTag) — remove every other cached release now that we know this one
        // is actually installable, so switching versions doesn't quietly accumulate every
        // version ever tried.
        cleanupOtherInstalls(keepTagName = release.tagName)

        val installDir = installDirFor(release.tagName, platformSuffix)
        val binaryPath = installDir.resolve(if (platformSuffix.startsWith("win")) "bhl.exe" else "bhl")
        if (Files.isRegularFile(binaryPath)) return binaryPath

        Files.createDirectories(installDir)
        val archivePath = installDir.resolve(asset.name)
        try {
            indicator.text = "Downloading ${asset.name}…"
            HttpRequests.request(asset.downloadUrl).saveToFile(archivePath, indicator)

            // Mandatory, not best-effort: this binary runs unsandboxed as a subprocess, so a
            // corrupted or tampered download must fail loudly rather than get installed anyway.
            val checksumAsset = findChecksumAsset(release, asset)
                ?: error("${release.tagName} has no .sha256 checksum for ${asset.name} — refusing to install unverified")
            indicator.text = "Verifying checksum…"
            val expected = HttpRequests.request(checksumAsset.downloadUrl).readString(indicator)
                .trim().substringBefore(' ').lowercase()
            val actual = sha256Hex(archivePath)
            if (!expected.equals(actual, ignoreCase = true)) {
                error("Checksum mismatch for ${asset.name}: expected $expected, got $actual")
            }

            indicator.text = "Extracting ${asset.name}…"
            extractSingleBinary(archivePath, asset.name, binaryPath)
        } finally {
            Files.deleteIfExists(archivePath)
        }

        if (!Files.isRegularFile(binaryPath)) {
            error("${asset.name} did not contain the expected $binaryPath")
        }
        binaryPath.toFile().setExecutable(true)
        if (platformSuffix.startsWith("osx")) {
            removeQuarantineAttribute(binaryPath)
        }
        return binaryPath
    }

    /**
     * Gatekeeper can refuse to run a freshly-downloaded, unsigned/ad-hoc-signed binary
     * ("cannot be opened because Apple could not verify...") if macOS tags it with the
     * `com.apple.quarantine` extended attribute. Plain HTTP downloads via [HttpRequests]
     * don't reliably trigger that tagging the way Safari/Finder downloads do, but strip it
     * defensively in case some code path in between does — `xattr -d` exits non-zero when the
     * attribute is simply absent, which is the common case and not a failure.
     */
    private fun removeQuarantineAttribute(path: Path) {
        runCatching {
            ProcessBuilder("xattr", "-d", "com.apple.quarantine", path.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    private fun installsRoot(): Path = Path.of(PathManager.getSystemPath(), "bhl-rider")

    private fun installDirFor(tagName: String, platformSuffix: String): Path =
        installsRoot().resolve(tagName).resolve(platformSuffix)

    /** Deletes every cached release directory except [keepTagName]'s. Best-effort: a locked
     * file (e.g. the old binary still running as a subprocess) shouldn't fail the new install. */
    private fun cleanupOtherInstalls(keepTagName: String) {
        val root = installsRoot()
        if (!Files.isDirectory(root)) return
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                if (entry.fileName.toString() != keepTagName) {
                    runCatching { entry.toFile().deleteRecursively() }
                }
            }
        }
    }

    /** Every published archive contains exactly one file: the `bhl`/`bhl.exe` executable. */
    private fun extractSingleBinary(archivePath: Path, assetName: String, destination: Path) {
        if (assetName.endsWith(".zip")) {
            ZipInputStream(Files.newInputStream(archivePath)).use { zip ->
                val entry = generateSequence { zip.nextEntry }.firstOrNull { !it.isDirectory }
                    ?: error("$assetName is empty")
                Files.newOutputStream(destination).use { zip.copyTo(it) }
            }
        } else {
            GZIPInputStream(Files.newInputStream(archivePath)).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    val entry = generateSequence { tar.nextEntry }.firstOrNull { !it.isDirectory }
                        ?: error("$assetName is empty")
                    Files.newOutputStream(destination).use { tar.copyTo(it) }
                }
            }
        }
    }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

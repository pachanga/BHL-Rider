package com.bitdotgames.bhl.rider.download

import com.google.gson.annotations.SerializedName

/**
 * Mirrors the subset of GitHub's release API response ([bitdotgames/BHL releases]
 * (https://github.com/bitdotgames/BHL/releases)) this plugin needs. LSP server builds are
 * tagged `lsp-vX.Y.Z`, distinct from the language/compiler's own `vX.Y.Z` tags.
 */
data class BhlRelease(
    @SerializedName("tag_name") val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<BhlReleaseAsset>,
) {
    /** `lsp-v0.3.1` -> `v0.3.1`, matching the version embedded in asset file names. */
    val version: String get() = tagName.removePrefix("lsp-")
}

data class BhlReleaseAsset(
    val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
)

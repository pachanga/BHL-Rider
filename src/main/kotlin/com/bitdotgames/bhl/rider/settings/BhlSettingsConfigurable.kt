package com.bitdotgames.bhl.rider.settings

import com.bitdotgames.bhl.rider.download.BhlBinaryInstaller
import com.bitdotgames.bhl.rider.download.BhlRelease
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel

class BhlSettingsConfigurable(private val project: Project) : BoundConfigurable("BHL") {
    private val settings = BhlSettings.getInstance(project)
    private val releaseCombo = ComboBox<BhlRelease>().apply {
        renderer = SimpleListCellRenderer.create("") { release ->
            release.version + if (release.prerelease) " (prerelease)" else ""
        }
    }
    private var compatibleReleases: List<BhlRelease> = emptyList()
    private var releasesLoaded = false

    private lateinit var useCustomCheckbox: JBCheckBox
    private lateinit var executablePathRow: Row
    private lateinit var forceRebuildRow: Row
    private lateinit var downloadButton: JButton
    private lateinit var releaseStatusLabel: JLabel

    override fun createPanel() = panel {
        row("Download LSP release:") {
            cell(releaseCombo)
            downloadButton = button("Download") {
                val release = releaseCombo.selectedItem as? BhlRelease ?: return@button
                installRelease(project, release) { path ->
                    settings.downloadedReleaseTag = release.tagName
                    settings.downloadedBinaryPath = path
                    project.messageBus.syncPublisher(BhlSettings.SETTINGS_CHANGED_TOPIC).settingsChanged()
                    updateDownloadStatusLabel()
                }
            }.component
            releaseStatusLabel = label("").component
        }
        row {
            useCustomCheckbox = checkBox("Use custom BHL installation path")
                .bindSelected(settings::useCustomInstallation)
                .comment("Explicitly point at your own bhl executable/script instead of a downloaded release.")
                .component
        }
        executablePathRow = row("Executable path:") {
            val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select BHL Executable")
            val field = TextFieldWithBrowseButton().apply {
                addBrowseFolderListener(project, descriptor)
            }
            cell(field)
                .bindText(settings::executablePath)
                .comment("Path to the BHL executable/script.")
                .align(AlignX.FILL)
        }
        forceRebuildRow = row {
            checkBox("Force rebuild on startup")
                .bindSelected(settings::forceRebuild)
                .comment("Sets BHL_REBUILD=1 and BHL_SILENT=1 when launching the language server.")
        }
        row("Log file:") {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Select BHL Log File")
            val field = TextFieldWithBrowseButton().apply {
                addBrowseFolderListener(project, descriptor)
            }
            cell(field)
                .bindText(settings::logFile)
                .comment("Optional path for the LSP server log (passed as --log-file=PATH).")
                .align(AlignX.FILL)
        }
        row {
            checkBox("Trace LSP traffic")
                .bindSelected(settings::traceLsp)
                .comment(
                    "Mirrors raw JSON-RPC requests/responses into the BHL LSP console " +
                        "(truncated). Takes effect immediately, no server restart needed.",
                )
        }
    }.also {
        useCustomCheckbox.addItemListener { updateCustomPathVisibility() }
        updateCustomPathVisibility()
        updateDownloadStatusLabel()
        loadReleasesAsync()
    }

    /**
     * The executable path/force-rebuild controls are for an *explicit* custom override — they
     * only apply when [useCustomCheckbox] is on (see
     * [com.bitdotgames.bhl.rider.lsp.BhlLspServerDescriptor.createCommandLine]) — so hide them
     * entirely rather than just disabling them, to keep the common "just download a release"
     * path free of detail that isn't relevant to it.
     */
    private fun updateCustomPathVisibility() {
        val useCustom = useCustomCheckbox.isSelected
        executablePathRow.visible(useCustom)
        forceRebuildRow.visible(useCustom)
    }

    private fun updateDownloadStatusLabel() {
        val installedInfo = if (settings.downloadedBinaryPath.isBlank()) {
            "Not downloaded yet — the server runs as \"bhl\" on PATH until you download a release."
        } else {
            "Currently installed: ${settings.downloadedReleaseTag.removePrefix("lsp-")}"
        }
        releaseStatusLabel.text = if (releasesLoaded && compatibleReleases.isEmpty()) {
            "No compatible release published for this platform. $installedInfo"
        } else {
            installedInfo
        }
        downloadButton.isEnabled = compatibleReleases.isNotEmpty()
        releaseCombo.isEnabled = compatibleReleases.isNotEmpty()
    }

    /**
     * Populates [releaseCombo] with `lsp-v*` releases that publish a binary for the current
     * platform, preselecting the one already installed ([BhlSettings.downloadedReleaseTag]) if
     * it's among them. Runs off-EDT since it's a network call, but isn't wrapped in a modal
     * progress task like [installRelease] is — this fires unprompted the moment the Settings
     * page opens, and a brief background GET request doesn't warrant interrupting the user
     * with a dialog.
     */
    private fun loadReleasesAsync() {
        val platformSuffix = BhlBinaryInstaller.currentPlatformSuffix()
        if (platformSuffix == null) {
            releaseStatusLabel.text =
                "No BHL binary is published for this platform (${System.getProperty("os.name")}/${System.getProperty("os.arch")})."
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { BhlBinaryInstaller.fetchReleases(EmptyProgressIndicator()) }
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { releases ->
                    compatibleReleases = releases.filter { BhlBinaryInstaller.findAsset(it, platformSuffix) != null }
                    releasesLoaded = true
                    releaseCombo.model = javax.swing.DefaultComboBoxModel(compatibleReleases.toTypedArray())
                    compatibleReleases.firstOrNull { it.tagName == settings.downloadedReleaseTag }
                        ?.let { releaseCombo.selectedItem = it }
                    updateDownloadStatusLabel()
                }.onFailure { e ->
                    releaseStatusLabel.text = "Failed to fetch BHL releases: ${e.message}"
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        project.messageBus.syncPublisher(BhlSettings.SETTINGS_CHANGED_TOPIC).settingsChanged()
    }
}

/**
 * Downloads/verifies/extracts [release] for the current platform. On success, [onInstalled]
 * receives the installed binary's path.
 */
private fun installRelease(project: Project, release: BhlRelease, onInstalled: (String) -> Unit) {
    try {
        val path = ProgressManager.getInstance().run(
            object : Task.WithResult<Path, Exception>(project, "Downloading BHL ${release.version}", true) {
                override fun compute(indicator: ProgressIndicator) = BhlBinaryInstaller.install(release, indicator)
            },
        )
        onInstalled(path.toString())
        Messages.showInfoMessage(project, "Installed BHL ${release.version} to:\n$path", "Download BHL Binary")
    } catch (e: Exception) {
        Messages.showErrorDialog(project, "Failed to install BHL binary: ${e.message}", "Download BHL Binary")
    }
}

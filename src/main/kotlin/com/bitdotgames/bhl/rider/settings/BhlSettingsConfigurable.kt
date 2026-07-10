package com.bitdotgames.bhl.rider.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class BhlSettingsConfigurable(private val project: Project) : BoundConfigurable("BHL") {
    private val settings = BhlSettings.getInstance(project)

    override fun createPanel() = panel {
        row("Executable path:") {
            val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select BHL Executable")
            val field = TextFieldWithBrowseButton().apply {
                addBrowseFolderListener(project, descriptor)
            }
            cell(field)
                .bindText(settings::executablePath)
                .comment("Path to the BHL executable/script. Leave empty to resolve \"bhl\" on PATH.")
                .align(AlignX.FILL)
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
            checkBox("Force rebuild on startup")
                .bindSelected(settings::forceRebuild)
                .comment("Sets BHL_REBUILD=1 and BHL_SILENT=1 when launching the language server.")
        }
        row {
            checkBox("Trace LSP traffic")
                .bindSelected(settings::traceLsp)
                .comment(
                    "Mirrors raw JSON-RPC requests/responses into the BHL LSP console " +
                        "(truncated). Takes effect immediately, no server restart needed.",
                )
        }
    }

    override fun apply() {
        super.apply()
        project.messageBus.syncPublisher(BhlSettings.SETTINGS_CHANGED_TOPIC).settingsChanged()
    }
}

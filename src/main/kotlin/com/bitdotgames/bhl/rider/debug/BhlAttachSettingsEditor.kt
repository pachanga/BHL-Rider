package com.bitdotgames.bhl.rider.debug

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class BhlAttachSettingsEditor : SettingsEditor<BhlAttachRunConfiguration>() {
    private val hostField = JBTextField()
    private val portField = JBTextField()

    override fun resetEditorFrom(config: BhlAttachRunConfiguration) {
        hostField.text = config.host
        portField.text = config.port.toString()
    }

    override fun applyEditorTo(config: BhlAttachRunConfiguration) {
        config.host = hostField.text.trim().ifBlank { "localhost" }
        config.port = portField.text.trim().toIntOrNull() ?: config.port
    }

    override fun createEditor(): JComponent = panel {
        row("Host:") { cell(hostField).align(AlignX.FILL) }
        row("Port:") { cell(portField).align(AlignX.FILL) }
    }
}

package com.bitdotgames.bhl.rider.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

class BhlSettingsState {
    // Off by default: without it, the server is launched as bare "bhl" (resolved on PATH) or
    // the last-downloaded release (see downloadedBinaryPath) — executablePath/forceRebuild are
    // ignored even if still set from a previous session.
    var useCustomInstallation: Boolean = false
    var executablePath: String = ""
    // Set immediately on a successful "Download" in Settings (not gated behind Apply/OK, since
    // the binary is already on disk by then regardless of what the user does with the dialog).
    // Ignored when useCustomInstallation is on.
    var downloadedReleaseTag: String = ""
    var downloadedBinaryPath: String = ""
    var logFile: String = ""
    // Off by default: BHL_REBUILD makes the launcher run dotnet clean+publish before the
    // LSP starts, which can exceed the IDE's init timeout. Enable only for LSP development.
    var forceRebuild: Boolean = false
    // Mirror raw JSON-RPC frames into the BHL LSP console. Read per-frame, so toggling
    // takes effect without a server restart.
    var traceLsp: Boolean = false
    var projectDirectory: String = ""
    var selectedProjectFile: String = ""
}

@Service(Service.Level.PROJECT)
@State(name = "BhlRiderSettings", storages = [Storage("bhl-rider.xml")])
class BhlSettings : PersistentStateComponent<BhlSettingsState> {
    private var state = BhlSettingsState()

    override fun getState(): BhlSettingsState = state

    override fun loadState(state: BhlSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var useCustomInstallation: Boolean
        get() = state.useCustomInstallation
        set(value) {
            state.useCustomInstallation = value
        }

    var executablePath: String
        get() = state.executablePath
        set(value) {
            state.executablePath = value
        }

    var downloadedReleaseTag: String
        get() = state.downloadedReleaseTag
        set(value) {
            state.downloadedReleaseTag = value
        }

    var downloadedBinaryPath: String
        get() = state.downloadedBinaryPath
        set(value) {
            state.downloadedBinaryPath = value
        }

    var logFile: String
        get() = state.logFile
        set(value) {
            state.logFile = value
        }

    var forceRebuild: Boolean
        get() = state.forceRebuild
        set(value) {
            state.forceRebuild = value
        }

    var traceLsp: Boolean
        get() = state.traceLsp
        set(value) {
            state.traceLsp = value
        }

    var projectDirectory: String
        get() = state.projectDirectory
        set(value) {
            state.projectDirectory = value
        }

    var selectedProjectFile: String
        get() = state.selectedProjectFile
        set(value) {
            state.selectedProjectFile = value
        }

    companion object {
        fun getInstance(project: Project): BhlSettings = project.service()

        @Topic.ProjectLevel
        val SETTINGS_CHANGED_TOPIC: Topic<BhlSettingsChangeListener> = Topic.create(
            "BHL settings changed",
            BhlSettingsChangeListener::class.java,
        )
    }
}

interface BhlSettingsChangeListener {
    fun settingsChanged()
}

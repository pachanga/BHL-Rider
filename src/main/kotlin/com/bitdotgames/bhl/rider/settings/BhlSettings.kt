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
    var executablePath: String = ""
    var logFile: String = ""
    var forceRebuild: Boolean = true
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

    var executablePath: String
        get() = state.executablePath
        set(value) {
            state.executablePath = value
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

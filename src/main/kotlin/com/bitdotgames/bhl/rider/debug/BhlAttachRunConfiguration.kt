package com.bitdotgames.bhl.rider.debug

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EmptyRunProfileState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DebugAdapterId
import org.jdom.Element

private const val DEFAULT_HOST = "localhost"
private const val DEFAULT_PORT = 7777

/**
 * Attach-only, same as BHL-VSCode's `bhl` debug config type: the BHL debug server is embedded in
 * the host application and already listening, so there's nothing for Rider to launch — only
 * [host]/[port] to connect to. [getState] is never actually used to run anything (the platform's
 * `DapProgramRunner` drives the session directly from [DapLaunchArgumentsProvider]).
 *
 * Extends [LocatableConfigurationBase] rather than the more generic `RunConfigurationBase`
 * directly — that's what Rider's own real attach-style config (`DotNetRemoteConfiguration`, for
 * ".NET Remote Attach") extends too, and plain `RunConfigurationBase` subclasses are exercised far
 * less by Rider's split frontend/backend run-configuration editing UI.
 *
 * [RunConfigurationWithSuppressedDefaultRunAction]: there's nothing sensible for a plain "Run" to
 * do here (only "Debug" attaches to the DAP server) — mirrors how the bundled Godot plugin's own
 * attach-style DAP configuration suppresses it.
 */
class BhlAttachRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<Element>(project, factory, name),
    DapLaunchArgumentsProvider,
    RunConfigurationWithSuppressedDefaultRunAction {

    var host: String = DEFAULT_HOST
    var port: Int = DEFAULT_PORT

    override val adapterId: DebugAdapterId = BhlDebugAdapterId

    override val request: DapStartRequest = DapStartRequest.Attach

    override fun arguments(): Map<String, Any> = mapOf("host" to host, "port" to port)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = BhlAttachSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        EmptyRunProfileState.INSTANCE

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("host", host)
        element.setAttribute("port", port.toString())
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        element.getAttributeValue("host")?.let { host = it }
        element.getAttributeValue("port")?.toIntOrNull()?.let { port = it }
    }

    // RunConfigurationBase.clone()'s default is a shallow Object.clone() (which does copy host/
    // port along with everything else) plus a re-copy of the separate "Options" bean — this
    // explicit re-copy is redundant defensive belt-and-suspenders, matching the same pattern the
    // bundled Godot plugin's own attach-style DAP config uses for its own custom fields.
    override fun clone(): RunConfiguration {
        val cloned = super.clone() as BhlAttachRunConfiguration
        cloned.host = host
        cloned.port = port
        return cloned
    }
}

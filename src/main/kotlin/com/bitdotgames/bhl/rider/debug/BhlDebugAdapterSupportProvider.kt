package com.bitdotgames.bhl.rider.debug

import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.DebugAdapterId
import com.intellij.platform.dap.DebugAdapterSupportProvider

/**
 * The BHL debug server (`bhl.dap.BHLDebugServer`) is embedded in the host application (e.g. the
 * Unity editor) rather than being a process this plugin can launch — attaching over a plain TCP
 * socket is the only supported mode (mirrors BHL-VSCode's `debug.ts`).
 */
object BhlDebugAdapterId : DebugAdapterId("BHL", "BHL Debugger")

class BhlDebugAdapterSupportProvider : DebugAdapterSupportProvider<BhlDebugAdapterId> {
    override val adapterId: BhlDebugAdapterId = BhlDebugAdapterId

    override fun createDebugAdapterDescriptor(project: Project): DebugAdapterDescriptor<BhlDebugAdapterId> =
        BhlDebugAdapterDescriptor()
}

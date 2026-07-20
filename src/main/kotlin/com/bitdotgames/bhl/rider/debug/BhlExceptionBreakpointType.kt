package com.bitdotgames.bhl.rider.debug

import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType

/**
 * The BHL debug server declares no exception-breakpoint support (no `supportsExceptionInfoRequest`
 * capability); this placeholder exists only because [com.intellij.platform.dap.DapBreakpointsDescription]
 * requires a non-null exception breakpoint type. Deliberately not registered as an
 * `xdebugger.breakpointType` extension, so it never surfaces in the UI.
 */
class BhlExceptionBreakpointProperties : XBreakpointProperties<BhlExceptionBreakpointProperties>() {
    override fun getState(): BhlExceptionBreakpointProperties = this
    override fun loadState(state: BhlExceptionBreakpointProperties) {}
}

class BhlExceptionBreakpointType :
    XBreakpointType<XBreakpoint<BhlExceptionBreakpointProperties>, BhlExceptionBreakpointProperties>(
        "bhl-exception",
        "BHL Exception Breakpoint",
    ) {
    override fun createDefaultBreakpoint(
        creator: XBreakpointType.XBreakpointCreator<BhlExceptionBreakpointProperties>,
    ): XBreakpoint<BhlExceptionBreakpointProperties>? = null

    override fun getDisplayText(breakpoint: XBreakpoint<BhlExceptionBreakpointProperties>): String =
        "BHL exception breakpoint"
}

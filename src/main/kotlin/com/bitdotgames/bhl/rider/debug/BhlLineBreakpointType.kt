package com.bitdotgames.bhl.rider.debug

import com.bitdotgames.bhl.rider.BhlFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XLineBreakpointTypeBase

class BhlLineBreakpointType : XLineBreakpointTypeBase("bhl-line", "BHL Line Breakpoint", null) {
    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean = file.fileType == BhlFileType
}

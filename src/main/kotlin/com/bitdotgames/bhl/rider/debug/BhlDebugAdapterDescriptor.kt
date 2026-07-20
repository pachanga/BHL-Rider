package com.bitdotgames.bhl.rider.debug

import com.bitdotgames.bhl.rider.lsp.BhlLspConsoleService
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.platform.dap.DapBreakpointsDescription
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.connection.DebugAdapterHandle
import com.intellij.platform.dap.connection.DebugAdapterSocketConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * BHL's debug server is attach-only: it already listens on [BhlAttachRunConfiguration.host]:
 * [BhlAttachRunConfiguration.port] before we ever get here, so all this needs to do is open a
 * socket to it — [DebugAdapterSocketConnection] handles the connect-with-retry loop that
 * BHL-VSCode's `waitForServer()` polls for by hand.
 */
class BhlDebugAdapterDescriptor : DebugAdapterDescriptor<BhlDebugAdapterId>() {
    override val id: BhlDebugAdapterId = BhlDebugAdapterId

    override val breakpointsDescription: DapBreakpointsDescription =
        DapBreakpointsDescription(BhlLineBreakpointType::class.java, BhlExceptionBreakpointType::class.java)

    override suspend fun launchDebugAdapter(
        environment: ExecutionEnvironment,
        executionResult: ExecutionResult?,
        sessionId: String,
    ): DebugAdapterHandle {
        val config = environment.runProfile as? BhlAttachRunConfiguration
            ?: throw ExecutionException("BHL debug adapter requires a \"BHL: Attach\" run configuration")
        val console = BhlLspConsoleService.getInstance(environment.project)
        // reveal=true: surface the "BHL LSP" console immediately so a stalled/failed attach isn't
        // silent — the host app's BHLDebugServer must already be listening on host:port before
        // this succeeds, and retries below can take up to ~20s to give up.
        console.logInfo("DAP: connecting to ${config.host}:${config.port}…", reveal = true)
        try {
            return DebugAdapterSocketConnection(config.host, config.port, 40, 500.milliseconds) {
                console.logInfo("DAP: disconnected from ${config.host}:${config.port}", reveal = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            console.logInfo(
                "DAP: failed to connect to ${config.host}:${config.port} — is the BHL debug server " +
                    "(bhl.dap.BHLDebugServer) running and listening on that port? (${e.message})",
                reveal = true,
            )
            throw ExecutionException("Could not attach to BHL debug server at ${config.host}:${config.port}: ${e.message}", e)
        }
    }
}

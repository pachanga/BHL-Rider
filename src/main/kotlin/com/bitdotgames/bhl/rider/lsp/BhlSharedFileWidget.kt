package com.bitdotgames.bhl.rider.lsp

import com.bitdotgames.bhl.rider.BhlFileType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedStatusBarPopup
import com.intellij.platform.lsp.api.LspServerManager
import java.nio.file.Path

private const val WIDGET_ID = "BHLSharedFileContext"

class BhlSharedFileWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "BHL Shared File Context"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = BhlSharedFileWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

/**
 * Status bar widget shown for any BHL file whose owning project is known (see
 * [BhlResolvedProjectsCache]): displays which project's context it's currently being viewed as,
 * and — when the file sits in a directory shared between more than one project's `src_dirs` (see
 * [BhlSharedFileOwnershipService]) — lets you switch on demand, replacing the earlier design
 * where opening/focusing such a file forced a picker every time. Hidden only when no owning
 * project is known at all (e.g. no `bhl.proj` found for this file).
 */
class BhlSharedFileWidget(project: Project) : EditorBasedStatusBarPopup(project, false) {
    override fun ID(): String = WIDGET_ID

    override fun createInstance(project: Project): StatusBarWidget = BhlSharedFileWidget(project)

    protected override fun getWidgetState(file: VirtualFile?): EditorBasedStatusBarPopup.WidgetState {
        if (file == null || file.fileType != BhlFileType) return WidgetState.HIDDEN
        val candidates = BhlResolvedProjectsCache.getInstance(project).findAllOwning(file)
        if (candidates.isEmpty()) return WidgetState.HIDDEN
        val current = BhlSharedFileOwnershipService.getInstance(project).resolveOwner(file, candidates)
        val tooltip = if (candidates.size > 1) {
            "Shared BHL file — click to view as a different project"
        } else {
            "BHL project: ${current.displayName()}"
        }
        return WidgetState(tooltip, "BHL: ${current.displayName()}", true)
    }

    override fun createPopup(context: DataContext): ListPopup {
        val file = getSelectedFile() ?: error("BHL shared-file widget popup requested with no selected file")
        val ownership = BhlSharedFileOwnershipService.getInstance(project)
        val candidates = BhlResolvedProjectsCache.getInstance(project).findAllOwning(file)
        val current = ownership.resolveOwner(file, candidates)

        val group = DefaultActionGroup()
        for (candidate in candidates) {
            group.add(
                object : AnAction(candidate.displayName(), null, if (candidate == current) AllIcons.Actions.Checked else null) {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                    override fun actionPerformed(e: AnActionEvent) {
                        if (ownership.setOwner(file, candidate)) {
                            // A plain close+reopen (reconnectBhlFile) is NOT enough here: the platform's
                            // LspServerImpl permanently caches a per-file "unsupported" result the first
                            // time isSupportedFile returns false for it (LspServerImpl.unsupportedFilePaths,
                            // never evicted for that server instance's lifetime) — so the *other* project's
                            // server, which returned false for this file before the switch, would keep
                            // returning false forever no matter how many times the file is reopened. Only a
                            // fresh server instance (i.e. an actual restart) clears that cache; the platform
                            // then automatically re-routes currently-open files to the new instances.
                            BhlLspConsoleService.getInstance(project).logInfo(
                                "shared-file owner switched to ${candidate.displayName()} for ${file.name} — " +
                                    "restarting BHL servers to clear the platform's per-file routing cache",
                            )
                            LspServerManager.getInstance(project)
                                .stopAndRestartIfNeeded(BhlLspServerSupportProvider::class.java)
                        }
                        update()
                    }
                },
            )
        }
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "View \"${file.name}\" as",
            group,
            context,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }
}

private fun Path.displayName(): String = fileName?.toString() ?: toString()

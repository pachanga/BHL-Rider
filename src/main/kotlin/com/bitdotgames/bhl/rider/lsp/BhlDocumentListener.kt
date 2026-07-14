package com.bitdotgames.bhl.rider.lsp

import com.bitdotgames.bhl.rider.BhlFileType
import com.intellij.codeInsight.AutoPopupController
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * Triggers the parameter-info auto-popup (`(` / `,`, matching other editors) from document
 * changes rather than [com.intellij.codeInsight.editorActions.TypedHandlerDelegate] keystrokes:
 * fast consecutive keystrokes (e.g. typing `,` immediately followed by a space) can be
 * delivered to the editor as a single batched text-input event that never reaches
 * `TypedHandlerDelegate.charTyped` for either character. A document listener fires for every
 * text change regardless of how it was delivered, so it can't miss a burst the typed-handler
 * chain drops.
 */
fun installBhlDocumentListener(project: Project, disposable: Disposable) {
    EditorFactory.getInstance().eventMulticaster.addDocumentListener(
        object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val virtualFile = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (virtualFile.fileType != BhlFileType) return
                val editor = EditorFactory.getInstance().getEditors(event.document, project).firstOrNull() ?: return
                AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
            }
        },
        disposable,
    )
}

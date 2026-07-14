package com.bitdotgames.bhl.rider

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Auto-shows the parameter-info popup when typing the BHL server's signature-help trigger
 * characters (`(` and `,`), matching the behavior of other editors.
 *
 * `AutoPopupController.autoPopupParameterInfo` schedules the popup on a debounce timer; any
 * further keystroke before that timer fires cancels the pending schedule (confirmed: typing a
 * space immediately after `,` cancelled it before it ever showed, with no re-arm since space
 * isn't `(`/`,`). Rather than special-case which characters are "safe" to type in between, call
 * it on every keystroke unconditionally — once a popup/controller already exists for the
 * editor this is a cheap no-op refresh, and it guarantees the popup eventually appears once
 * typing pauses, regardless of what was typed right after the trigger character.
 *
 * Checks the virtual file's declared FileType rather than PSI language/class: `.bhl` files
 * have no ParserDefinition, so their PSI falls back to plain text, but `PsiFile.fileType`
 * still reports [BhlFileType] since that comes from the registered `fileType` extension.
 */
class BhlTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.fileType == BhlFileType) {
            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
        }
        return Result.CONTINUE
    }
}

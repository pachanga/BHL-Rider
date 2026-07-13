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
 * Checks the virtual file's declared FileType rather than PSI language/class: `.bhl` files
 * have no ParserDefinition, so their PSI falls back to plain text, but `PsiFile.fileType`
 * still reports [BhlFileType] since that comes from the registered `fileType` extension.
 */
class BhlTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.fileType == BhlFileType && (c == '(' || c == ',')) {
            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
        }
        return Result.CONTINUE
    }
}
